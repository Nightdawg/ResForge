package resforge;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;
import resforge.layers.BoneOffCodec;
import resforge.res.Layer;
import resforge.res.Manifest;
import resforge.res.Packer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoneOffJsonTest {

    @Test
    void cpfloatReaderWriterAreInverse() {
        MessageWriter w = new MessageWriter();
        double[] vals = {0.0, 1.0, -1.0, 0.5, -2.25, 12.789999961853027, 0.32907199999317527, 1.9963089996017516};
        for(double v : vals)
            w.cpfloat(v);
        MessageReader in = new MessageReader(w.toByteArray());
        for(double v : vals)
            assertEquals(v, in.cpfloat(), 0.0, "cpfloat " + v + " must round-trip exactly");
    }

    @Test
    void cpfloatEncodeReproducesOriginalBytes() {
        // Bytes from a known cpfloat (e=1, mantissa for ~2.5230399994179606).
        MessageWriter src = new MessageWriter();
        src.cpfloat(2.5230399994179606);
        byte[] raw = src.toByteArray();
        double v = new MessageReader(raw).cpfloat();
        assertArrayEquals(raw, new MessageWriter().cpfloat(v).toByteArray(),
                "decode -> encode of a cpfloat must be byte-identical");
    }

    /** A boneoff with the friendly cpfloat/float32/string opcodes (0, 16, 1, 2, 4, 5). */
    private static byte[] boneoffFriendly() {
        MessageWriter w = new MessageWriter();
        w.string("h");
        w.uint8(2).string("Carpals1.R");                      // eqpoint
        w.uint8(0).cpfloat(0.25).cpfloat(1.5).cpfloat(-2.0);  // translate (cpfloat)
        w.uint8(16).float32(0.5f).float32(1.25f).float32(-3f);// translate_f32
        w.uint8(1).cpfloat(1.5).cpfloat(0.0).cpfloat(1.0).cpfloat(0.0); // rotate
        w.uint8(4);                                           // nullrot
        w.uint8(5).float32(2.0f);                             // scale
        return w.toByteArray();
    }

    @Test
    void friendlyOpcodesRoundTripAndDecode() {
        byte[] payload = boneoffFriendly();
        Map<String, Object> m = BoneOffCodec.decode(payload);
        assertEquals("h", m.get("name"));
        List<?> ops = (List<?>) m.get("ops");
        assertEquals(6, ops.size());
        assertEquals("eqpoint", ((Map<?, ?>) ops.get(0)).get("op"));
        assertEquals("Carpals1.R", ((Map<?, ?>) ops.get(0)).get("bone"));
        assertEquals("translate", ((Map<?, ?>) ops.get(1)).get("op"));
        assertEquals(0.25, ((Map<?, ?>) ops.get(1)).get("x"));
        assertEquals("scale", ((Map<?, ?>) ops.get(5)).get("op"));

        assertArrayEquals(payload, BoneOffCodec.encode(m));
        assertNotNull(BoneOffCodec.toJsonIfLossless(payload));
    }

    /** The quantised rotation (opcode 17): angle as a turn-fraction, axis as raw octahedral ints. */
    @Test
    void quantizedRotationRoundTripsViaRawOctahedral() {
        MessageWriter w = new MessageWriter();
        w.string("q");
        w.uint8(17).uint16(0x4000).int16(12345).int16(-9000);
        byte[] payload = w.toByteArray();

        Map<String, Object> m = BoneOffCodec.decode(payload);
        Map<?, ?> op = (Map<?, ?>) ((List<?>) m.get("ops")).get(0);
        assertEquals("rotate_q", op.get("op"));
        assertEquals(0.25, op.get("angleTurns"));            // 0x4000 / 65536 = 0.25
        assertEquals(List.of(12345L, -9000L), op.get("axisOct"));
        assertArrayEquals(payload, BoneOffCodec.encode(m), "raw octahedral ints must re-encode exactly");
    }

    @Test
    void bonealignOpcodesRoundTrip() {
        MessageWriter w = new MessageWriter();
        w.string("a");
        w.uint8(3).cpfloat(0.0).cpfloat(1.0).cpfloat(0.0).string("orig").string("tgt"); // bonealign
        w.uint8(19).int16(100).int16(-200).string("o2").string("t2");                   // bonealign_q
        byte[] payload = w.toByteArray();

        Map<String, Object> m = BoneOffCodec.decode(payload);
        assertArrayEquals(payload, BoneOffCodec.encode(m));
        assertNotNull(BoneOffCodec.toJsonIfLossless(payload));
    }

    @Test
    void unknownOpcodeFallsBackToRaw() {
        MessageWriter w = new MessageWriter();
        w.string("x").uint8(99);
        assertNull(BoneOffCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void editedTranslationSurvivesUnpackRepack(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("boneoff", boneoffFriendly()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("b.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("boneoff", m.entries.get(0).codec);
        assertArrayEquals(original, Packer.pack(dir).serialize(), "unedited boneoff must round-trip");

        // Move the translate's x from 0.25 to 0.75 (both exact cpfloat values).
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replaceFirst("0\\.25", "0.75");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        Map<String, Object> remodel = BoneOffCodec.decode(Packer.pack(dir).layers.get(0).data);
        Map<?, ?> translate = (Map<?, ?>) ((List<?>) remodel.get("ops")).get(1);
        assertEquals(0.75, translate.get("x"));
    }
}
