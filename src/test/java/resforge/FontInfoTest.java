package resforge;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import resforge.layers.FontInfo;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FontInfoTest {
    record Case(String name, byte[] payload, boolean recognized, int version, int type,
                String format, int offset) {
    }

    @TestFactory
    Stream<DynamicTest> parsesSupportedAndRejectedForms() {
        List<Case> cases = List.of(
                valid("sfnt TrueType", new byte[]{0x00, 0x01, 0x00, 0x00}, "ttf"),
                valid("OpenType CFF", new byte[]{'O', 'T', 'T', 'O'}, "otf"),
                valid("TrueType collection", new byte[]{'t', 't', 'c', 'f'}, "ttc"),
                valid("legacy TrueType", new byte[]{'t', 'r', 'u', 'e'}, "ttf"),
                new Case("empty", new byte[0], false, 0, 0, null, -1),
                new Case("truncated header", new byte[]{1}, false, 1, 0, null, -1),
                new Case("wrong version", payload(2, 0, 'O', 'T', 'T', 'O'),
                        false, 2, 0, null, -1),
                new Case("wrong type", payload(1, 3, 'O', 'T', 'T', 'O'),
                        false, 1, 3, null, -1),
                new Case("truncated signature", payload(1, 0, 0, 1, 0),
                        true, 1, 0, null, -1),
                new Case("wrong signature", payload(1, 0, 'B', 'A', 'D', '!'),
                        true, 1, 0, null, -1)
        );

        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name, () -> {
            FontInfo info = FontInfo.parse(c.payload);
            assertEquals(c.recognized, info.recognized);
            assertEquals(c.version, info.ver);
            assertEquals(c.type, info.type);
            assertEquals(c.format, info.format);
            assertEquals(c.offset, info.fontOffset);
        }));
    }

    private static Case valid(String name, byte[] signature, String format) {
        return new Case(name, payload(1, 0, signature), true, 1, 0, format, 2);
    }

    private static byte[] payload(int version, int type, int... tail) {
        byte[] payload = new byte[2 + tail.length];
        payload[0] = (byte) version;
        payload[1] = (byte) type;
        for(int i = 0; i < tail.length; i++)
            payload[i + 2] = (byte) tail[i];
        return payload;
    }

    private static byte[] payload(int version, int type, byte[] tail) {
        int[] ints = new int[tail.length];
        for(int i = 0; i < tail.length; i++)
            ints[i] = tail[i];
        return payload(version, type, ints);
    }
}
