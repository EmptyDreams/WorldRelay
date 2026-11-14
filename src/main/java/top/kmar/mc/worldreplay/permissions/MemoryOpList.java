package top.kmar.mc.worldreplay.permissions;

import net.minecraft.server.players.ServerOpList;

import java.io.File;

/** 共享世界的运行时 OP 列表；保留原版条目和增删行为，禁止从本机文件加载或保存。 */
public final class MemoryOpList extends ServerOpList {

    public MemoryOpList(File unusedFile) {
        // 原版构造器只保留 File 引用，不检查或打开文件。
        super(unusedFile);
    }

    @Override
    public void load() {
        // 本机 OP 文件不是共享世界的权限来源。
    }

    @Override
    public void save() {
        // add/remove 会调用此方法，但共享世界的名单只保留在内存中。
    }

}
