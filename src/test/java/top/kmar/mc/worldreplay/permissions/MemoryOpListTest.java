package top.kmar.mc.worldreplay.permissions;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.ServerOpListEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryOpListTest {

    @TempDir
    Path directory;

    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "Operator");

    /** 连文件路径和存在性都不查询；继承的 add/remove 自动保存也不会触及文件。 */
    @Test
    void neverAccessesFileDuringConstructionLoadingSavingOrMutation() {
        File file = mock(File.class, invocation -> {
            throw new AssertionError("OP file accessed through " + invocation.getMethod().getName());
        });
        var ops = new MemoryOpList(file);
        var entry = new ServerOpListEntry(profile, 2, false);

        ops.load();
        assertTrue(ops.isEmpty());
        ops.add(entry);
        ops.save();
        ops.load();
        assertSame(entry, ops.get(profile));
        assertArrayEquals(new String[]{profile.getName()}, ops.getUserList());
        ops.remove(profile);
        assertNull(ops.get(profile));
        ops.add(entry);
        ops.remove(entry);
        assertTrue(ops.isEmpty());
        verifyNoInteractions(file);
    }

    /** 硬盘已有 OP 名单不被导入；内存权限的改变也不覆盖已有文件。 */
    @Test
    void preservesExistingDiskFileWithoutLoadingItsOperators() throws Exception {
        Path file = directory.resolve("ops.json");
        String original = "[{\"uuid\":\"" + UUID.randomUUID()
            + "\",\"name\":\"DiskOnly\",\"level\":4,\"bypassesPlayerLimit\":true}]";
        Files.writeString(file, original);
        byte[] bytes = Files.readAllBytes(file);
        var ops = new MemoryOpList(file.toFile());

        ops.load();
        assertTrue(ops.isEmpty());
        ops.add(new ServerOpListEntry(profile, 3, true));
        ops.save();
        ops.load();
        //noinspection DataFlowIssue
        assertEquals(3, ops.get(profile).getLevel());
        assertTrue(ops.canBypassPlayerLimit(profile));
        ops.remove(profile);
        ops.save();
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    /** 不创建 OP 文件或父目录；新服务器实例也不会继承旧实例的内存权限。 */
    @Test
    void neverCreatesFilesAndStartsEachInstanceEmpty() {
        Path parent = directory.resolve("missing");
        File file = parent.resolve("ops.json").toFile();
        var first = new MemoryOpList(file);
        first.load();
        first.add(new ServerOpListEntry(profile, 4, false));
        first.save();

        var next = new MemoryOpList(file);
        next.load();
        assertTrue(next.isEmpty());
        assertFalse(Files.exists(parent));
    }

}