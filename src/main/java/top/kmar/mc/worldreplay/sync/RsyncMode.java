package top.kmar.mc.worldreplay.sync;

import java.util.List;

/** 工具来源独立于路径配置，不再通过空路径推断用户的选择。 */
public enum RsyncMode {
    BUNDLED,
    AUTO,
    CUSTOM;

    public static List<RsyncMode> availableModes() {
        return availableModes(!BundledRsync.isNotSupportedPlatform());
    }

    static List<RsyncMode> availableModes(boolean bundledSupported) {
        return bundledSupported ? List.of(BUNDLED, AUTO, CUSTOM) : List.of(AUTO, CUSTOM);
    }

    public static RsyncMode defaultMode() {
        return availableModes().getFirst();
    }

}
