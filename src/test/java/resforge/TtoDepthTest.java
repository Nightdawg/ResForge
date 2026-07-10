package resforge;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.layers.CodeEntryInfo;
import resforge.layers.Mat2Codec;
import resforge.layers.RLinkInfo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtoDepthTest {
    private static final int MAX_DEPTH = 256;
    private static final int EXCESSIVE_DEPTH = 12_000;
    private static final int T_END = 0;
    private static final int T_STR = 2;
    private static final int T_LIST = 8;
    private static final int T_NIL = 12;
    private static final int T_MAP = 32;

    @Test
    void mat2RejectsExcessivelyDeepListsWithoutStackOverflow() {
        byte[] payload = mat2Payload(T_LIST, EXCESSIVE_DEPTH);

        assertThrows(RuntimeException.class, () -> Mat2Codec.decode(payload));
        assertNull(Mat2Codec.toJsonIfLossless(payload));
    }

    @Test
    void mat2RejectsExcessivelyDeepMapsWithoutStackOverflow() {
        byte[] payload = mat2Payload(T_MAP, EXCESSIVE_DEPTH);

        assertThrows(RuntimeException.class, () -> Mat2Codec.decode(payload));
        assertNull(Mat2Codec.toJsonIfLossless(payload));
    }

    @Test
    void codeEntryRejectsExcessivelyDeepListsWithoutStackOverflow() {
        assertFalse(CodeEntryInfo.parse(codeEntryPayload(T_LIST, EXCESSIVE_DEPTH)).recognized);
    }

    @Test
    void codeEntryRejectsExcessivelyDeepMapsWithoutStackOverflow() {
        assertFalse(CodeEntryInfo.parse(codeEntryPayload(T_MAP, EXCESSIVE_DEPTH)).recognized);
    }

    @Test
    void rlinkRejectsExcessivelyDeepListsWithoutStackOverflow() {
        assertFalse(RLinkInfo.parse(rlinkPayload(T_LIST, EXCESSIVE_DEPTH)).recognized);
    }

    @Test
    void rlinkRejectsExcessivelyDeepMapsWithoutStackOverflow() {
        assertFalse(RLinkInfo.parse(rlinkPayload(T_MAP, EXCESSIVE_DEPTH)).recognized);
    }

    @Test
    void mat2AcceptsListsAndMapsAtMaximumDepth() {
        Mat2Codec.decode(mat2Payload(T_LIST, MAX_DEPTH));
        Mat2Codec.decode(mat2Payload(T_MAP, MAX_DEPTH));
    }

    @Test
    void codeEntryAcceptsListsAndMapsAtMaximumDepth() {
        assertTrue(CodeEntryInfo.parse(codeEntryPayload(T_LIST, MAX_DEPTH)).recognized);
        assertTrue(CodeEntryInfo.parse(codeEntryPayload(T_MAP, MAX_DEPTH)).recognized);
    }

    @Test
    void rlinkAcceptsListsAndMapsAtMaximumDepth() {
        assertTrue(RLinkInfo.parse(rlinkPayload(T_LIST, MAX_DEPTH)).recognized);
        assertTrue(RLinkInfo.parse(rlinkPayload(T_MAP, MAX_DEPTH)).recognized);
    }

    private static byte[] mat2Payload(int containerTag, int depth) {
        MessageWriter out = new MessageWriter().uint16(1).string("test");
        nestedValue(out, containerTag, depth);
        out.uint8(T_END);
        return out.toByteArray();
    }

    private static byte[] codeEntryPayload(int containerTag, int depth) {
        MessageWriter out = new MessageWriter()
                .uint8(3)
                .string("entry")
                .string("example.Entry");
        nestedValue(out, containerTag, depth);
        out.uint8(T_END);
        out.string("").string("");
        return out.toByteArray();
    }

    private static byte[] rlinkPayload(int containerTag, int depth) {
        MessageWriter out = new MessageWriter()
                .uint8(3)
                .uint16(0)
                .uint8(3)
                .string("")
                .uint16(0);
        nestedValue(out, containerTag, depth);
        out.uint8(T_END);
        return out.toByteArray();
    }

    private static void nestedValue(MessageWriter out, int containerTag, int depth) {
        for(int i = 0; i < depth; i++) {
            out.uint8(containerTag);
            if(containerTag == T_MAP)
                out.uint8(T_STR).string("key");
        }
        out.uint8(T_NIL);
        for(int i = 0; i < depth; i++)
            out.uint8(T_END);
    }
}
