package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldStore;
import top.kmar.worldrelay.server.world.WorldTransfers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 数据通过 rsync 传输，HTTP 只传身份、会话和固定大小的摘要。 */
public final class WorldTransferHandler implements HttpHandler {

    public enum Action {DOWNLOAD, UPLOAD, COMMIT, COMMIT_STATUS, CLOSE_DOWNLOAD}

    private final WorldTransfers transfers;
    private final Action action;
    private final TransferJobs jobs;

    public WorldTransferHandler(WorldTransfers transfers, Action action, TransferJobs jobs) {
        this.transfers = transfers;
        this.action = action;
        this.jobs = jobs;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Transfer request exceeds limit");
            return;
        }
        Command command;
        try {
            command = parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_TRANSFER_REQUEST", "Invalid transfer request");
            return;
        }
        try {
            var owner = new WorldStore.Identity(exchange.getPrincipal().getUsername(), command.device, command.session, command.epoch);
            String jobKey = owner + ":" + action + ":" + command;
            String response;
            if (action == Action.COMMIT_STATUS || action == Action.CLOSE_DOWNLOAD) {
                if (action == Action.CLOSE_DOWNLOAD) jobs.invalidate(owner + ":" + Action.DOWNLOAD + ":" + command);
                response = execute(command, owner);
            } else {
                // 未授权请求不进入后台队列；长任务开始和完成时仍会再次校验任期。
                transfers.authorize(command.world, owner, command.base, command.transfer, command.hash, action != Action.DOWNLOAD);
                response = jobs.poll(jobKey, () -> execute(command, owner));
            }
            HttpResponses.bytes(exchange, response == null ? 202 : 200, "application/json; charset=utf-8",
                (response == null ? "{\"pending\":true}" : response).getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (Exception failure) {
            // 路径和清单内容由不可信上传提供，错误响应和日志均不回显它们。
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Snapshot transfer failed; ownership is retained");
            HttpResponses.error(exchange, 503, "SNAPSHOT_TRANSFER_FAILED", "Transfer verification or storage failed; retry is safe");
        }
    }

    private String execute(Command command, WorldStore.Identity owner) throws IOException, WorldAccessException {
        JsonWriter writer = new JsonWriter().beginObject();
        if (action == Action.COMMIT || action == Action.COMMIT_STATUS) {
            Long version;
            if (action == Action.COMMIT) version = transfers.commit(command.world, owner, command.base, command.transfer, command.hash);
            else version = transfers.status(command.world, owner, command.base, command.transfer, command.hash);
            writer.name("worldId").value(command.world).name("epoch").value(command.epoch)
                .name("uploadId").value(command.transfer).name("manifestHash").value(command.hash).name("version");
            if (version == null) writer.nullValue();
            else writer.value(version);
        } else if (action == Action.CLOSE_DOWNLOAD) {
            transfers.closeDownload(command.world, owner, command.transfer);
            return "{\"closed\":true}";
        } else {
            var ticket = transfers.begin(command.world, owner, command.base, command.transfer, action == Action.UPLOAD);
            writer.name("worldId").value(command.world).name("epoch").value(command.epoch)
                .name("uploadId").value(command.transfer).name("transferId").value(ticket.transferId())
                .name("privateKey").value(ticket.privateKey()).name("hostPublicKey").value(ticket.hostPublicKey())
                .name("rsyncCommand").beginObject().name("host").value(ticket.rsyncCommand().host())
                .name("port").value(ticket.rsyncCommand().port()).name("user").value(ticket.rsyncCommand().user())
                .name("arguments").beginArray();
            for (String argument : ticket.rsyncCommand().arguments()) {
                writer.value(argument);
            }
            writer.endArray().endObject().name("manifestHash");
            if (ticket.manifestHash() == null) writer.nullValue();
            else writer.value(ticket.manifestHash());
        }
        return writer.endObject().finish();
    }

    private record Command(String world, String device, String session, long epoch, Long base, String transfer, String hash) {

    }

    private Command parse(String json) {
        JsonReader reader = new JsonReader(json, 4096, 2);
        Set<String> fields = new HashSet<>();
        String world = null, device = null, session = null, transfer = null, hash = null;
        long epoch = 0;
        Long base = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            fields.add(name);
            switch (name) {
                case "worldId" -> world = reader.nextString();
                case "deviceId" -> device = reader.nextString();
                case "sessionId" -> session = reader.nextString();
                case "epoch" -> epoch = reader.nextLong();
                case "uploadId" -> transfer = reader.nextString();
                case "baseVersion" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else base = reader.nextLong();
                }
                case "manifestHash" -> hash = reader.nextString();
                default -> throw new IllegalArgumentException("Unknown transfer field");
            }
        }
        reader.endObject();
        reader.finish();
        Set<String> expected = new HashSet<>(Set.of("worldId", "deviceId", "sessionId", "epoch", "baseVersion", "uploadId"));
        if (action == Action.COMMIT || action == Action.COMMIT_STATUS) expected.add("manifestHash");
        if (!fields.equals(expected) || epoch < 1 || (base != null && base < 1)
            || ((action == Action.COMMIT || action == Action.COMMIT_STATUS) && (hash == null || !hash.matches("[0-9a-f]{64}"))))
            throw new IllegalArgumentException();
        WorldStore.validateId(world);
        WorldStore.validateId(device);
        WorldStore.validateId(session);
        WorldStore.validateId(transfer);
        return new Command(world, device, session, epoch, base, transfer, hash);
    }

}