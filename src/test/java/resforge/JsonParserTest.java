package resforge;

import resforge.io.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The hand-rolled JSON parser must fail cleanly on hostile input, not throw raw bounds errors. */
class JsonParserTest {

    @Test
    void truncatedUnicodeEscapeIsRejectedCleanly() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"\\u12\""));
    }

    @Test
    void unterminatedBackslashIsRejectedCleanly() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"abc\\"));
    }

    @Test
    void duplicateObjectKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void validJsonStillParses() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\":1,\"b\":\"x\\u0041y\"}");
        assertEquals(1L, m.get("a"));
        assertEquals("xAy", m.get("b"));
    }
}
