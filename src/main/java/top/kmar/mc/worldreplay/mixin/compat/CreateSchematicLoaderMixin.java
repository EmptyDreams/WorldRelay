package top.kmar.mc.worldreplay.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.schematics.ServerSchematicLoader.SchematicUploadEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import top.kmar.mc.worldreplay.compat.create.CreateSchematicPaths;

import java.nio.file.Path;

/** 保留 Create 的上传校验和玩家目录，只替换服务端蓝图根路径。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.schematics.ServerSchematicLoader", remap = false)
public abstract class CreateSchematicLoaderMixin {

    @ModifyExpressionValue(method = "handleNewUpload", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lcom/simibubi/create/foundation/utility/CreatePaths;UPLOADED_SCHEMATICS_DIR:Ljava/nio/file/Path;"))
    private Path worldrelay$uploadDirectory(Path original, @Local(argsOnly = true) ServerPlayer player) {
        return CreateSchematicPaths.uploaded(original, player.level());
    }

    @ModifyExpressionValue(method = "handleInstantSchematic", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lcom/simibubi/create/foundation/utility/CreatePaths;UPLOADED_SCHEMATICS_DIR:Ljava/nio/file/Path;"))
    private Path worldrelay$instantDirectory(Path original, @Local(argsOnly = true) Level world) {
        return CreateSchematicPaths.uploaded(original, world);
    }

    @ModifyExpressionValue(method = "cancelUpload", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lcom/simibubi/create/foundation/utility/CreatePaths;UPLOADED_SCHEMATICS_DIR:Ljava/nio/file/Path;"))
    @SuppressWarnings("LocalMayUseName") // 按唯一类型捕获，避免依赖 Create 发布构建的局部变量名。
    private Path worldrelay$cancelDirectory(Path original, @Local SchematicUploadEntry entry) {
        // 超时和停服清理使用上传记录的世界，不能取客户端此刻的活动世界。
        return CreateSchematicPaths.uploaded(original, entry.world);
    }

}
