package top.kmar.mc.worldreplay.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 有界、无重定向的协调请求；仅在后台调用，不记录凭据或服务端正文。 */
final class WorldOwnershipClient {

    private static final Set<String> PHASES = Set.of("PREPARING", "STARTING", "RUNNING", "STOPPING", "COMMITTING");

    record View(String worldId, long epoch, String sessionId, String phase, boolean hasSave,
                Long baseVersion, Long committedVersion, int heartbeatIntervalSeconds) {

        void checkSession(LocalWorldSession session) throws IOException {
            if (!worldId.equals(session.worldId()) || (session.sessionId() != null
                && (!sessionId.equals(session.sessionId()) || epoch != session.epoch()))) {
                throw new IOException("Ownership response does not match session");
            }
            if (session.sessionId() != null && !Objects.equals(session.baseVersion(), baseVersion)) {
                throw new IOException("Ownership base version changed within session");
            }
        }

    }

    static View acquire(ClientConnectionSettings settings, String user, LocalWorldSession session) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("worldId", session.worldId());
        body.addProperty("deviceId", session.deviceId());
        body.addProperty("requestId", session.requestId());
        View view = parse(post(settings, user, "acquire", body, false));
        view.checkSession(session);
        return view;
    }

    static View command(
        ClientConnectionSettings settings, String user, LocalWorldSession session,
        String action, String stage
    ) throws IOException {
        JsonObject body = identity(session);
        if (stage != null) body.addProperty("stage", stage);
        if ("SAVE_SYNCED".equals(stage) || "UPLOAD_COMPLETE".equals(stage)) addBase(body, session.baseVersion());
        String response = post(settings, user, action, body, false);
        View view = parse(response);
        view.checkSession(session);
        return view;
    }

    /** 取消成功返回 204，没有所有权视图；与必须返回视图的协调操作分开。 */
    static void cancelCreation(ClientConnectionSettings settings, String user, LocalWorldSession session) throws IOException {
        post(settings, user, "cancel-creation", identity(session), true);
    }

    static JsonObject identity(LocalWorldSession session) {
        JsonObject body = new JsonObject();
        body.addProperty("worldId", session.worldId());
        body.addProperty("deviceId", session.deviceId());
        body.addProperty("sessionId", Objects.requireNonNull(session.sessionId()));
        body.addProperty("epoch", session.epoch());
        return body;
    }

    static void addBase(JsonObject body, Long base) {
        if (base == null) body.add("baseVersion", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("baseVersion", base);
    }

    static String post(
        ClientConnectionSettings settings, String user, String action,
        JsonObject body, boolean noContent
    ) throws IOException {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build()) {
            String credentials = Base64.getEncoder().encodeToString((user + ":" + settings.token()).getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(settings.serverUri().resolve("/api/v1/worlds/" + action))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            var pending = client.sendAsync(request, info -> new BoundedBody());
            try {
                // 覆盖完整响应体；ofInputStream 的 send 返回后，普通 read 没有请求超时保护。
                var response = pending.get(10, TimeUnit.SECONDS);
                byte[] bytes = response.body();
                int status = response.statusCode();
                if (noContent ? status != 204 : status != 200 && status != 201 && status != 202) {
                    throw new IOException("Ownership request failed: HTTP " + status);
                }
                if (noContent && bytes.length != 0) throw new IOException("Unexpected cancellation response");
                return new String(bytes, StandardCharsets.UTF_8);
            } finally {
                pending.cancel(true);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Ownership request interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("Ownership request failed or timed out");
        } catch (RuntimeException failure) {
            throw new IOException("Invalid ownership request or settings");
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;
        private boolean done;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) return;
            for (var item : items) {
                if (item.remaining() > 65536 - size) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Ownership response exceeds limit"));
                    return;
                }
                size += item.remaining();
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable failure) {
            if (!done) {
                done = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }

    }

    static View parse(String json) throws IOException {
        try {
            JsonObject body = JsonParser.parseString(json).getAsJsonObject();
            String world = uuid(body, "worldId"), session = uuid(body, "sessionId");
            long epoch = positiveLong(body, "epoch");
            String phase = ClientJson.requiredString(body, "phase");
            if (!PHASES.contains(phase) || !body.getAsJsonPrimitive("hasSave").isBoolean()) throw new IllegalArgumentException();
            boolean hasSave = body.get("hasSave").getAsBoolean();
            Long base = version(body, "baseVersion"), committed = version(body, "committedVersion");
            boolean hasCommittedSave = committed != null;
            if (hasSave != hasCommittedSave || !Objects.equals(base, committed)) throw new IllegalArgumentException();
            int interval = Math.toIntExact(positiveLong(body, "heartbeatIntervalSeconds"));
            long timeout = positiveLong(body, "heartbeatTimeoutSeconds");
            if (timeout <= interval) throw new IllegalArgumentException();
            return new View(world, epoch, session, phase, hasSave, base, committed, interval);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid ownership response");
        }
    }

    private static Long version(JsonObject body, String key) {
        return body.get(key).isJsonNull() ? null : positiveLong(body, key);
    }

    private static long positiveLong(JsonObject body, String key) {
        var value = body.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException();
        long result = value.getAsBigDecimal().longValueExact();
        if (result < 1) throw new IllegalArgumentException();
        return result;
    }

    private static String uuid(JsonObject body, String key) {
        String value = ClientJson.requiredString(body, key);
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        return value;
    }

}