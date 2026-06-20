package resforge;

import resforge.io.MessageWriter;
import resforge.layers.ImageHeaderCodec;
import resforge.layers.ImageInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageHeaderTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    /** A real-shape old-style image header: z=0, subz=100, fl=0, id=131, off=(20,24). */
    private static byte[] header(int z, int subz, int fl, int id, int ox, int oy) {
        MessageWriter w = new MessageWriter();
        w.uint8(z & 0xff);          // ver = low byte of z
        w.int8(z >> 8);             // z high
        w.int16(subz);
        w.uint8(fl);
        w.int16(id);
        w.int16(ox).int16(oy);
        w.bytes(PNG);
        return w.toByteArray();
    }

    @Test
    void parsesAndReEncodesByteIdentical() {
        byte[] payload = header(0, 100, 0, 131, 20, 24);
        ImageHeaderCodec h = ImageHeaderCodec.parse(payload);
        assertTrue(h.editable);
        assertEquals(0, h.z);
        assertEquals(100, h.subz);
        assertEquals(131, h.id);
        assertEquals(20, h.offX);
        assertEquals(24, h.offY);
        assertFalse(h.nooff);
        assertArrayEquals(payload, h.encode());
    }

    @Test
    void editingFieldsKeepsImageAndIsParseable() {
        byte[] payload = header(0, 100, 0, 131, 20, 24);
        ImageHeaderCodec h = ImageHeaderCodec.parse(payload);
        byte[] edited = h.encodeWith(0, 100, 200, -5, 7, true);

        // The embedded image must survive unchanged.
        ImageInfo ii = ImageInfo.parse(edited);
        assertTrue(ii.recognized);
        assertEquals(200, ii.id);
        assertEquals(-5, ii.offX);
        assertEquals(7, ii.offY);
        assertTrue(ii.nooff);
        byte[] img = Arrays.copyOfRange(edited, ii.imageOffset, edited.length);
        assertArrayEquals(PNG, img);

        // Round-trips through the codec again.
        ImageHeaderCodec h2 = ImageHeaderCodec.parse(edited);
        assertTrue(h2.editable);
        assertEquals(200, h2.id);
        assertTrue(h2.nooff);
    }

    @Test
    void preservesInfoBlockVerbatim() {
        // fl bit3 set => an info block of one entry "tsz" then a terminator.
        MessageWriter w = new MessageWriter();
        w.uint8(0).int8(0).int16(0).uint8(4).int16(7).int16(0).int16(0);  // z,subz,fl=4(has-info),id=7,off
        w.string("tsz").uint8(8).int16(32).int16(0).int16(0).int16(0);    // entry: key, len=8, 8 bytes (coord)
        w.string("");                                                     // info terminator
        w.bytes(PNG);
        byte[] payload = w.toByteArray();

        ImageHeaderCodec h = ImageHeaderCodec.parse(payload);
        assertTrue(h.editable, "header with an info block must still round-trip byte-exact");
        assertEquals(7, h.id);
        // Change only the id; the info block + image stay verbatim.
        byte[] edited = h.encodeWith(h.z, h.subz, 9, h.offX, h.offY, h.nooff);
        assertEquals(9, ImageInfo.parse(edited).id);
        assertEquals(payload.length, edited.length);
    }

    @Test
    void buildWrapsImageIntoValidHeader() {
        byte[] payload = ImageHeaderCodec.build(150, 0, 0, false, PNG);
        ImageInfo ii = ImageInfo.parse(payload);
        assertTrue(ii.recognized);
        assertEquals(150, ii.id);
        assertEquals("png", ii.imageFormat);
        assertArrayEquals(PNG, Arrays.copyOfRange(payload, ii.imageOffset, payload.length));
    }

    @Test
    void newStyleHeaderIsNotEditable() {
        MessageWriter w = new MessageWriter();
        w.uint8(128 + 1);   // new-style (typed) header
        w.int16(5);
        w.string("");       // empty tto info
        w.bytes(PNG);
        assertFalse(ImageHeaderCodec.parse(w.toByteArray()).editable);
    }

    @Test
    void rejectsOutOfRangeZ() {
        ImageHeaderCodec h = ImageHeaderCodec.parse(header(0, 0, 0, 1, 0, 0));
        // z low byte 200 (>=128) would be misread as a new-style header.
        assertThrows(IllegalArgumentException.class, () -> h.encodeWith(200, 0, 1, 0, 0, false));
    }
}
