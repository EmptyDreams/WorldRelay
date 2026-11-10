package top.kmar.worldrelay.server.http;

import org.junit.jupiter.api.Test;
import top.kmar.worldrelay.server.json.JsonWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerStatusHandlerTest {

    /** 验证 JVM 与 Native Image 分别按各自的 Runtime 语义报告堆用量、容量和上限。 */
    @Test
    void reportsHeapAccordingToRuntimeSemantics() {
        var jvm = new JsonWriter();
        ServerStatusHandler.writeHeap(jvm, 64, 32, 512, false);
        assertEquals("{\"usedBytes\":32,\"committedBytes\":64,\"maxBytes\":512}", jvm.finish());

        var nativeImage = new JsonWriter();
        ServerStatusHandler.writeHeap(nativeImage, 512, 480, 512, true);
        assertEquals("{\"usedBytes\":32,\"committedBytes\":null,\"maxBytes\":512}", nativeImage.finish());
    }

}