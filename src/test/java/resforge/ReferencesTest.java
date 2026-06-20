package resforge;

import resforge.io.MessageWriter;
import resforge.res.Layer;
import resforge.res.References;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencesTest {

    private static byte[] deps(Object... nameVerPairs) {
        MessageWriter w = new MessageWriter().uint8(1);
        for(int i = 0; i < nameVerPairs.length; i += 2)
            w.string((String) nameVerPairs[i]).uint16((Integer) nameVerPairs[i + 1]);
        return w.toByteArray();
    }

    private static byte[] codeentry(String name, int ver) {
        // t==2 classpath section: [resName, uint16 ver] until empty name
        return new MessageWriter().uint8(2).string(name).uint16(ver).string("").toByteArray();
    }

    private static byte[] rlinkToEq() {
        return new MessageWriter()
                .uint8(3).uint16(0).uint8(3)
                .string("gfx/fx/eq").uint16(21)
                .uint8(32)
                    .uint8(2).string("res").uint8(34).string("gfx/fx/flight").uint16(6)
                .uint8(0).uint8(0)
                .toByteArray();
    }

    /** A mat2 with an mlink (resource path), an external tex (path), a local tex
     *  (u8, not a ref), and a mode-name string ("def", no slash, not a ref). */
    private static byte[] mat2() {
        return new MessageWriter()
                .uint16(7)
                .string("mlink").uint8(2).string("gfx/terobjs/subst/tin").uint8(0)
                .string("tex").uint8(2).string("gfx/fx/oarsplash").uint8(0)
                .string("otex").uint8(4).uint8(0).uint8(0)              // local tex: {u8:0}
                .string("light").uint8(2).string("def").uint8(0)        // mode name, no slash
                .toByteArray();
    }

    @Test
    void aggregatesAcrossLayersAndDedupesDistinct() {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("deps", deps("lib/vmat", 39, "gfx/fx/eq", 21, "gfx/terobjs/subst/tin", 7)));
        rc.layers.add(new Layer("codeentry", codeentry("lib/vmat", 39)));   // dup of a deps entry
        rc.layers.add(new Layer("rlink", rlinkToEq()));                     // eq (dup) + flight (new)
        rc.layers.add(new Layer("mat2", mat2()));                          // tin (dup) + oarsplash (new)

        References refs = References.scan(rc);
        Map<String, List<References.Ref>> grouped = refs.bySource();

        // grouped by source, in canonical order
        assertEquals(List.of("deps", "rlink", "codeentry", "mat2"), List.copyOf(grouped.keySet()));
        assertEquals(3, grouped.get("deps").size());
        assertEquals(2, grouped.get("rlink").size());     // eq + flight
        assertEquals(1, grouped.get("codeentry").size());
        assertEquals(2, grouped.get("mat2").size());       // tin + oarsplash (NOT the local tex or "def")

        // distinct union: lib/vmat, gfx/fx/eq, gfx/terobjs/subst/tin, gfx/fx/flight, gfx/fx/oarsplash
        assertEquals(5, refs.distinctNames().size());
        assertTrue(refs.distinctNames().contains("gfx/fx/flight"));
        assertTrue(refs.distinctNames().contains("gfx/fx/oarsplash"));
    }

    @Test
    void mat2OnlyKeepsSlashBearingStrings() {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("mat2", mat2()));
        List<References.Ref> m = References.scan(rc).bySource().get("mat2");
        assertEquals(2, m.size());
        // local tex {u8:0} and the "def" mode name must be excluded
        for(References.Ref r : m)
            assertTrue(r.name.indexOf('/') >= 0, "mat2 ref should look like a path: " + r.name);
    }

    @Test
    void renderListsCountAndSources() {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("deps", deps("lib/vmat", 39, "gfx/fx/eq", 21)));
        String report = References.scan(rc).render("thing.res");
        assertTrue(report.contains("References for thing.res"));
        assertTrue(report.contains("2 distinct resources referenced"));
        assertTrue(report.contains("from deps (2):"));
        assertTrue(report.contains("lib/vmat @v39"));
    }

    @Test
    void noReferenceBearingLayersYieldsEmptyReport() {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("image", new byte[]{0, 0, 0}));
        References refs = References.scan(rc);
        assertTrue(refs.bySource().isEmpty());
        assertEquals(0, refs.distinctNames().size());
        assertTrue(refs.render("x").contains("references no others"));
    }

    @Test
    void unrecognizedLayerIsToleratedNotCrashing() {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("deps", new byte[]{0x01, 0x41}));   // truncated deps (name, no ver)
        rc.layers.add(new Layer("mat2", new byte[]{0x07}));         // id only, no entries
        References refs = References.scan(rc);
        assertFalse(refs.render("x").isEmpty());                   // did not throw
    }
}
