package resforge;

import org.junit.jupiter.api.Test;
import resforge.vbuf.Vbuf2Format;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Vbuf2FormatTest {
    @Test
    void bareAndFormattedAttributesShareElementCounts() {
        assertElements("pos", 3);
        assertElements("nrm", 3);
        assertElements("col", 4);
        assertElements("tex", 2);
        assertElements("tan", 3);
        assertElements("bit", 3);
        assertElements("otex", 2);
    }

    @Test
    void variableOrUnknownAttributesHaveNoFixedElementCount() {
        assertEquals(-1, Vbuf2Format.elements("bones"));
        assertEquals(-1, Vbuf2Format.elements("bones2"));
        assertEquals(-1, Vbuf2Format.elements("unknown"));
    }

    private static void assertElements(String name, int expected) {
        assertEquals(expected, Vbuf2Format.elements(name));
        assertEquals(expected, Vbuf2Format.elements(name + "2"));
    }
}
