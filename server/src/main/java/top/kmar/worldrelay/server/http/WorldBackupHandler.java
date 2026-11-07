package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.json.JsonReader;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldBackups;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 仅挂载在本机管理监听器；恢复要求显式确认及预期版本，大文件任务返回 202 后轮询。 */
public final class WorldBackupHandler implements HttpHandler {

    public enum Action {BACKUPS, RESTORE_PREVIEW, RESTORE}

    private final WorldBackups backups;
    private final Action action;
    private final TransferJobs jobs;

    public WorldBackupHandler(WorldBackups backups, Action action, TransferJobs jobs) {
        this.backups = backups;
        this.action = action;
        this.jobs = jobs;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpRequests.rejectNonJsonContentType(exchange)) return;
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            exchange.getResponseHeaders().set("Connection", "close");
            HttpResponses.error(exchange, 413, "PAYLOAD_TOO_LARGE", "Backup request exceeds 4096 bytes");
            return;
        }
        try {
            var reader = new JsonReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString(), 4096, 2);
            String world = null, restoreId = null, hash = null;
            long backupVersion = 0, expectedVersion = 0, expectedEpoch = -1;
            boolean confirm = false;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                fields.add(field);
                switch (field) {
                    case "worldId" -> world = reader.nextString();
                    case "restoreId" -> restoreId = reader.nextString();
                    case "manifestHash" -> hash = reader.nextString();
                    case "backupVersion" -> backupVersion = reader.nextLong();
                    case "expectedVersion" -> expectedVersion = reader.nextLong();
                    case "expectedEpoch" -> expectedEpoch = reader.nextLong();
                    case "confirm" -> confirm = reader.nextBoolean();
                    default -> throw new IllegalArgumentException("Unknown backup request field");
                }
            }
            reader.endObject();
            reader.finish();
            Set<String> expected = switch (action) {
                case BACKUPS -> Set.of("worldId");
                case RESTORE_PREVIEW -> Set.of("worldId", "backupVersion");
                case RESTORE -> Set.of("worldId", "restoreId", "backupVersion", "expectedVersion", "expectedEpoch", "manifestHash", "confirm");
            };
            if (!fields.equals(expected)) throw new IllegalArgumentException("Missing backup request field");
            WorldStore.validateId(world);
            String response;
            if (action == Action.BACKUPS) response = backups.list(world);
            else if (action == Action.RESTORE_PREVIEW) {
                if (backupVersion < 1) throw new IllegalArgumentException("Invalid backup version");
                var preview = backups.preview(world, backupVersion);
                var writer = new JsonWriter();
                preview.writeJson(writer, false);
                response = writer.finish();
            } else {
                if (!confirm)
                    throw new WorldAccessException(400, "RESTORE_CONFIRMATION_REQUIRED", "Review the preview and explicitly confirm the restore");
                var request = new WorldBackups.Restore(world, restoreId, backupVersion, expectedVersion, expectedEpoch, hash);
                backups.authorize(request);
                response = jobs.poll("restore:" + request, () -> backups.restore(request).toJson());
            }
            HttpResponses.bytes(exchange, response == null ? 202 : 200, "application/json; charset=utf-8",
                (response == null ? "{\"pending\":true}" : response).getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_BACKUP_REQUEST", "Invalid backup management request");
        } catch (Exception failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Backup operation failed; authoritative metadata is retained");
            HttpResponses.error(exchange, 503, "BACKUP_OPERATION_FAILED", "Backup verification or storage failed; retry is safe");
        }
    }

}