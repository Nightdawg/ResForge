package resforge;

import resforge.io.MessageWriter;
import resforge.layers.FlavObjInfo;
import resforge.layers.TileInfo;
import resforge.layers.TilesetInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;
import resforge.res.References;
import resforge.res.Replacer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the tileset2 / tile / flavobj decoders and tile-image replace. */
class TilesetTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    /** A tile layer: u8 t, u8 id, u16 w, then the image to end. */
    private static byte[] tile(char t, int id, int w) {
        MessageWriter mw = new MessageWriter();
        mw.uint8(t).uint8(id).uint16(w).bytes(PNG);
        return mw.toByteArray();
    }

    @Test
    void tileDecodesHeaderAndLocatesImage() {
        TileInfo ti = TileInfo.parse(tile('c', 15, 1));
        assertTrue(ti.recognized);
        assertTrue(ti.found);
        assertEquals('c', ti.t);
        assertEquals("centre-transition", ti.kindName());
        assertEquals(15, ti.id);
        assertEquals(1, ti.weight);
        assertEquals(4, ti.imageOffset);
        assertEquals("png", ti.imageFormat);
    }

    @Test
    void tileImageReplaceIsLosslessExceptTheImage() throws Exception {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("tile", tile('g', 0, 5)));
        byte[] newImg = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9, 9};
        Replacer.replace(rc, "tile", newImg);

        TileInfo ti = TileInfo.parse(rc.layers.get(0).data);
        assertEquals('g', ti.t);                 // header preserved
        assertEquals(0, ti.id);
        assertEquals(5, ti.weight);
        assertArrayEquals(newImg, Arrays.copyOfRange(rc.layers.get(0).data, ti.imageOffset, rc.layers.get(0).data.length));
    }

    @Test
    void tilesetDecodesTilerNameTagsAndFlavors() {
        // part 0: tn="trn-r" + empty tto list; part 1: 1 flavor; part 2: 2 tags.
        MessageWriter mw = new MessageWriter();
        mw.uint8(0).string("trn-r").uint8(0);                       // part 0 + empty list (T_END)
        mw.uint8(1).uint16(1).uint16(100)                          // part 1: flnum=1, flavprob=100
                .string("gfx/tiles/flavor/grass").uint16(7).uint8(50);
        mw.uint8(2).int8(2).string("dirt").string("rough");       // part 2: 2 tags
        TilesetInfo ti = TilesetInfo.parse(mw.toByteArray());

        assertTrue(ti.recognized);
        assertTrue(ti.reachedEnd);
        assertEquals("trn-r", ti.tilerName);
        assertEquals(List.of("dirt", "rough"), ti.tags);
        assertEquals(1, ti.flavors.size());
        assertEquals("gfx/tiles/flavor/grass", ti.flavors.get(0).res);
        assertEquals(50, ti.flavors.get(0).weight);
        assertEquals(1, ti.references().size());
    }

    @Test
    void flavobjDecodesResourceSpec() {
        MessageWriter mw = new MessageWriter();
        mw.uint8(1).string("sfx/ambient/wind").uint16(3).uint8(0);   // ver1 + res@3 + empty arg list
        FlavObjInfo fo = FlavObjInfo.parse(mw.toByteArray());
        assertTrue(fo.recognized);
        assertTrue(fo.reachedEnd);
        assertEquals("sfx/ambient/wind", fo.res);
        assertEquals(3, fo.resVer);
        assertEquals(1, fo.references().size());
        assertEquals("sfx/ambient/wind", fo.references().get(0).name);
    }

    @Test
    void referencesIncludeFlavobjAndTilesetFlavors() {
        ResContainer rc = new ResContainer(1);
        // a flavobj
        MessageWriter fo = new MessageWriter();
        fo.uint8(1).string("gfx/flavor/pebble").uint16(2).uint8(0);
        rc.layers.add(new Layer("flavobj", fo.toByteArray()));
        // a tileset2 with one flavor
        MessageWriter ts = new MessageWriter();
        ts.uint8(0).string("gnd").uint8(0);
        ts.uint8(1).uint16(1).uint16(50).string("gfx/flavor/tuft").uint16(4).uint8(10);
        rc.layers.add(new Layer("tileset2", ts.toByteArray()));

        References refs = References.scan(rc);
        Map<String, List<References.Ref>> g = refs.bySource();
        assertTrue(g.containsKey("flavobj"), "flavobj refs surfaced");
        assertTrue(g.containsKey("tileset2"), "tileset2 flavor refs surfaced");
        assertEquals("gfx/flavor/pebble", g.get("flavobj").get(0).name);
        assertEquals("gfx/flavor/tuft", g.get("tileset2").get(0).name);
    }

    @Test
    void tileWithoutImageIsNotFound() {
        MessageWriter mw = new MessageWriter();
        mw.uint8('g').uint8(0).uint16(1).uint8(1).uint8(2).uint8(3);  // no image magic
        TileInfo ti = TileInfo.parse(mw.toByteArray());
        assertTrue(ti.recognized);
        assertFalse(ti.found);
    }
}
