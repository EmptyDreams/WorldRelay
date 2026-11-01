package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonToken;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.protocol.HostEndpoint;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 世界名称、版本指针和所有权由同一份元数据原子发布。v1 只读兼容，写入一律使用 v2。 */
record WorldMetadata(String worldId, String name, String createdAt, State state) {

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
    }

    WorldMetadata withState(State next) {
        return new WorldMetadata(worldId, name, createdAt, next);
    }

    String toJson() {
        JsonWriter writer = new JsonWriter().beginObject().name("formatVersion").value(2)
            .name("worldId").value(worldId).name("name").value(name).name("createdAt").value(createdAt)
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
        return writer.endObject().endObject().finish();
    }

    static WorldMetadata parse(String json) {
        JsonReader reader = new JsonReader(json, 4096, 4);
        Set<String> fields = new HashSet<>();
        int format = 0;
        String id = null, name = null, createdAt = null;
        State state = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            fields.add(field);
            switch (field) {
                case "formatVersion" -> format = reader.nextInt();
                case "worldId" -> id = reader.nextString();
                case "name" -> name = reader.nextString();
                case "createdAt" -> createdAt = reader.nextString();
                case "state" -> state = readState(reader);
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
        } else throw new IllegalArgumentException("Unsupported world metadata version");
        return new WorldMetadata(id, name, createdAt, state);
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