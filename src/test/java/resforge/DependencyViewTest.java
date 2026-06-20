package resforge;

import resforge.io.MessageWriter;
import resforge.layers.DepsInfo;
import resforge.layers.RLinkInfo;
import resforge.layers.SrcInfo;
import resforge.res.Layer;
import resforge.res.Packer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyViewTest {

    /* ------------------------------------------------------------------ deps */

    @Test
    void depsDecodesNameVersionRecords() {
        byte[] payload = new MessageWriter()
                .uint8(1)
                .string("lib/dynspr").uint16(16)
                .string("sfx/creak").uint16(3)
                .string("gfx/terobjs/knarr").uint16(2)
                .toByteArray();

        DepsInfo di = DepsInfo.parse(payload);
        assertTrue(di.recognized);
        assertTrue(di.reachedEnd);
        assertEquals(1, di.ver);
        assertEquals(3, di.deps.size());
        assertEquals("lib/dynspr", di.deps.get(0).name);
        assertEquals(16, di.deps.get(0).ver);
        assertEquals("sfx/creak", di.deps.get(1).name);
        assertEquals(3, di.deps.get(1).ver);
        assertEquals("gfx/terobjs/knarr", di.deps.get(2).name);
        assertEquals(2, di.deps.get(2).ver);
    }

    @Test
    void depsEmptyListIsRecognized() {
        byte[] payload = new MessageWriter().uint8(1).toByteArray();
        DepsInfo di = DepsInfo.parse(payload);
        assertTrue(di.recognized);
        assertTrue(di.deps.isEmpty());
    }

    @Test
    void depsTruncatedRecordFailsGracefully() {
        // version + a name but no uint16 version -> reader runs past end
        byte[] payload = new MessageWriter().uint8(1).string("lib/dynspr").toByteArray();
        DepsInfo di = DepsInfo.parse(payload);
        assertFalse(di.recognized);
    }

    /* ------------------------------------------------------------------- src */

    @Test
    void srcDecodesNameAndBody() {
        byte[] body = "/* code */\npackage haven.res;\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new MessageWriter()
                .uint8(1)
                .string("Tree.java")
                .bytes(body)
                .toByteArray();

        SrcInfo si = SrcInfo.parse(payload);
        assertTrue(si.recognized);
        assertEquals(1, si.ver);
        assertEquals("Tree.java", si.fileName);
        assertArrayEquals(body, si.source);
        assertEquals(new String(body, StandardCharsets.UTF_8), si.text());
    }

    @Test
    void srcEmptyBodyIsRecognized() {
        byte[] payload = new MessageWriter().uint8(1).string("Empty.java").toByteArray();
        SrcInfo si = SrcInfo.parse(payload);
        assertTrue(si.recognized);
        assertEquals("Empty.java", si.fileName);
        assertEquals(0, si.source.length);
    }

    /* ----------------------------------------------------------------- rlink */

    @Test
    void rlinkDecodesLinkWithMapSpecAndNestedRefs() {
        // ver=3; one entry: id=0, type=3, res="gfx/fx/eq"@21, spec = tto map
        //   { "eq": "s1", "res": res("gfx/fx/candle-flare64"@8) }
        byte[] payload = new MessageWriter()
                .uint8(3)
                .uint16(0).uint8(3)
                .string("gfx/fx/eq").uint16(21)
                .uint8(32)                                   // tto map
                    .uint8(2).string("eq").uint8(2).string("s1")
                    .uint8(2).string("res").uint8(34).string("gfx/fx/candle-flare64").uint16(8)
                .uint8(0)                                     // end map
                .uint8(0)                                     // end spec list
                .toByteArray();

        RLinkInfo ri = RLinkInfo.parse(payload);
        assertTrue(ri.recognized);
        assertTrue(ri.reachedEnd);
        assertEquals(3, ri.ver);
        assertEquals(1, ri.links.size());

        RLinkInfo.Link lk = ri.links.get(0);
        assertEquals(0, lk.id);
        assertEquals("gfx/fx/eq", lk.res);
        assertEquals(21, lk.ver);
        assertTrue(lk.spec.contains("gfx/fx/candle-flare64"));

        // references() == link target + nested res()
        assertEquals(2, ri.references().size());
        assertEquals("gfx/fx/eq", ri.references().get(0).name);
        assertEquals("gfx/fx/candle-flare64", ri.references().get(1).name);
        assertEquals(8, ri.references().get(1).ver);
    }

    @Test
    void rlinkSpecTerminatedByEndOfMessage() {
        // ver=3; one entry whose spec ends at EOM (no trailing 0 terminator)
        byte[] payload = new MessageWriter()
                .uint8(3)
                .uint16(6401).uint8(3)
                .string("gfx/terobjs/trees/fallenfruit").uint16(3)
                .uint8(2).string("gfx/terobjs/items/mulberry-yester")
                .uint8(4).uint8(1).uint8(4).uint8(3)
                .toByteArray();

        RLinkInfo ri = RLinkInfo.parse(payload);
        assertTrue(ri.recognized);
        assertEquals(1, ri.links.size());
        assertEquals("gfx/terobjs/trees/fallenfruit", ri.links.get(0).res);
        assertEquals(3, ri.links.get(0).ver);
        // the plain-string member is rendered in the spec
        assertTrue(ri.links.get(0).spec.contains("mulberry-yester"));
    }

    @Test
    void rlinkUnknownEntryTypeIsTolerated() {
        // ver=3; one good link, then an entry with an unknown type byte
        byte[] payload = new MessageWriter()
                .uint8(3)
                .uint16(0).uint8(3).string("gfx/fx/eq").uint16(21).uint8(0)
                .uint16(1).uint8(99)                         // unknown entry type
                .toByteArray();

        RLinkInfo ri = RLinkInfo.parse(payload);
        assertFalse(ri.recognized);                          // did not cleanly finish
        assertEquals(1, ri.links.size());                    // but kept the good link
        assertEquals("gfx/fx/eq", ri.links.get(0).res);
    }

    /* --------------------------------------------- raw passthrough losslessness */

    @Test
    void dependencyLayersSurviveUnpackPackUnchanged(@TempDir Path tmp) throws Exception {
        byte[] deps = new MessageWriter().uint8(1)
                .string("lib/dynspr").uint16(16).toByteArray();
        byte[] src = new MessageWriter().uint8(1).string("Tree.java")
                .bytes("class Tree {}".getBytes(StandardCharsets.UTF_8)).toByteArray();
        byte[] rlink = new MessageWriter().uint8(3).uint16(0).uint8(3)
                .string("gfx/fx/eq").uint16(21).uint8(0).toByteArray();

        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("deps", deps));
        rc.layers.add(new Layer("src", src));
        rc.layers.add(new Layer("rlink", rlink));
        byte[] original = rc.serialize();

        Path out = tmp.resolve("unpacked");
        Files.createDirectories(out);
        Unpacker.unpack(ResContainer.parse(original), out);

        assertArrayEquals(original, Packer.pack(out).serialize(),
                "dependency layers must round-trip byte-for-byte");
    }
}
