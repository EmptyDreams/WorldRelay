package top.kmar.mc.worldreplay.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RsyncModeTest {

    /** 验证 Windows x64 的选项包含内置、自动查找和自定义，且内置排在默认位置。 */
    @Test
    void supportedPlatformOffersAllModes() {
        assertEquals(List.of(RsyncMode.BUNDLED, RsyncMode.AUTO, RsyncMode.CUSTOM), RsyncMode.availableModes(true));
    }

    /** 验证不支持内置工具的平台不暴露内置选项，默认提供自动查找，同时保留自定义入口。 */
    @Test
    void otherPlatformsHideBundledMode() {
        assertEquals(List.of(RsyncMode.AUTO, RsyncMode.CUSTOM), RsyncMode.availableModes(false));
    }

}