package resforge;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;
import resforge.layers.BoneOffInfo;
import resforge.layers.LightInfo;
import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.res.Layer;
import resforge.res.Packer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigViewersTest {

    /* ----- a faithful cpfloat encoder (inverse of Utils.floatd) for fixtures ----- */
    private static void cpfloat(MessageWriter w, double x) {
        if(x == 0.0) {
            w.int8(-128).int32(0);
            return;
        }
        boolean s = x < 0;
        double ax = Math.abs(x);
        int e = (int) Math.floor(Math.log(ax) / Math.log(2));
        double v = ax / Math.pow(2, e);     // in [1, 2)
        long m = Math.round((v - 1.0) * 2147483648.0);
        if(m >= 2147483648L) {              // rounded up to 2.0
            e++;
            m = 0;
        }
        int t = (int) (m & 0x7fffffffL);
        if(s)
            t |= 0x80000000;
        w.int8(e).int32(t);
    }

    /* --------------------------------------------------------------- primitives */

    @Test
    void cpfloatReadsSpecialZeroAndRoundTrips() {
        MessageWriter w = new MessageWriter();
        cpfloat(w, 0.0);
        cpfloat(w, 1.0);
        cpfloat(w, -2.5);
        cpfloat(w, 0.4);
        cpfloat(w, 1234.5);
        MessageReader in = new MessageReader(w.toByteArray());
        assertEquals(0.0, in.cpfloat(), 0.0);
        assertEquals(1.0, in.cpfloat(), 1e-9);
        assertEquals(-2.5, in.cpfloat(), 1e-9);
        assertEquals(0.4, in.cpfloat(), 1e-6);
        assertEquals(1234.5, in.cpfloat(), 1e-3);
    }

    @Test
    void normPrimitivesMatchClientScaling() {
        MessageWriter w = new MessageWriter();
        w.uint16(0x7fff).int16(0x7fff).uint16(0x8000);
        MessageReader in = new MessageReader(w.toByteArray());
        assertEquals(0.5f, in.mnorm16(), 1e-4);          // 0x7fff / 0x10000
        assertEquals(1.0f, in.snorm16(), 1e-4);          // 0x7fff / 0x7fff
        assertEquals(0x8000 / 65535.0f, in.unorm16(), 1e-6);
    }

    @Test
    void oct2uvecProducesUnitVectors() {
        float[] v = new float[3];
        MessageReader.oct2uvec(v, 0f, 0f);
        assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-5);
        MessageReader.oct2uvec(v, 0.5f, -0.5f);
        assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-5);
    }

    /* ------------------------------------------------------------------- light */

    @Test
    void lightVer1DecodesColorsAndAttenuation() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(7);
        // amb, dif, spc as float32 RGBA
        for(int c = 0; c < 12; c++)
            w.float32(c % 4 == 3 ? 1.0f : 0.5f);
        w.uint8(1).float32(1f).float32(0.2f).float32(0.05f);   // attenuation
        w.uint8(2).float32(0f).float32(0f).float32(1f);        // direction
        LightInfo li = LightInfo.parse(w.toByteArray());
        assertTrue(li.recognized);
        assertTrue(li.reachedEnd);
        assertEquals(1, li.ver);
        assertEquals(7, li.id);
        assertEquals(127, li.amb[0]);          // 0.5 * 255
        assertEquals(255, li.amb[3]);
        assertTrue(li.hasAtt);
        assertTrue(li.hasDir);
        assertEquals("point light", li.kind());
    }

    @Test
    void lightVer0UsesCpfloat() {
        MessageWriter w = new MessageWriter();
        w.uint8(0).int8(3);
        for(int c = 0; c < 12; c++)
            cpfloat(w, c % 4 == 3 ? 1.0 : 1.0);   // all 1.0 -> 255
        LightInfo li = LightInfo.parse(w.toByteArray());
        assertTrue(li.recognized);
        assertEquals(0, li.ver);
        assertEquals(3, li.id);
        assertEquals(255, li.dif[1]);
        assertEquals("directional light", li.kind());   // no attenuation tag
    }

    /* -------------------------------------------------------------------- skel */

    @Test
    void skelVer1DecodesBonesAndParents() {
        MessageWriter w = new MessageWriter();
        // skel always begins in ver-0 mode; a 1-char string with code <32 switches
        // the sub-version (here \u0001 selects the ver-1 bone encoding).
        w.string("\u0001");
        // bone "root" (no parent)
        w.string("root").string("");
        w.float32(0f).float32(0f).float32(0f);
        w.uint16(0).int16(0).int16(0);
        // bone "child" parented to root
        w.string("child").string("root");
        w.float32(1f).float32(2f).float32(3f);
        w.uint16(0x4000).int16(0).int16(0);
        SkelInfo si = SkelInfo.parse(w.toByteArray());
        assertTrue(si.recognized);
        assertTrue(si.reachedEnd);
        assertEquals(2, si.bones.size());
        assertEquals(1, si.rootCount());
        assertEquals("root", si.bones.get(0).name);
        assertEquals("root", si.bones.get(1).parent);
        assertEquals(3f, si.bones.get(1).pz, 1e-6);
    }

    /* ----------------------------------------------------------------- boneoff */

    @Test
    void boneOffDecodesOpcodeProgram() {
        MessageWriter w = new MessageWriter();
        w.string("hand");
        w.uint8(2).string("Main");                       // equip point
        w.uint8(16).float32(1f).float32(2f).float32(3f); // translate (f32)
        w.uint8(4);                                      // null rotation
        w.uint8(5).float32(2f);                          // scale
        BoneOffInfo bo = BoneOffInfo.parse(w.toByteArray());
        assertTrue(bo.recognized);
        assertTrue(bo.reachedEnd);
        assertEquals("hand", bo.name);
        assertEquals(4, bo.ops.size());
        assertEquals(2, bo.ops.get(0).code);
        assertTrue(bo.ops.get(0).desc.contains("Main"));
        assertTrue(bo.ops.get(3).desc.contains("scale"));
    }

    @Test
    void boneOffUnknownOpcodeIsNotRecognized() {
        MessageWriter w = new MessageWriter();
        w.string("x").uint8(200);
        BoneOffInfo bo = BoneOffInfo.parse(w.toByteArray());
        assertFalse(bo.recognized);
    }

    /* -------------------------------------------------------------------- skan */

    @Test
    void skanVer1DecodesTracksAndMode() {
        MessageWriter w = new MessageWriter();
        w.int16(5);              // id
        w.uint8(2);              // fl: fmt = (2&6)>>1 = 1, no nspeed
        w.uint8(1);              // mode = loop
        w.float32(4.0f);         // len
        // one track "bone0" with 2 frames (fmt 1: u16 time, 3 int16, u16 angle, 2 int16)
        w.string("bone0").uint16(2);
        for(int f = 0; f < 2; f++) {
            w.uint16(0).int16(0).int16(0).int16(0).uint16(0).int16(0).int16(0);
        }
        SkanInfo si = SkanInfo.parse(w.toByteArray());
        assertTrue(si.recognized);
        assertTrue(si.reachedEnd);
        assertEquals(5, si.id);
        assertEquals("loop", si.mode);
        assertEquals(4f, si.len, 1e-6);
        assertEquals(1, si.tracks.size());
        assertEquals(2, si.tracks.get(0).frames);
        assertEquals(2, si.totalFrames());
    }

    /* --------------------------------------------- raw passthrough losslessness */

    @Test
    void rigLayersSurviveUnpackPackUnchanged(@TempDir Path tmp) throws Exception {
        MessageWriter light = new MessageWriter();
        light.uint8(1).int16(0);
        for(int c = 0; c < 12; c++)
            light.float32(0.5f);

        MessageWriter boneoff = new MessageWriter();
        boneoff.string("p").uint8(16).float32(1f).float32(0f).float32(0f);

        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("light", light.toByteArray()));
        rc.layers.add(new Layer("boneoff", boneoff.toByteArray()));
        byte[] original = rc.serialize();

        Path out = tmp.resolve("unpacked");
        Files.createDirectories(out);
        Unpacker.unpack(ResContainer.parse(original), out);
        assertArrayEquals(original, Packer.pack(out).serialize(),
                "rig layers must round-trip byte-for-byte");
    }
}
