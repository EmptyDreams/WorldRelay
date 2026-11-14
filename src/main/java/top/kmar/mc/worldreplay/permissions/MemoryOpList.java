package top.kmar.mc.worldreplay.permissions;

import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import com.mojang.authlib.GameProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.kmar.worldrelay.permissions.OperatorPolicy;

import java.io.File;
import java.util.Map;

/** 共享世界的运行时 OP 列表；保留原版条目和增删行为，禁止从本机文件加载或保存。 */
public final class MemoryOpList extends ServerOpList {
    private Map<String, Integer> policy;
    private long revision = -1;

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

    /** 仅在服务器线程调用；全量替换使撤销权限同时清除已经解析的 UUID 条目。 */
    public boolean apply(OperatorPolicy next) {
        if (next.revision() <= revision) return false;
        getEntries().clear();
        policy = next.operators();
        revision = next.revision();
        return true;
    }

    @Override
    public @Nullable ServerOpListEntry get(@NotNull GameProfile profile) {
        if (policy == null) return super.get(profile);
        Integer level = policy.get(profile.getName());
        if (level == null) return null;
        var entry = super.get(profile);
        if (entry == null || entry.getLevel() != level || entry.getBypassesPlayerLimit()) {
            entry = new ServerOpListEntry(profile, level, false);
            super.add(entry);
        }
        return entry;
    }

    @Override
    public void add(@NotNull ServerOpListEntry entry) {
        // 中心配置接入后，本机 op 或其他普通增删调用不能扩大名单。
        if (policy == null) super.add(entry);
    }

    @Override
    protected boolean contains(@NotNull GameProfile profile) {
        // 原版白名单等调用也必须使用同一份中心权限，而非未解析的 UUID 缓存。
        return get(profile) != null;
    }

    @Override
    public String @NotNull [] getUserList() {
        return policy == null ? super.getUserList() : policy.keySet().stream().sorted().toArray(String[]::new);
    }

    @Override
    public boolean isEmpty() {
        return policy == null ? super.isEmpty() : policy.isEmpty();
    }

}
