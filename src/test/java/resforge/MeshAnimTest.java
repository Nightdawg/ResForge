package resforge;

import resforge.io.MessageWriter;
import resforge.layers.MeshAnimInfo;
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

class MeshAnimTest {

    /** A manim with one float16 (fmt 3) frame morphing {@code n} vertices in one span. */
    private static byte[] manimF16(int id, boolean rnd, float len, float tm, int n) {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(id).uint8(rnd ? 1 : 0).float32(len);
        w.uint8(3).float32(tm).uint16(n);
        w.uint16(0).uint16(n);                       // one span: start 0, run n
        for(int i = 0; i < n; i++)
            w.float16(0.5f).float16(-0.5f).float16(1.0f);
        w.uint8(0);                                  // end of frames
        return w.toByteArray();
    }

    @Test
    void decodesFloat16Frame() {
        MeshAnimInfo mi = MeshAnimInfo.parse(manimF16(-1, false, 0.5f, 0.1f, 4));
        assertTrue(mi.recognized);
        assertTrue(mi.reachedEnd);
        assertEquals(1, mi.ver);
        assertEquals(-1, mi.id);
        assertFalse(mi.random);
        assertEquals(0.5f, mi.len, 1e-6);
        assertEquals(1, mi.frames.size());
        assertEquals(4, mi.frames.get(0).vertices);
        assertEquals(3, mi.frames.get(0).fmt);
        assertEquals("float16 pos", mi.frames.get(0).formatName());
        assertEquals(4, mi.totalMorphs());
    }

    @Test
    void decodesFloat32AndQuantizedFrames() {
        // one fmt-1 (pos+nrm, 6 float32) frame and one fmt-4 (unorm8 + 6 float16 header) frame
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(7).uint8(1).float32(2.0f);
        // fmt 1: 2 vertices
        w.uint8(1).float32(0f).uint16(2).uint16(0).uint16(2);
        for(int v = 0; v < 2; v++)
            for(int k = 0; k < 6; k++)
                w.float32(0.25f);
        // fmt 4: 3 vertices, with 6-value float16 quantization header
        w.uint8(4).float32(1f).uint16(3);
        for(int k = 0; k < 6; k++)
            w.float16(0f);
        w.uint16(0).uint16(3);
        for(int v = 0; v < 3; v++)
            w.uint8(10).uint8(20).uint8(30);
        w.uint8(0);

        MeshAnimInfo mi = MeshAnimInfo.parse(w.toByteArray());
        assertTrue(mi.recognized);
        assertTrue(mi.reachedEnd);
        assertEquals(7, mi.id);
        assertTrue(mi.random);
        assertEquals(2, mi.frames.size());
        assertEquals(1, mi.frames.get(0).fmt);
        assertEquals(4, mi.frames.get(1).fmt);
        assertEquals(5, mi.totalMorphs());
    }

    @Test
    void multipleSpansInOneFrameAreWalked() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(0).uint8(0).float32(1f);
        w.uint8(3).float32(0f).uint16(4);
        // two spans of 2 vertices each
        w.uint16(0).uint16(2);
        for(int i = 0; i < 2; i++) w.float16(0f).float16(0f).float16(0f);
        w.uint16(10).uint16(2);
        for(int i = 0; i < 2; i++) w.float16(0f).float16(0f).float16(0f);
        w.uint8(0);
        MeshAnimInfo mi = MeshAnimInfo.parse(w.toByteArray());
        assertTrue(mi.recognized);
        assertEquals(1, mi.frames.size());
        assertEquals(4, mi.frames.get(0).vertices);
    }

    @Test
    void unknownFrameFormatIsNotRecognized() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(0).uint8(0).float32(1f);
        w.uint8(9);                                  // invalid frame format (>4)
        MeshAnimInfo mi = MeshAnimInfo.parse(w.toByteArray());
        assertFalse(mi.recognized);
    }

    @Test
    void wrongVersionIsNotRecognized() {
        byte[] payload = new MessageWriter().uint8(2).toByteArray();
        assertFalse(MeshAnimInfo.parse(payload).recognized);
    }

    @Test
    void manimLayerSurvivesUnpackPackUnchanged(@TempDir Path tmp) throws Exception {
        ResContainer rc = new ResContainer(1);
        rc.layers.add(new Layer("manim", manimF16(-1, true, 0.5f, 0.2f, 3)));
        byte[] original = rc.serialize();

        Path out = tmp.resolve("unpacked");
        Files.createDirectories(out);
        Unpacker.unpack(ResContainer.parse(original), out);
        assertArrayEquals(original, Packer.pack(out).serialize(),
                "manim layer must round-trip byte-for-byte");
    }
}
