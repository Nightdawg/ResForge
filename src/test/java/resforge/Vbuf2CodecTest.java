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

    private static byte[] formatted(String base, String format) {
        return formatted(base, format, 1);
    }

    private static byte[] formatted(String base, String format, int vertices) {
        int elements = resforge.vbuf.Vbuf2Format.elements(base) * vertices;
        MessageWriter w = new MessageWriter();
        w.uint8(0).uint16(vertices);
        w.string(base + "2").uint8(1).string(format);
        switch(format) {
            case "f1": for(int i = 0; i < elements; i++) w.int8(0); break;
            case "sf9995": w.int32(0); break;
            case "rn4":
                w.float32(0).float32(0);
                for(int i = 0; i < elements; i++) w.int32(0);
                break;
            case "rn2":
                w.float32(0).float32(0);
                for(int i = 0; i < elements; i++) w.uint16(0);
                break;
            case "rn1":
                w.float32(0).float32(0);
                for(int i = 0; i < elements; i++) w.uint8(0);
                break;
            case "uvech": w.uint8(0); break;
            default: throw new AssertionError(format);
        }
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

    @Test
    void f1AttributesCanBeEditedWithinMiniFloatPrecision() {
        float[] values = {0.5f, -1.0f, 3.25f};
        assertEditedValues("pos", "f1", values, 0.26f);

        Vbuf2Codec codec = Vbuf2Codec.parse(formatted("pos", "f1"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.setAttr("pos", new float[]{1000, 0, 0}));
    }

    @Test
    void f1TinyValuesUnderflowToZeroInsteadOfWrappingShiftDistance() {
        Vbuf2Codec codec = Vbuf2Codec.parse(formatted("pos", "f1"));
        codec.setAttr("pos", new float[]{Math.scalb(1.0f, -38), 0, 0});

        float[] decoded = Vbuf2Codec.parse(codec.encode()).decodeAttr("pos");
        assertEquals(0.0f, decoded[0]);
    }

    @Test
    void everyFiniteF1CodeDecodesAndReencodesExactly() {
        for(int bits = 0; bits <= 0xff; bits++) {
            if(((bits >>> 3) & 0xf) == 0xf)
                continue; // Infinity/NaN encodings are deliberately not editable.
            Vbuf2Codec codec = Vbuf2Codec.parse(formatted("pos", "f1"));
            java.util.Arrays.fill(codec.attrs.get(0).data,
                    codec.attrs.get(0).data.length - 3, codec.attrs.get(0).data.length,
                    (byte) bits);
            float[] decoded = codec.decodeAttr("pos");
            codec.setAttr("pos", decoded);
            float[] roundTrip = Vbuf2Codec.parse(codec.encode()).decodeAttr("pos");
            for(int i = 0; i < decoded.length; i++)
                assertEquals(Float.floatToRawIntBits(decoded[i]),
                        Float.floatToRawIntBits(roundTrip[i]), "f1 code 0x"
                                + Integer.toHexString(bits));
        }
    }

    @Test
    void sf9995AttributesCanBeEditedWithinSharedExponentPrecision() {
        assertEditedValues("pos", "sf9995", new float[]{-3.0f, 2.0f, 0.5f}, 0.01f);
    }

    @Test
    void rangeNormalizedAttributesCanBeEditedAtEachWidth() {
        float[] values = {-2.0f, 1.0f, 4.0f, 8.0f};
        assertEditedValues("col", "rn1", values, 0.021f);
        assertEditedValues("col", "rn2", values, 0.0001f);
        assertEditedValues("col", "rn4", values, 0.00001f);
    }

    @Test
    void emptyRangeNormalizedAttributesRetainTheirHeaders() {
        for(String format : new String[]{"rn1", "rn2", "rn4"}) {
            Vbuf2Codec codec = Vbuf2Codec.parse(formatted("col", format, 0));
            codec.setAttr("col", new float[0]);
            Vbuf2Codec reparsed = Vbuf2Codec.parse(codec.encode());
            assertEquals(0, reparsed.decodeAttr("col").length, format);
        }
    }

    @Test
    void halfByteOctahedralVectorsCanBeEditedWithinQuantizationPrecision() {
        float[] values = {0.2f, -0.7f, 0.6855655f};
        assertEditedValues("nrm", "uvech", values, 0.12f);
    }

    private static void assertEditedValues(String base, String format,
                                           float[] values, float tolerance) {
        Vbuf2Codec codec = Vbuf2Codec.parse(formatted(base, format));
        codec.setAttr(base, values);
        Vbuf2Codec reparsed = Vbuf2Codec.parse(codec.encode());
        float[] decoded = reparsed.decodeAttr(base);
        assertEquals(values.length, decoded.length);
        for(int i = 0; i < values.length; i++)
            assertEquals(values[i], decoded[i], tolerance, format + " component " + i);
    }
}
