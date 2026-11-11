package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.HostEndpoint;

import java.time.Instant;
import java.util.*;

/** 所有权、有界提交回执和取消审计一同原子发布；v9 固定世界的登录模式，仍可读取 v1～v8。 */
record WorldMetadata(String worldId, String name, String createdAt, State state, List<Cancellation> cancellations, List<Commit> commits, boolean allowOfflinePlayers) {

    WorldMetadata(String worldId, String name, String createdAt, State state, List<Cancellation> cancellations, List<Commit> commits) {
        this(worldId, name, createdAt, state, cancellations, commits, false);
    }

    WorldMetadata(String worldId, String name, String createdAt, State state, List<Cancellation> cancellations) {
        this(worldId, name, createdAt, state, cancellations, List.of());
    }

    WorldMetadata(String worldId, String name, String createdAt, State state) {
        this(worldId, name, createdAt, state, List.of());
    }

    record Commit(String uploadId, Owner owner, long epoch, Long baseVersion, long version, String manifestHash,
                  String committedAt, Long restoredFromVersion) {

        Commit(String uploadId, Owner owner, long epoch, Long baseVersion, long version, String manifestHash, String committedAt) {
            this(uploadId, owner, epoch, baseVersion, version, manifestHash, committedAt, null);
        }

        Commit(String uploadId, Owner owner, long epoch, Long baseVersion, long version, String manifestHash) {
            this(uploadId, owner, epoch, baseVersion, version, manifestHash, null);
        }

        Commit {
            WorldStore.validateId(uploadId);
            Objects.requireNonNull(owner);
            if (epoch < 1 || version < 1 || (baseVersion != null && baseVersion < 1)
                || version != (baseVersion == null ? 1 : Math.addExact(baseVersion, 1))
                || !manifestHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid commit receipt");
            if (restoredFromVersion != null && (baseVersion == null || restoredFromVersion < 1 || restoredFromVersion > baseVersion))
                throw new IllegalArgumentException("Invalid restore source");
            if (committedAt != null) {
                // 只验证时间格式，保留回执中的原始字符串。
                //noinspection ResultOfMethodCallIgnored
                Instant.parse(committedAt);
            }
        }

        boolean matches(WorldStore.Identity identity) {
            return epoch == identity.epoch() && owner.username.equals(identity.username())
                && owner.deviceId.equals(identity.deviceId()) && owner.sessionId.equals(identity.sessionId());
        }

    }

    record Cancellation(Owner owner, long epoch, String cancelledAt, String abandonId, Long committedVersion,
                        String lastCommittedAt, WorldPhase phase, String reason) {

        Cancellation(Owner owner, long epoch, String cancelledAt) {
            this(owner, epoch, cancelledAt, null, null, null, null, null);
        }

        Cancellation {
            Objects.requireNonNull(owner);
            if (epoch < 1) throw new IllegalArgumentException("Invalid cancelled epoch");
            // 只验证审计时间格式，存储保留调用方提供的原始字符串。
            //noinspection ResultOfMethodCallIgnored
            Instant.parse(cancelledAt);
            if (abandonId != null) {
                WorldStore.validateId(abandonId);
                WorldAbandonment.validate(epoch, committedVersion, phase, lastCommittedAt, reason);
            } else if (committedVersion != null || lastCommittedAt != null || phase != null || reason != null) {
                throw new IllegalArgumentException("Invalid cancellation audit fields");
            }
        }

        boolean matches(WorldStore.Identity identity) {
            return epoch == identity.epoch() && owner.username.equals(identity.username())
                && owner.deviceId.equals(identity.deviceId()) && owner.sessionId.equals(identity.sessionId());
        }

    }

    record Owner(String username, String deviceId, String sessionId, String requestId) {

        Owner {
            UserStore.validateUsername(username);
            WorldStore.validateId(deviceId);
            WorldStore.validateId(sessionId);
            WorldStore.validateId(requestId);
        }

    }

    record State(long epoch, WorldPhase phase, Owner owner, Long baseVersion, Long committedVersion,
                 String lastCommittedAt, HostEndpoint endpoint) {

        State {
            Objects.requireNonNull(phase);
            if (epoch < 0 || (committedVersion != null && committedVersion < 1)
                || (committedVersion == null) != (lastCommittedAt == null)) {
                throw new IllegalArgumentException("Invalid version state");
            }
            if (lastCommittedAt != null) {
                //noinspection ResultOfMethodCallIgnored
                Instant.parse(lastCommittedAt);
            }
            if (phase == WorldPhase.FREE) {
                if (owner != null || baseVersion != null || endpoint != null) {
                    throw new IllegalArgumentException("Invalid free state");
                }
            } else if (epoch == 0 || owner == null || !Objects.equals(baseVersion, committedVersion)) {
                throw new IllegalArgumentException("Invalid owned state");
            }
            if ((phase == WorldPhase.RUNNING) == (endpoint == null)) {
                throw new IllegalArgumentException("Invalid endpoint state");
            }
        }

        static State free() {
            return new State(0, WorldPhase.FREE, null, null, null, null, null);
        }

        State transition(WorldPhase next, HostEndpoint nextEndpoint) {
            return new State(epoch, next, owner, baseVersion, committedVersion, lastCommittedAt, nextEndpoint);
        }

    }

    WorldMetadata {
        WorldStore.validateId(worldId);
        WorldStore.validateName(name);
        //noinspection ResultOfMethodCallIgnored
        Instant.parse(createdAt);
        Objects.requireNonNull(state);
        cancellations = List.copyOf(cancellations);
        commits = List.copyOf(commits);
        long previous = 0;
        for (Cancellation cancellation : cancellations) {
            if (cancellation.epoch <= previous || cancellation.epoch >= state.epoch) {
                throw new IllegalArgumentException("Invalid cancellation history");
            }
            previous = cancellation.epoch;
        }
        long version = commits.isEmpty() ? 0 : commits.getFirst().version() - 1;
        for (Commit commit : commits) {
            if (commit.version != ++version || commit.epoch > state.epoch) throw new IllegalArgumentException("Invalid commit history");
        }
        if (!commits.isEmpty() && !Objects.equals(state.committedVersion, version)) throw new IllegalArgumentException("Commit history mismatch");
    }

    WorldMetadata withState(State next) {
        return new WorldMetadata(worldId, name, createdAt, next, cancellations, commits, allowOfflinePlayers);
    }

    WorldMetadata withHistory(List<Cancellation> retainedCancellations, List<Commit> retainedCommits) {
        return new WorldMetadata(worldId, name, createdAt, state, retainedCancellations, retainedCommits, allowOfflinePlayers);
    }

    String toJson() {
        int format = 9;
        JsonWriter writer = new JsonWriter().beginObject().name("formatVersion")
            .value(format)
            .name("worldId").value(worldId).name("name").value(name).name("createdAt").value(createdAt)
            .name("allowOfflinePlayers").value(allowOfflinePlayers)
            .name("state").beginObject().name("epoch").value(state.epoch)
            .name("phase").value(state.phase.name()).name("owner");
        if (state.owner == null) writer.nullValue();
        else writer.beginObject().name("username").value(state.owner.username)
            .name("deviceId").value(state.owner.deviceId).name("sessionId").value(state.owner.sessionId)
            .name("requestId").value(state.owner.requestId).endObject();
        writer.name("baseVersion");
        if (state.baseVersion == null) writer.nullValue();
        else writer.value(state.baseVersion);
        writer.name("committedVersion");
        if (state.committedVersion == null) writer.nullValue();
        else writer.value(state.committedVersion);
        writer.name("lastCommittedAt");
        if (state.lastCommittedAt == null) writer.nullValue();
        else writer.value(state.lastCommittedAt);
        writer.name("endpoint");
        if (state.endpoint == null) writer.nullValue();
        else state.endpoint.writeJson(writer);
        writer.endObject();
        writer.name("cancellations").beginArray();
        for (Cancellation cancellation : cancellations) {
            Owner owner = cancellation.owner;
            writer.beginObject().name("username").value(owner.username).name("deviceId").value(owner.deviceId)
                .name("sessionId").value(owner.sessionId).name("requestId").value(owner.requestId)
                .name("epoch").value(cancellation.epoch).name("cancelledAt").value(cancellation.cancelledAt);
            if (cancellation.abandonId != null) {
                writer.name("abandonId").value(cancellation.abandonId).name("actor").value("local-admin").name("committedVersion");
                if (cancellation.committedVersion == null) writer.nullValue();
                else writer.value(cancellation.committedVersion);
                writer.name("lastCommittedAt");
                if (cancellation.lastCommittedAt == null) writer.nullValue();
                else writer.value(cancellation.lastCommittedAt);
                writer.name("phase").value(cancellation.phase.name()).name("reason").value(cancellation.reason);
            }
            writer.endObject();
        }
        writer.endArray();
        writer.name("commits").beginArray();
        for (Commit commit : commits) {
            Owner owner = commit.owner;
            writer.beginObject().name("uploadId").value(commit.uploadId).name("owner").beginObject()
                .name("username").value(owner.username).name("deviceId").value(owner.deviceId)
                .name("sessionId").value(owner.sessionId).name("requestId").value(owner.requestId).endObject()
                .name("epoch").value(commit.epoch).name("baseVersion");
            if (commit.baseVersion == null) writer.nullValue();
            else writer.value(commit.baseVersion);
            writer.name("version").value(commit.version).name("manifestHash").value(commit.manifestHash);
            if (commit.committedAt != null) writer.name("committedAt").value(commit.committedAt);
            if (commit.restoredFromVersion != null) writer.name("restoredFromVersion").value(commit.restoredFromVersion);
            writer.endObject();
        }
        writer.endArray();
        return writer.endObject().finish();
    }

    static WorldMetadata parse(String json) {
        JsonReader reader = new JsonReader(json, WorldStore.MAX_METADATA_BYTES, 5);
        Set<String> fields = new HashSet<>();
        int format = 0;
        boolean allowOfflinePlayers = false;
        String id = null, name = null, createdAt = null;
        State state = null;
        List<Cancellation> cancellations = new ArrayList<>();
        List<Commit> commits = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "formatVersion" -> format = reader.nextInt();
                case "worldId" -> id = reader.nextString();
                case "name" -> name = reader.nextString();
                case "createdAt" -> createdAt = reader.nextString();
                case "allowOfflinePlayers" -> allowOfflinePlayers = reader.nextBoolean();
                case "state" -> state = readState(reader);
                case "cancellations" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        cancellations.add(readCancellation(reader));
                    }
                    reader.endArray();
                }
                case "commits" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        commits.add(readCommit(reader));
                    }
                    reader.endArray();
                }
                default -> throw new IllegalArgumentException("Unknown world metadata field");
            }
        }
        reader.endObject();
        reader.finish();
        if (format == 1) {
            requireFields(fields, Set.of("formatVersion", "worldId", "name", "createdAt"));
            state = State.free();
        } else if (format == 2) {
            requireFields(fields, Set.of("formatVersion", "worldId", "name", "createdAt", "state"));
        } else if (format == 3) {
            requireFields(fields, Set.of("formatVersion", "worldId", "name", "createdAt", "state", "cancellations"));
            if (cancellations.isEmpty()) throw new IllegalArgumentException("Missing cancellation history");
        } else if (format >= 4 && format <= 8) {
            requireFields(fields, Set.of("formatVersion", "worldId", "name", "createdAt", "state", "cancellations", "commits"));
            if (commits.isEmpty() && format != 8) throw new IllegalArgumentException("Missing commit history");
            if (format < 6 && commits.getFirst().version() != 1) throw new IllegalArgumentException("Incomplete legacy commit history");
            if (format == 8 && cancellations.stream().noneMatch(cancellation -> cancellation.abandonId != null))
                throw new IllegalArgumentException("Missing abandonment audit");
        } else if (format == 9) {
            requireFields(fields, Set.of("formatVersion", "worldId", "name", "createdAt", "state", "cancellations", "commits", "allowOfflinePlayers"));
        } else throw new IllegalArgumentException("Unsupported world metadata version");
        return new WorldMetadata(id, name, createdAt, state, cancellations, commits, allowOfflinePlayers);
    }

    private static Commit readCommit(JsonReader reader) {
        Set<String> fields = new HashSet<>();
        String id = null, hash = null, at = null;
        Owner owner = null;
        long epoch = 0, version = 0;
        Long base = null, restored = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "uploadId" -> id = reader.nextString();
                case "owner" -> owner = readOwner(reader);
                case "epoch" -> epoch = reader.nextLong();
                case "baseVersion" -> base = nullableLong(reader);
                case "version" -> version = reader.nextLong();
                case "manifestHash" -> hash = reader.nextString();
                case "committedAt" -> at = reader.nextString();
                case "restoredFromVersion" -> restored = reader.nextLong();
                default -> throw new IllegalArgumentException("Unknown commit field");
            }
        }
        reader.endObject();
        Set<String> expected = new HashSet<>(Set.of("uploadId", "owner", "epoch", "baseVersion", "version", "manifestHash"));
        if (at != null) expected.add("committedAt");
        if (restored != null) expected.add("restoredFromVersion");
        requireFields(fields, expected);
        return new Commit(id, owner, epoch, base, version, hash, at, restored);
    }

    private static Cancellation readCancellation(JsonReader reader) {
        Set<String> fields = new HashSet<>();
        String username = null, device = null, session = null, request = null, at = null;
        String abandonId = null, lastCommittedAt = null, reason = null, actor = null;
        WorldPhase phase = null;
        Long version = null;
        long epoch = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "username" -> username = reader.nextString();
                case "deviceId" -> device = reader.nextString();
                case "sessionId" -> session = reader.nextString();
                case "requestId" -> request = reader.nextString();
                case "epoch" -> epoch = reader.nextLong();
                case "cancelledAt" -> at = reader.nextString();
                case "abandonId" -> abandonId = reader.nextString();
                case "actor" -> actor = reader.nextString();
                case "committedVersion" -> version = nullableLong(reader);
                case "lastCommittedAt" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else lastCommittedAt = reader.nextString();
                }
                case "phase" -> phase = WorldPhase.valueOf(reader.nextString());
                case "reason" -> reason = reader.nextString();
                default -> throw new IllegalArgumentException("Unknown cancellation field");
            }
        }
        reader.endObject();
        Set<String> expected = new HashSet<>(Set.of("username", "deviceId", "sessionId", "requestId", "epoch", "cancelledAt"));
        if (abandonId != null) {
            expected.addAll(Set.of("abandonId", "actor", "committedVersion", "lastCommittedAt", "phase", "reason"));
            if (!"local-admin".equals(actor)) throw new IllegalArgumentException("Invalid abandonment actor");
        }
        requireFields(fields, expected);
        return new Cancellation(new Owner(username, device, session, request), epoch, at, abandonId, version, lastCommittedAt, phase, reason);
    }

    private static State readState(JsonReader reader) {
        Set<String> fields = new HashSet<>();
        long epoch = -1;
        WorldPhase phase = null;
        Owner owner = null;
        Long base = null, committed = null;
        String committedAt = null;
        HostEndpoint endpoint = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "epoch" -> epoch = reader.nextLong();
                case "phase" -> phase = WorldPhase.valueOf(reader.nextString());
                case "owner" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else owner = readOwner(reader);
                }
                case "baseVersion" -> base = nullableLong(reader);
                case "committedVersion" -> committed = nullableLong(reader);
                case "lastCommittedAt" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else committedAt = reader.nextString();
                }
                case "endpoint" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else endpoint = readEndpoint(reader);
                }
                default -> throw new IllegalArgumentException("Unknown world state field");
            }
        }
        reader.endObject();
        requireFields(fields, Set.of("epoch", "phase", "owner", "baseVersion", "committedVersion", "lastCommittedAt", "endpoint"));
        return new State(epoch, phase, owner, base, committed, committedAt, endpoint);
    }

    private static Owner readOwner(JsonReader reader) {
        Set<String> fields = new HashSet<>();
        String user = null, device = null, session = null, request = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "username" -> user = reader.nextString();
                case "deviceId" -> device = reader.nextString();
                case "sessionId" -> session = reader.nextString();
                case "requestId" -> request = reader.nextString();
                default -> throw new IllegalArgumentException("Unknown owner field");
            }
        }
        reader.endObject();
        requireFields(fields, Set.of("username", "deviceId", "sessionId", "requestId"));
        return new Owner(user, device, session, request);
    }

    private static HostEndpoint readEndpoint(JsonReader reader) {
        Set<String> fields = new HashSet<>();
        String address = null;
        int port = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "address" -> address = reader.nextString();
                case "port" -> port = reader.nextInt();
                default -> throw new IllegalArgumentException("Unknown endpoint field");
            }
        }
        reader.endObject();
        requireFields(fields, Set.of("address", "port"));
        return new HostEndpoint(address, port);
    }

    private static Long nullableLong(JsonReader reader) {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextLong();
    }

    private static void requireFields(Set<String> actual, Set<String> expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("Missing world metadata fields");
    }

}
