package top.kmar.worldrelay.server.world;

import top.kmar.worldrelay.snapshot.SnapshotFiles;
import top.kmar.worldrelay.snapshot.SnapshotManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

record BackupTestWorld(Path root, String world, String first, String second) {

    static String id() {
        return UUID.randomUUID().toString();
    }

    static BackupTestWorld create(Path root) throws Exception {
        try (var store = WorldStore.open(root)) {
            var created = store.create("管理恢复测试");
            assertNotNull(created);
            String first = save(store, created.worldId(), "first-world");
            String second = save(store, created.worldId(), "second-world");
            return new BackupTestWorld(root, created.worldId(), first, second);
        }
    }

    static WorldStore.Identity acquire(WorldStore store, String world) throws Exception {
        String device = id();
        var view = store.acquire(world, "Dev", device, id()).view();
        return new WorldStore.Identity("Dev", device, view.sessionId(), view.epoch());
    }

    static String save(WorldStore store, String world, String data) throws Exception {
        var owner = acquire(store, world);
        Long base = store.heartbeat(world, owner, null).baseVersion();
        store.stop(world, owner, WorldStore.StopStage.BEGIN, base);
        store.prepareTransfer(world, owner, base, true);
        Path candidate = store.candidateDirectory(world, owner).resolve("payload");
        Files.createDirectories(candidate.resolve("world/mod-data/empty"));
        Files.writeString(candidate.resolve("world/level.dat"), data);
        Files.writeString(candidate.resolve("world/mod-data/train.dat"), data + "-train");
        byte[] manifest = SnapshotFiles.scan(candidate.resolve("world")).encode();
        Files.write(candidate.resolve("manifest.bin"), manifest);
        String upload = id();
        store.publishSnapshot(world, owner, base, upload, SnapshotManifest.hash(manifest), candidate);
        return upload;
    }

    Path directory() {
        return root.resolve(world);
    }

    Path snapshot(String upload) {
        return directory().resolve("snapshots").resolve(upload);
    }

    Path metadata() {
        return directory().resolve("world.json");
    }

}