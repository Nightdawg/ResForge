package resforge;

import resforge.io.MessageWriter;
import resforge.layers.CodeEntryInfo;
import resforge.layers.CodeInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeLayerTest {
    @Test
    void codeDecodesNameAndClassBytes() {
        byte[] classBytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 1, 2, 3};
        MessageWriter w = new MessageWriter();
        w.string("haven.res.lib.test.Foo");
        w.bytes(classBytes);

        CodeInfo ci = CodeInfo.parse(w.toByteArray());
        assertTrue(ci.recognized);
        assertEquals("haven.res.lib.test.Foo", ci.name);
        assertTrue(ci.isClassFile, "must detect the 0xCAFEBABE class-file magic");
        assertArrayEquals(classBytes, ci.code);
    }

    @Test
    void codeNonClassPayloadIsRecognizedButNotClassFile() {
        MessageWriter w = new MessageWriter();
        w.string("x");
        w.bytes(new byte[]{1, 2, 3, 4});
        CodeInfo ci = CodeInfo.parse(w.toByteArray());
        assertTrue(ci.recognized);
        assertFalse(ci.isClassFile);
    }

    /** Mirrors vmat.res/hide.res: a t==1 entry section plus a t==2 classpath section. */
    @Test
    void codeEntryDecodesEntriesAndClasspath() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);                                                   // section: entries
        w.string("objdelta").string("haven.res.lib.vmat.AttrMats");
        w.string("spr").string("haven.res.lib.vmat.VarSprite");
        w.string("").string("");                                     // terminator (en="" , cn="")
        w.uint8(2);                                                   // section: classpath
        w.string("lib/cattlemat").uint16(7);
        w.string("mat").uint16(0);
        w.string("");                                                // terminator (ln="")

        CodeEntryInfo ce = CodeEntryInfo.parse(w.toByteArray());
        assertTrue(ce.recognized);
        assertTrue(ce.reachedEnd);
        assertEquals(2, ce.entries.size());
        assertEquals("objdelta", ce.entries.get(0).name);
        assertEquals("haven.res.lib.vmat.AttrMats", ce.entries.get(0).className);
        assertEquals(2, ce.classpath.size());
        assertEquals("lib/cattlemat", ce.classpath.get(0).name);
        assertEquals(7, ce.classpath.get(0).ver);
    }

    /** Mirrors knarr.res: a t==3 entry with a tto argument list [["mat",16],["mesh",16]]. */
    @Test
    void codeEntryDecodesArgumentsForTag3() {
        MessageWriter w = new MessageWriter();
        w.uint8(3);
        w.string("spr").string("haven.res.lib.dynspr.Dyntex");
        w.uint8(8).uint8(2).string("mat").uint8(4).uint8(16).uint8(0);   // ["mat", u8:16]
        w.uint8(8).uint8(2).string("mesh").uint8(4).uint8(16).uint8(0);  // ["mesh", u8:16]
        w.uint8(0);                                                      // end args list
        w.string("").string("");                                        // terminator

        CodeEntryInfo ce = CodeEntryInfo.parse(w.toByteArray());
        assertTrue(ce.recognized);
        assertTrue(ce.reachedEnd);
        assertEquals(1, ce.entries.size());
        assertEquals("spr", ce.entries.get(0).name);
        assertEquals("haven.res.lib.dynspr.Dyntex", ce.entries.get(0).className);
        assertEquals("[[\"mat\", 16], [\"mesh\", 16]]", ce.entries.get(0).args);
    }
}
