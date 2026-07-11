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

    @Test
    void nonconformantNumbersAreRejected() {
        for(String json : new String[]{"+5", "01", "-01", "1.", "1e", "1e+", "--1", "1+2"})
            assertThrows(IllegalArgumentException.class, () -> Json.parse(json), json);
    }

    @Test
    void conformantNumbersStillParse() {
        assertEquals(0L, Json.parse("0"));
        assertEquals(-12L, Json.parse("-12"));
        assertEquals(0.25, Json.parse("0.25"));
        assertEquals(1000.0, Json.parse("1e+3"));
    }

    @Test
    void pairedUnicodeSurrogatesAreAccepted() {
        assertEquals("\ud83d\ude00", Json.parse("\"\\uD83D\\uDE00\""));
        assertEquals("\ud83d\ude00", Json.parse("\"\ud83d\ude00\""));
    }

    @Test
    void loneUnicodeSurrogatesAreRejected() {
        for(String json : new String[]{
                "\"\\uD83D\"", "\"\\uDE00\"", "\"\\uD83D\\u0041\"",
                "\"\uD83D\"", "\"\uDE00\""
        })
            assertThrows(IllegalArgumentException.class, () -> Json.parse(json));
    }

    @Test
    void unescapedControlCharactersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\nb\""));
        assertEquals("a\nb", Json.parse("\"a\\nb\""));
    }

    @Test
    void writerRejectsNonFiniteNumbersAndLoneSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Json.write("\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> Json.write("\uDE00"));
        assertEquals("\"\ud83d\ude00\"\n", Json.write("\ud83d\ude00"));
    }

    @Test
    void pathologicallyDeepNestingIsRejectedCleanly() {
        // A few thousand levels would StackOverflowError without the depth guard; that
        // is an Error, so it would slip past the codecs' catch(RuntimeException) guards.
        // It must fail with a clear IllegalArgumentException instead.
        String deepArray = "[".repeat(5000) + "]".repeat(5000);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deepArray));
        String deepObject = "{\"a\":".repeat(5000) + "1" + "}".repeat(5000);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deepObject));
    }

    @Test
    void modestNestingStillParses() {
        // Well within the cap: real layers never nest more than a level or two.
        String nested = "[".repeat(64) + "1" + "]".repeat(64);
        assertEquals(64, depthOf(Json.parse(nested)));
    }

    private static int depthOf(Object o) {
        int d = 0;
        while(o instanceof java.util.List<?> l && !l.isEmpty()) {
            d++;
            o = l.get(0);
        }
        return d;
    }
}
