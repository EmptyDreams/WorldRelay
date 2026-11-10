package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.kmar.worldrelay.server.auth.UserStore;
import top.kmar.worldrelay.server.json.JsonWriter;
import top.kmar.worldrelay.server.world.WorldAccessException;
import top.kmar.worldrelay.server.world.WorldAdministration;
import top.kmar.worldrelay.server.world.WorldRetention;
import top.kmar.worldrelay.server.world.WorldStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** 本机管理查询与维护；删除请求必须来自仍匹配当前元数据的已确认预览。 */
public final class WorldManagementHandler implements HttpHandler {

    public enum Action {LIST, DETAIL, HISTORY, RENAME, DELETE_PREVIEW, DELETE, BACKUP_DELETE_PREVIEW, BACKUP_DELETE}

    private static final Set<String> DELETE_FIELDS = Set.of("worldId", "deleteId", "expectedName", "expectedEpoch", "expectedVersion", "metadataHash", "confirm");
    private static final Set<String> BACKUP_DELETE_FIELDS = Set.of("worldId", "deleteId", "backupVersion", "snapshotId", "expectedVersion", "expectedEpoch", "confirm");
    private final WorldStore worlds;
    private final WorldAdministration administration;
    private final WorldRetention retention;
    private final UserStore users;
    private final TransferJobs jobs;
    private final long recycleHours;
    private final Action action;

    public WorldManagementHandler(WorldStore worlds, WorldRetention retention, UserStore users, TransferJobs jobs, long recycleHours, Action action) {
        this.worlds = worlds;
        this.administration = new WorldAdministration(worlds);
        this.retention = retention;
        this.users = users;
        this.jobs = jobs;
        this.recycleHours = recycleHours;
        this.action = action;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (action != Action.LIST && HttpRequests.rejectNonJsonContentType(exchange)) return;
        try {
            String response;
            if (action == Action.LIST) response = administration.list();
            else {
                Set<String> required = switch (action) {
                    case RENAME -> Set.of("worldId", "name", "expectedName");
                    case DELETE -> DELETE_FIELDS;
                    case BACKUP_DELETE -> BACKUP_DELETE_FIELDS;
                    case BACKUP_DELETE_PREVIEW -> Set.of("worldId", "backupVersion");
                    default -> Set.of("worldId");
                };
                var body = AdminRequests.read(exchange, required, action == Action.HISTORY ? Set.of("limit", "beforeEpoch") : Set.of());
                String world = AdminRequests.string(body, "worldId");
                WorldStore.validateId(world);
                var writer = new JsonWriter();
                if (action == Action.BACKUP_DELETE_PREVIEW) {
                    response = retention.previewBackupDeletion(world, AdminRequests.requiredNumber(body, "backupVersion")).toJson(false);
                } else if (action == Action.BACKUP_DELETE) {
                    if (!Boolean.TRUE.equals(body.get("confirm")))
                        throw new WorldAccessException(400, "DELETE_CONFIRMATION_REQUIRED", "Preview and confirm backup deletion");
                    var request = new WorldRetention.BackupDeletion(world, AdminRequests.string(body, "deleteId"),
                        AdminRequests.requiredNumber(body, "backupVersion"), AdminRequests.string(body, "snapshotId"),
                        AdminRequests.requiredNumber(body, "expectedVersion"), AdminRequests.requiredNumber(body, "expectedEpoch"));
                    response = jobs.poll("backup-delete:" + request, world, null, "BACKUP_DELETE", () -> {
                        retention.deleteBackup(request);
                        return new JsonWriter().beginObject().name("worldId").value(world).name("deleteId").value(request.deleteId())
                            .name("backupVersion").value(request.backupVersion()).name("deleted").value(true).endObject().finish();
                    });
                    if (response == null) {
                        HttpResponses.bytes(exchange, 202, "application/json; charset=utf-8", "{\"pending\":true}".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                } else if (action == Action.HISTORY) {
                    int limit = body.containsKey("limit") ? Math.toIntExact(AdminRequests.requiredNumber(body, "limit")) : 50;
                    response = administration.history(world, body.containsKey("beforeEpoch") ? AdminRequests.requiredNumber(body, "beforeEpoch") : null, limit);
                } else if (action == Action.DELETE_PREVIEW) {
                    var preview = retention.previewDeletion(world);
                    writer.beginObject().name("worldId").value(world).name("deleteId").value(preview.deleteId())
                        .name("expectedName").value(preview.expectedName()).name("expectedEpoch").value(preview.expectedEpoch())
                        .name("expectedVersion");
                    if (preview.expectedVersion() == null) writer.nullValue();
                    else writer.value(preview.expectedVersion());
                    response = writer.name("metadataHash").value(preview.metadataHash()).name("confirm").value(false).endObject().finish();
                } else if (action == Action.DELETE) {
                    if (!Boolean.TRUE.equals(body.get("confirm")))
                        throw new WorldAccessException(400, "DELETE_CONFIRMATION_REQUIRED", "Preview and confirm world deletion");
                    var request = new WorldStore.Deletion(world, AdminRequests.string(body, "deleteId"), AdminRequests.string(body, "expectedName"),
                        AdminRequests.requiredNumber(body, "expectedEpoch"), AdminRequests.number(body, "expectedVersion"), AdminRequests.string(body, "metadataHash"));
                    var deletedAt = retention.delete(request);
                    jobs.invalidateWorld(world);
                    users.revokeWorldTransfers(world);
                    response = writer.beginObject().name("worldId").value(world).name("deleteId").value(request.deleteId())
                        .name("deleted").value(true).name("deletedAt").value(deletedAt.toString())
                        .name("purgeAfter").value(deletedAt.plus(Duration.ofHours(recycleHours)).toString()).endObject().finish();
                    System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "World deleted: world={0}, deletion={1}", world, request.deleteId());
                } else {
                    if (action == Action.RENAME) {
                        worlds.rename(world, AdminRequests.string(body, "expectedName"), AdminRequests.string(body, "name"));
                        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "World renamed: world={0}", world);
                    }
                    writer.beginObject().name("world");
                    administration.detail(writer, world);
                    writer.name("tasks");
                    writeTasks(writer, jobs.stats(world));
                    if (action == Action.DETAIL) {
                        writer.name("storage");
                        retention.writeStorage(writer, world);
                    }
                    response = writer.endObject().finish();
                }
            }
            HttpResponses.bytes(exchange, 200, "application/json; charset=utf-8", response.getBytes(StandardCharsets.UTF_8));
        } catch (WorldAccessException failure) {
            HttpResponses.error(exchange, failure.status(), failure.code(), failure.getMessage());
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException failure) {
            HttpResponses.error(exchange, 400, "INVALID_MANAGEMENT_REQUEST", "Invalid world management request");
        } catch (Exception failure) {
            HttpResponses.error(exchange, 503, "WORLD_MANAGEMENT_FAILED", "World operation requires retry or server recovery");
        }
    }

    static void writeTasks(JsonWriter writer, TransferJobs.Stats stats) {
        writer.beginObject().name("queued").value(stats.queued()).name("running").value(stats.running())
            .name("cached").value(stats.cached()).name("activeOperations").beginArray();
        for (String operation : stats.activeOperations()) {
            writer.value(operation);
        }
        writer.endArray().endObject();
    }

}
