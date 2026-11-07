package top.kmar.mc.worldreplay.compat.create;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import top.kmar.mc.worldreplay.playerdata.SharedPlayerData;

import java.nio.file.Path;

/** 只改变共享世界的服务端副本；个人蓝图库继续由 Create 自己管理。 */
public final class CreateSchematicPaths {

    private CreateSchematicPaths() {
    }

    public static Path uploaded(Path original, Level level) {
        if (level.isClientSide() || level.getServer() == null) return original;
        Path world = level.getServer().getWorldPath(LevelResource.ROOT);
        // 与玩家数据使用同一个受管标记；检查失败时不能回退到全局目录写入。
        return new SharedPlayerData(world).isSharedWorld()
            ? world.resolve("worldrelay/create/schematics/uploaded").toAbsolutePath().normalize()
            : original;
    }

}
