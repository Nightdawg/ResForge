package resforge;

import resforge.io.MessageWriter;
import resforge.layers.TexInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests that TexInfo locates the optional alpha mask (tag 4) as well as the
 *  color image (tag 0), without disturbing the color split. */
class TexMaskTest {
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    /** id, off, sz, color image (tag 0), a filter (tag 1), then a mask (tag 4) —
     *  the layout real foliage textures (e.g. mulberry) use. */
    private static byte[] texColorFilterMask() {
        MessageWriter w = new MessageWriter();
        w.int16(1).uint16(0).uint16(0).uint16(256).uint16(256);
        w.uint8(0).int32(JPG.length).bytes(JPG);     // color (JPEG)
        w.uint8(1).uint8(4);                          // filter (tag 1, value 4)
        w.uint8(4).int32(PNG.length).bytes(PNG);     // mask (PNG)
        return w.toByteArray();
    }

    private static byte[] texColorOnly() {
        MessageWriter w = new MessageWriter();
        w.int16(1).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(JPG.length).bytes(JPG);
        return w.toByteArray();
    }

    @Test
    void locatesBothColorAndMask() {
        byte[] payload = texColorFilterMask();
        TexInfo ti = TexInfo.parse(payload);
        assertTrue(ti.found, "color image found");
        assertEquals("jpg", ti.imageFormat);
        assertArrayEquals(JPG, Arrays.copyOfRange(payload, ti.imageOffset, ti.imageOffset + ti.imageLen));

        assertTrue(ti.maskFound, "alpha mask found");
        assertEquals("png", ti.maskFormat);
        assertArrayEquals(PNG, Arrays.copyOfRange(payload, ti.maskOffset, ti.maskOffset + ti.maskLen));
        // The mask must come after the color image.
        assertTrue(ti.maskOffset > ti.imageOffset + ti.imageLen);
    }

    @Test
    void colorOnlyTextureHasNoMask() {
        TexInfo ti = TexInfo.parse(texColorOnly());
        assertTrue(ti.found);
        assertFalse(ti.maskFound, "no tag-4 part means no mask");
        assertEquals(-1, ti.maskOffset);
    }

    @Test
    void colorSplitIsUnchangedByMaskScanning() {
        // The color image's offset/length must be identical whether or not a mask follows.
        TexInfo withMask = TexInfo.parse(texColorFilterMask());
        TexInfo colorOnly = TexInfo.parse(texColorOnly());
        assertEquals(colorOnly.imageLen, withMask.imageLen);
        assertEquals(JPG.length, withMask.imageLen);
        assertTrue(withMask.found && colorOnly.found);
    }
}
