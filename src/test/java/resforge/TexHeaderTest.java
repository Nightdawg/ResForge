package resforge;

import resforge.io.MessageWriter;
import resforge.layers.TexHeaderCodec;
import resforge.layers.TexInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexHeaderTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};

    /** A tex layer: id, off, sz, then a t==0 color-image part (tag, int32 len, image). */
    private static byte[] tex(int id, int ox, int oy, int sx, int sy) {
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(ox).uint16(oy).uint16(sx).uint16(sy);
        w.uint8(0);                 // part tag: fl=0, t=0 (color image)
        w.int32(PNG.length);
        w.bytes(PNG);
        return w.toByteArray();
    }

    @Test
    void parsesAndReEncodesByteIdentical() {
        byte[] payload = tex(0, 0, 0, 256, 512);
        TexHeaderCodec h = TexHeaderCodec.parse(payload);
        assertTrue(h.editable);
        assertEquals(0, h.id);
        assertEquals(256, h.szX);
        assertEquals(512, h.szY);
        assertArrayEquals(payload, h.encode());
    }

    @Test
    void editingFieldsKeepsPartsAndImage() {
        byte[] payload = tex(0, 0, 0, 256, 512);
        TexHeaderCodec h = TexHeaderCodec.parse(payload);
        byte[] edited = h.encodeWith(7, 16, 32, 128, 64);

        TexInfo ti = TexInfo.parse(edited);
        assertTrue(ti.found);
        assertEquals(7, ti.id);
        assertEquals(16, ti.offX);
        assertEquals(128, ti.szX);
        assertEquals(64, ti.szY);
        // The embedded image part must survive unchanged.
        byte[] img = Arrays.copyOfRange(edited, ti.imageOffset, ti.imageOffset + ti.imageLen);
        assertArrayEquals(PNG, img);
    }

    @Test
    void supportsUint16SizesAboveInt16() {
        byte[] payload = tex(0, 0, 0, 100, 100);
        TexHeaderCodec h = TexHeaderCodec.parse(payload);
        byte[] edited = h.encodeWith(0, 40000, 50000, 60000, 65535);   // beyond int16 range
        TexInfo ti = TexInfo.parse(edited);
        assertEquals(40000, ti.offX);
        assertEquals(65535, ti.szY);
    }

    @Test
    void rejectsOutOfRange() {
        TexHeaderCodec h = TexHeaderCodec.parse(tex(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> h.encodeWith(0, -1, 0, 1, 1));    // off can't be negative
        assertThrows(IllegalArgumentException.class, () -> h.encodeWith(70000, 0, 0, 1, 1)); // id out of int16
    }
}
