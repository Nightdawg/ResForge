package resforge;

import resforge.io.MessageWriter;
import resforge.model.LocalTextures;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the local-texture / matid mapping used by the textured 3D viewer. */
class LocalTexturesTest {

    /** A tex layer wrapping a tiny valid PNG signature so TexInfo locates it. */
    private static byte[] tex(int id, byte marker) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(png.length).bytes(png);   // tag 0 (color image, fl 0) + len + bytes
        return w.toByteArray();
    }

    /** A mat2 whose `tex` command's first value is a local u8 index. */
    private static byte[] mat2Local(int id, int texIdx) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(4).uint8(texIdx).uint8(0);   // tto: T_UINT8 value, then T_END
        return w.toByteArray();
    }

    /** A mat2 whose `tex` is an external string respath (mlink-style) — not local. */
    private static byte[] mat2External(int id) {
        MessageWriter w = new MessageWriter();
        w.uint16(id);
        w.string("tex").uint8(2).string("gfx/other/peartree-tex").uint8(0);
        return w.toByteArray();
    }

    @Test
    void mapsMatidToTheLocalTextureBytes() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0, (byte) 0x11)));
        res.layers.add(new Layer("tex", tex(1, (byte) 0x22)));
        res.layers.add(new Layer("mat2", mat2Local(5, 1)));   // matid 5 -> local tex #1

        LocalTextures lt = LocalTextures.from(res);
        assertTrue(lt.any());
        assertEquals(Integer.valueOf(1), lt.ordForMatid(5));
        byte[] img = lt.texForMatid(5);
        assertTrue(img != null);
        assertEquals((byte) 0x22, img[8], "resolved to the second tex layer's image");
        assertNull(lt.texForMatid(99), "unknown matid -> no texture");
    }

    @Test
    void externalTexturesAreNotResolvedLocally() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(0, (byte) 0x11)));
        res.layers.add(new Layer("mat2", mat2External(5)));

        LocalTextures lt = LocalTextures.from(res);
        assertFalse(lt.any(), "an external (mlink/string) texture is not a local match");
        assertNull(lt.texForMatid(5));
    }

    @Test
    void resolvesTheTexLayerByIdNotByPosition() {
        // tex ids that differ from their positions (as in the mulberry tree: ids 1,3,4,5).
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, (byte) 0xAA)));   // ordinal 0, id 1
        res.layers.add(new Layer("tex", tex(3, (byte) 0xBB)));   // ordinal 1, id 3
        res.layers.add(new Layer("mat2", mat2Local(5, 1)));      // matid 5 -> tex id 1, at ordinal 0
        res.layers.add(new Layer("mat2", mat2Local(6, 3)));      // matid 6 -> tex id 3, at ordinal 1

        LocalTextures lt = LocalTextures.from(res);
        assertEquals(Integer.valueOf(0), lt.ordForMatid(5), "tex id 1 lives at ordinal 0, not position 1");
        assertEquals((byte) 0xAA, lt.texForMatid(5)[8]);
        assertEquals(Integer.valueOf(1), lt.ordForMatid(6), "tex id 3 lives at ordinal 1");
        assertEquals((byte) 0xBB, lt.texForMatid(6)[8]);
    }

    @Test
    void unknownTexIdResolvesToNothing() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", tex(1, (byte) 0xAA)));
        res.layers.add(new Layer("mat2", mat2Local(5, 9)));      // references tex id 9 (absent)

        LocalTextures lt = LocalTextures.from(res);
        assertFalse(lt.any());
        assertNull(lt.texForMatid(5), "a tex id with no matching layer resolves to no texture");
    }
}
