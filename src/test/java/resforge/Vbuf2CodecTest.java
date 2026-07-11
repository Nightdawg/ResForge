package resforge;

import resforge.io.MessageWriter;
import resforge.vbuf.Vbuf2Codec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Vbuf2CodecTest {
    /** vbuf2 ver=0 with pos2(sn2) + tex2(un2) + a bone block. */
    private static byte[] richVbuf() {
        int num = 2;
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(num);
        w.string("pos2").uint8(1).string("sn2").float32(2.0f);
        for(int i = 0; i < num * 3; i++) w.int16(1000 + i);
        w.string("tex2").uint8(1).string("un2").float32(1.0f);
        for(int i = 0; i < num * 2; i++) w.uint16(2000 + i);
        w.string("bones2").uint8(1).string("un1").uint8(2);
        w.string("b").uint16(1).uint16(0).uint8(255).uint16(0).uint16(0);
        w.string("");
        return w.toByteArray();
    }

    private static byte[] posF4(int num, float[] xyz) {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(num);
        w.string("pos2").uint8(1).string("f4");
        for(float v : xyz) w.float32(v);
        return w.toByteArray();
    }

    @Test
    void reencodeIsByteIdentical() {
        byte[] payload = richVbuf();
        Vbuf2Codec c = Vbuf2Codec.parse(payload);
        assertArrayEquals(payload, c.encode(), "unchanged vbuf2 must re-encode byte-identically");
        assertEquals(2, c.num);
        assertEquals(3, c.attrs.size());
    }

    @Test
    void f4PositionEditReEncodesExactly() {
        float[] xyz = {1, 2, 3, -4, 5, -6};
        Vbuf2Codec c = Vbuf2Codec.parse(posF4(2, xyz));
        float[] p = c.decodePositions();
        for(int v = 0; v < c.num; v++) {
            p[v * 3]     *= 1.0f;
            p[v * 3 + 1] *= 1.0f;
            p[v * 3 + 2] *= 2.0f;   // double the z axis
        }
        c.setPositions(p);

        float[] back = Vbuf2Codec.parse(c.encode()).decodePositions();
        // f4 is exact: x,y unchanged, z doubled.
        assertEquals(1.0f, back[0]); assertEquals(2.0f, back[1]); assertEquals(6.0f, back[2]);
        assertEquals(-4.0f, back[3]); assertEquals(5.0f, back[4]); assertEquals(-12.0f, back[5]);
    }

    @Test
    void editedVbufStillReencodesAndPreservesOtherAttribs() {
        byte[] payload = richVbuf();
        Vbuf2Codec c = Vbuf2Codec.parse(payload);
        byte[] texBefore = c.attrs.get(1).data.clone();   // tex2

        float[] p = c.decodePositions();
        for(int i = 0; i < p.length; i++) p[i] *= 1.5f;
        c.setPositions(p);

        // Re-encode must still parse, and the untouched tex attribute is byte-identical.
        Vbuf2Codec re = Vbuf2Codec.parse(c.encode());
        assertEquals(c.num, re.num);
        assertArrayEquals(texBefore, re.attrs.get(1).data);
    }

    @Test
    void nonFinitePositionIsRejectedNotSilentlyCorrupting() {
        // A single NaN/Inf coordinate used to poison the shared max factor of a
        // quantised attribute (Math.max(x, NaN) == NaN), turning EVERY vertex into
        // NaN on the next decode — a silent, whole-mesh corruption with no error.
        // Now it must fail loudly instead.
        Vbuf2Codec c = Vbuf2Codec.parse(richVbuf());   // pos2 is sn2 (quantised)
        float[] p = c.decodePositions();

        p[2] = Float.NaN;
        IllegalArgumentException nan = assertThrows(IllegalArgumentException.class, () -> c.setPositions(p));
        assertTrue(nan.getMessage().contains("non-finite"), nan.getMessage());

        p[2] = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> c.setPositions(p));

        // setAttr (used by the glTF rebuild for pos/nrm/tex/…) is guarded the same way.
        float[] q = c.decodePositions();
        q[0] = Float.NEGATIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> c.setAttr("pos", q));
    }

    @Test
    void nonFiniteIsRejectedEvenForExactF4Positions() {
        // f4 is byte-lossless, so a NaN would "round-trip" — but a NaN position is
        // never valid geometry, so the encoder rejects it rather than writing it.
        Vbuf2Codec c = Vbuf2Codec.parse(posF4(2, new float[]{1, 2, 3, 4, 5, 6}));
        float[] p = c.decodePositions();
        p[4] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> c.setPositions(p));
    }
}
