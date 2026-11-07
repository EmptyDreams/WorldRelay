package top.kmar.mc.worldreplay.compat.create;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.ServerSchematicLoader;
import com.simibubi.create.content.schematics.table.SchematicTableBlockEntity;
import com.simibubi.create.foundation.utility.CreatePaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;
import top.kmar.worldrelay.snapshot.SnapshotFiles;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 执行真实 Create 发布类和可选 Mixin，只替换 Minecraft 世界与玩家的运行环境。 */
class CreateSchematicTest {

    @TempDir
    Path temporary;

    @BeforeAll
    static void loadServerConfig() {
        // forgejunitdev 不启动服务器；为真实上传限额提供默认内存配置。
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    private ServerLevel world(String name, boolean shared) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name));
        Files.writeString(root.resolve("level.dat"), "snapshot fixture");
        if (shared) Files.createFile(root.resolve(SharedPlayerData.MARKER_FILE));
        return world(root);
    }

    private ServerLevel world(Path root) {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(root);
        ServerLevel world = mock(ServerLevel.class);
        when(world.getServer()).thenReturn(server);
        when(world.holderLookup(Registries.BLOCK)).thenReturn(BuiltInRegistries.BLOCK.asLookup());
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.STONE.defaultBlockState());
        when(world.getEntitiesOfClass(any(), any(AABB.class), any())).thenReturn(List.of());
        return world;
    }

    private static ServerPlayer player(ServerLevel world, String name) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getGameProfile()).thenReturn(new GameProfile(UUID.randomUUID(), name));
        when(player.level()).thenReturn(world);
        when(player.getCommandSenderWorld()).thenReturn(world);
        return player;
    }

    private static ServerSchematicLoader loader() {
        SchematicTableBlockEntity table = mock(SchematicTableBlockEntity.class);
        return new ServerSchematicLoader() {
            @Override
            public SchematicTableBlockEntity getTable(net.minecraft.world.level.Level world, BlockPos pos) {
                return table;
            }
        };
    }

    private static ItemStack blueprint(String player, String file) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:schematic")));
        stack.set(AllDataComponents.SCHEMATIC_OWNER, player);
        stack.set(AllDataComponents.SCHEMATIC_FILE, file);
        return stack;
    }

    private static Path uploaded(ServerLevel world, String player, String file) {
        return CreateSchematicPaths.uploaded(CreatePaths.UPLOADED_SCHEMATICS_DIR, world).resolve(player).resolve(file);
    }

    private static byte[] schematic(ServerLevel world, int width) throws Exception {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(world, BlockPos.ZERO, new BlockPos(width, 1, 1), false, Blocks.AIR);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(template.save(new CompoundTag()), bytes);
        return bytes.toByteArray();
    }

    private static void upload(ServerSchematicLoader loader, ServerPlayer player, String file, byte[] bytes) {
        loader.handleNewUpload(player, file, bytes.length, BlockPos.ZERO);
        loader.handleWriteRequest(player, file, bytes);
        loader.handleFinishedUpload(player, file);
    }

    /** 普通上传按世界和玩家隔离；完整快照交给新房主后，原蓝图物品仍能读取同一结构。 */
    @Test
    void uploadedSchematicsSurviveSnapshotHandoffAndKeepWorldAndPlayerIsolation() throws Exception {
        ServerLevel first = world("first", true);
        ServerLevel second = world("second", true);
        ServerSchematicLoader loader = loader();
        upload(loader, player(first, "Alice"), "house.nbt", schematic(first, 2));
        upload(loader, player(first, "Bob"), "house.nbt", schematic(first, 3));
        upload(loader, player(second, "Alice"), "house.nbt", schematic(second, 4));
        loader.shutdown();
        assertEquals(new BlockPos(2, 1, 1), SchematicItem.loadSchematic(first, blueprint("Alice", "house.nbt")).getSize());
        assertEquals(new BlockPos(3, 1, 1), SchematicItem.loadSchematic(first, blueprint("Bob", "house.nbt")).getSize());
        assertEquals(new BlockPos(4, 1, 1), SchematicItem.loadSchematic(second, blueprint("Alice", "house.nbt")).getSize());

        Path firstRoot = first.getServer().getWorldPath(LevelResource.ROOT);
        var manifest = SnapshotFiles.scan(firstRoot, "a".repeat(64));
        Path nextHost = temporary.resolve("next-host");
        SnapshotFiles.copyWorld(firstRoot, nextHost);
        SnapshotFiles.verify(nextHost, manifest);
        assertTrue(manifest.entries().stream().anyMatch(entry -> entry.path().equals("worldrelay/create/schematics/uploaded/Alice/house.nbt")));
        assertEquals(new BlockPos(2, 1, 1), SchematicItem.loadSchematic(world(nextHost), blueprint("Alice", "house.nbt")).getSize());
    }

    /** 即时蓝图由服务端导出到世界目录，玩家自己保存的同名个人蓝图不被覆盖。 */
    @Test
    void instantSchematicExportsIntoWorldWithoutOverwritingPersonalLibrary() throws Exception {
        ServerLevel world = world("instant", true);
        ServerPlayer player = player(world, "Alice");
        when(player.getMainHandItem()).thenReturn(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:schematic_and_quill"))));
        String file = "worldrelay-test-" + UUID.randomUUID() + ".nbt";
        Path personal = CreatePaths.SCHEMATICS_DIR.resolve(file);
        Files.createDirectories(personal.getParent());
        Files.writeString(personal, "personal blueprint");
        try {
            loader().handleInstantSchematic(player, file, world, BlockPos.ZERO, new BlockPos(2, 1, 1));
            assertTrue(Files.isRegularFile(uploaded(world, "Alice", file)));
            assertEquals(new BlockPos(2, 1, 1), SchematicItem.loadSchematic(world, blueprint("Alice", file)).getSize());
            verify(player).setItemInHand(eq(InteractionHand.MAIN_HAND), any(ItemStack.class));
            assertEquals("personal blueprint", Files.readString(personal));
        } finally {
            Files.deleteIfExists(personal);
        }
    }

    /** 停服取消未完成上传时删除原世界的半成品，保留其他世界已完成的同名蓝图。 */
    @Test
    void shutdownRemovesPartialUploadFromItsOwningWorld() throws Exception {
        ServerLevel first = world("partial", true);
        ServerLevel second = world("completed", true);
        ServerSchematicLoader complete = loader();
        upload(complete, player(second, "Alice"), "house.nbt", schematic(second, 3));
        ServerSchematicLoader partial = loader();
        ServerPlayer player = player(first, "Alice");
        partial.handleNewUpload(player, "house.nbt", 100, BlockPos.ZERO);
        partial.handleWriteRequest(player, "house.nbt", new byte[]{1, 2});
        assertTrue(Files.exists(uploaded(first, "Alice", "house.nbt")));
        partial.shutdown();
        assertFalse(Files.exists(uploaded(first, "Alice", "house.nbt")));
        assertEquals(new BlockPos(3, 1, 1), SchematicItem.loadSchematic(second, blueprint("Alice", "house.nbt")).getSize());
    }

    /** 普通存档沿用 Create 路径；共享标记损坏时明确失败，避免写入全局蓝图目录。 */
    @Test
    void respectsOrdinaryWorldsAndRefusesBrokenSharedMarker() throws Exception {
        ServerLevel ordinary = world("ordinary", false);
        assertEquals(CreatePaths.UPLOADED_SCHEMATICS_DIR, CreateSchematicPaths.uploaded(CreatePaths.UPLOADED_SCHEMATICS_DIR, ordinary));
        Files.createDirectory(ordinary.getServer().getWorldPath(LevelResource.ROOT).resolve(SharedPlayerData.MARKER_FILE));
        assertThrows(UncheckedIOException.class, () -> CreateSchematicPaths.uploaded(CreatePaths.UPLOADED_SCHEMATICS_DIR, ordinary));
    }

}
