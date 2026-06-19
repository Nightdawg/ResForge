package hafen.resedit;

import hafen.resedit.io.MessageWriter;
import hafen.resedit.layers.Vbuf2Info;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VbufInfoTest {
    @Test
    void walksThroughBoneData() {
        int num = 2;
        MessageWriter w = new MessageWriter();
        w.uint8(0);                 // fl -> ver 0
        w.uint16(num);              // vertex count
        // pos2 formatted as f4: uint8(1) + "f4" + num*3 float32
        w.string("pos2").uint8(1).string("f4");
        for(int i = 0; i < num * 3; i++) w.float32(i * 1.0f);
        // nrm bare: num*3 float32
        w.string("nrm");
        for(int i = 0; i < num * 3; i++) w.float32(i * 2.0f);
        // bones2: ver1 + "un1" weights + mba, one bone with a 1-vertex run, then terminators
        w.string("bones2").uint8(1).string("un1").uint8(2);
        w.string("b").uint16(1).uint16(0).uint8(255);   // bone "b": run=1, vn=0, one weight
        w.uint16(0).uint16(0);                           // run=0 -> end this bone's spans
        w.string("");                                    // empty bone name -> end bone list

        Vbuf2Info vi = Vbuf2Info.parse(w.toByteArray());
        assertTrue(vi.recognized);
        assertEquals(0, vi.ver);
        assertEquals(num, vi.num);
        assertEquals(List.of("pos2(f4)", "nrm(bare)", "bones2(un1)"), vi.attribs);
        assertNull(vi.stoppedAt);
        assertTrue(vi.fullyWalked);
    }

    @Test
    void fullyWalksWhenAllAttributesKnown() {
        int num = 3;
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(num);
        // pos2 as sn2: 4-byte float32 scale + num*3 int16
        w.string("pos2").uint8(1).string("sn2").float32(1.0f);
        for(int i = 0; i < num * 3; i++) w.int16(i);
        // tex2 as un2: 4-byte scale + num*2 uint16
        w.string("tex2").uint8(1).string("un2").float32(1.0f);
        for(int i = 0; i < num * 2; i++) w.uint16(i);

        Vbuf2Info vi = Vbuf2Info.parse(w.toByteArray());
        assertTrue(vi.fullyWalked);
        assertEquals(List.of("pos2(sn2)", "tex2(un2)"), vi.attribs);
        assertEquals(num, vi.num);
    }
}
