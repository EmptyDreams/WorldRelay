package top.kmar.mc.worldreplay.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import top.kmar.mc.worldreplay.compat.create.CreateSchematicPaths;

import java.nio.file.Path;

/** 蓝图物品和蓝图加农炮从当前世界读取已上传副本；客户端预览路径不变。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.schematics.SchematicItem", remap = false)
public abstract class CreateSchematicItemMixin {

    @ModifyExpressionValue(method = "loadSchematic", at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lcom/simibubi/create/foundation/utility/CreatePaths;UPLOADED_SCHEMATICS_DIR:Ljava/nio/file/Path;"))
    private static Path worldrelay$readDirectory(Path original, @Local(argsOnly = true) Level world) {
        return CreateSchematicPaths.uploaded(original, world);
    }

}
