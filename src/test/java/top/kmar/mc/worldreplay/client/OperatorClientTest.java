package top.kmar.mc.worldreplay.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OperatorClientTest {
    private final LocalWorldSession session = new LocalWorldSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, LocalWorldSession.Stage.STARTED, "world");

    private String response(String extra) {
        return "{\"worldId\":\"" + session.worldId() + "\",\"sessionId\":\"" + session.sessionId()
            + "\",\"epoch\":3," + extra + "}";
    }

    @Test
    void readsInitialPolicyAndAcknowledgesOnlyAppliedVersions() throws Exception {
        var policy = OperatorClient.parse(response("\"revision\":4,\"unchanged\":false,\"operators\":[{\"username\":\"Dev\",\"level\":2}]"), session, -1);
        assertNotNull(policy);
        assertEquals(4, policy.revision());
        assertEquals(Map.of("Dev", 2), policy.operators());
        assertNull(OperatorClient.parse(response("\"revision\":4,\"unchanged\":true"), session, 4));
        assertThrows(IOException.class, () -> OperatorClient.parse(response("\"revision\":4,\"unchanged\":true"), session, -1));
    }

    @Test
    void rejectsWrongSessionStalePolicyAndInvalidLevels() {
        String valid = response("\"revision\":4,\"unchanged\":false,\"operators\":[{\"username\":\"Dev\",\"level\":2}]");
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace(session.sessionId(), UUID.randomUUID().toString()), session, -1));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace("\"epoch\":3", "\"epoch\":2"), session, -1));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid, session, 5));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid, session, 4));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace("\"epoch\":3", "\"epoch\":\"3\""), session, -1));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace("\"level\":2", "\"level\":5"), session, -1));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace("\"level\":2", "\"level\":\"2\""), session, -1));
        assertThrows(IOException.class, () -> OperatorClient.parse(valid.replace("[{\"username\":\"Dev\",\"level\":2}]",
            "[{\"username\":\"Dev\",\"level\":2},{\"username\":\"Dev\",\"level\":4}]"), session, -1));
    }
}
