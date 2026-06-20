package resforge;

import resforge.io.MessageWriter;
import resforge.layers.Mat2Codec;
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

class Mat2JsonTest {
    /** Mirrors male.res's mat2: id + cel/col/light/tex commands using str, u8, color, f32. */
    private static byte[] maleLikeMat2() {
        MessageWriter w = new MessageWriter();
        w.uint16(1);                                   // id
        w.string("cel"); w.uint8(0);                   // cel -> []  (T_END)
        w.string("col");
        w.uint8(6).uint8(204).uint8(204).uint8(204).uint8(255);  // T_COLOR
        w.uint8(6).uint8(164).uint8(164).uint8(164).uint8(255);
        w.uint8(6).uint8(0).uint8(0).uint8(0).uint8(255);
        w.uint8(6).uint8(0).uint8(0).uint8(0).uint8(255);
        w.uint8(15).float32(0f);                       // T_FLOAT32
        w.uint8(0);                                    // T_END
        w.string("light");
        w.uint8(2).string("def");                      // T_STR
        w.uint8(0);
        w.string("tex");
        w.uint8(4).uint8(0);                           // T_UINT8
        w.uint8(0);
        return w.toByteArray();
    }

    @Test
    void mat2DecodeEncodeIsLossless() {
        byte[] payload = maleLikeMat2();
        Map<String, Object> m = Mat2Codec.decode(payload);
        assertEquals(1L, m.get("id"));
        List<?> entries = (List<?>) m.get("entries");
        assertEquals(4, entries.size());

        Map<?, ?> cel = (Map<?, ?>) entries.get(0);
        assertEquals("cel", cel.get("key"));
        assertEquals(List.of(), cel.get("values"));

        Map<?, ?> col = (Map<?, ?>) entries.get(1);
        List<?> colVals = (List<?>) col.get("values");
        assertEquals(5, colVals.size());
        assertEquals(Map.of("color", List.of(204L, 204L, 204L, 255L)), colVals.get(0));
        assertEquals(Map.of("f32", 0.0), colVals.get(4));

        assertArrayEquals(payload, Mat2Codec.encode(m));
        assertNotNull(Mat2Codec.toJsonIfLossless(payload), "male-like mat2 must be lossless JSON");
    }

    @Test
    void mat2FloatValueRoundTrips() {
        // 3.5 is exactly representable in float32; must survive decode->JSON->encode.
        MessageWriter w = new MessageWriter();
        w.uint16(1);
        w.string("col");
        w.uint8(15).float32(3.5f);
        w.uint8(0);
        byte[] payload = w.toByteArray();
        assertArrayEquals(payload, Mat2Codec.encode(Mat2Codec.decode(payload)));
        assertNotNull(Mat2Codec.toJsonIfLossless(payload));
    }

    @Test
    void unsupportedMat2FallsBackToRaw() {
        // A coord value (T_COORD) is not modelled -> must not be exposed as JSON.
        MessageWriter w = new MessageWriter();
        w.uint16(1);
        w.string("weird");
        w.uint8(3).int32(10).int32(20);   // T_COORD
        w.uint8(0);
        assertNull(Mat2Codec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void mat2LayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(26);
        res.layers.add(new Layer("mat2", maleLikeMat2()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("m.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("mat2", m.entries.get(0).codec);
        assertTrue(m.entries.get(0).parts.get(0).endsWith(".json"));

        // Untouched repack is byte-identical.
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Edit the "light" command's value "def" -> "spec" and confirm it survives a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replace("\"def\"", "\"spec\"");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        byte[] mat2Data = Packer.pack(dir).layers.get(0).data;
        Map<String, Object> remodel = Mat2Codec.decode(mat2Data);
        List<?> entries = (List<?>) remodel.get("entries");
        Map<?, ?> light = (Map<?, ?>) entries.get(2);
        assertEquals("light", light.get("key"));
        assertEquals(List.of("spec"), light.get("values"));
    }
}
