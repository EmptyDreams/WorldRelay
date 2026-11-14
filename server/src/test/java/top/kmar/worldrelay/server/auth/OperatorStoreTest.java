package top.kmar.worldrelay.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.kmar.worldrelay.server.world.WorldAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperatorStoreTest {
    @TempDir Path root;

    @Test
    void persistsGlobalLevelsAndRejectsConcurrentStaleEdits() throws Exception {
        Path file = root.resolve("operators.json");
        try (var store = OperatorStore.open(file)) {
            assertEquals(0, store.snapshot().revision());
            assertEquals(Map.of("Dev", 2), store.update(0, "Dev", 2).operators());
            assertEquals(1, store.update(1, "Dev", 2).revision());
            assertEquals("OPERATOR_REVISION_MISMATCH",
                assertThrows(WorldAccessException.class, () -> store.update(0, "Guest", 4)).code());
            assertEquals(Map.of("Dev", 2), store.snapshot().operators());
            assertThrows(IllegalArgumentException.class, () -> store.update(1, "Dev", 5));
        }
        try (var store = OperatorStore.open(file)) {
            assertEquals(1, store.snapshot().revision());
            assertEquals(Map.of("Dev", 2), store.snapshot().operators());
            assertTrue(store.update(1, "Dev", 0).operators().isEmpty());
        }
        try (var store = OperatorStore.open(file)) {
            assertEquals(2, store.snapshot().revision());
            assertTrue(store.snapshot().operators().isEmpty());
        }
    }

    @Test
    void failedPublicationDoesNotAdvanceInMemoryPolicy() throws Exception {
        Path file = root.resolve("operators.json");
        try (var store = OperatorStore.open(file)) {
            Files.createDirectory(file);
            assertThrows(IOException.class, () -> store.update(0, "Dev", 2));
            assertEquals(0, store.snapshot().revision());
            assertTrue(store.snapshot().operators().isEmpty());
        }
    }

    @Test
    void rejectsCorruptionAndConcurrentStoreInstances() throws Exception {
        Path file = root.resolve("operators.json");
        try (var ignored = OperatorStore.open(file)) {
            assertThrows(IOException.class, () -> {
                try (var duplicate = OperatorStore.open(file)) {
                    fail("Unexpectedly opened a second store: " + duplicate.snapshot());
                }
            });
        }
        Files.writeString(file, "{\"formatVersion\":1,\"revision\":1,\"operators\":[{\"username\":\"Dev\",\"level\":9}]}");
        assertThrows(IOException.class, () -> {
            try (var corrupt = OperatorStore.open(file)) {
                fail("Unexpectedly opened a corrupt store: " + corrupt.snapshot());
            }
        });
        assertTrue(Files.readString(file).contains("\"level\":9"));
    }
}
