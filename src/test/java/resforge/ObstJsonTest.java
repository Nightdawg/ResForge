package resforge;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;
import resforge.layers.ObstCodec;
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

class ObstJsonTest {
    @Test
    void float16RoundTripsForAllNonNaNHalves() {
        // half -> float -> half must be the identity for every normal/subnormal/zero/inf
        // bit pattern (NaN excluded — its mantissa is canonicalized on re-encode).
        for(int h = 0; h <= 0xffff; h++) {
            boolean isNaN = ((h & 0x7c00) == 0x7c00) && ((h & 0x03ff) != 0);
            if(isNaN)
                continue;
            float f = MessageReader.halfToFloat(h);
            int back = MessageWriter.floatToHalf(f);
            assertEquals(h, back, "half 0x" + Integer.toHexString(h) + " did not round-trip");
        }
    }

    @Test
    void float16ReaderWriterAreInverse() {
        MessageWriter w = new MessageWriter();
        w.float16(1.5f).float16(-2.25f).float16(0f).float16(0.0009765625f);
        MessageReader in = new MessageReader(w.toByteArray());
        assertEquals(1.5f, in.float16());
        assertEquals(-2.25f, in.float16());
        assertEquals(0f, in.float16());
        assertEquals(0.0009765625f, in.float16());
    }

    /** A ver-1 obst: one 4-point polygon (values chosen to be exact float16). */
    private static byte[] obstV1() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);                 // version
        w.uint8(1);                 // 1 polygon
        w.uint8(4);                 // 4 points
        w.float16(1.5f).float16(-1.5f);
        w.float16(1.5f).float16(1.5f);
        w.float16(-1.5f).float16(1.5f);
        w.float16(-1.5f).float16(-1.5f);
        return w.toByteArray();
    }

    @Test
    void obstDecodeEncodeIsLossless() {
        byte[] payload = obstV1();
        Map<String, Object> m = ObstCodec.decode(payload);
        assertEquals(1L, m.get("version"));
        List<?> polys = (List<?>) m.get("polygons");
        assertEquals(1, polys.size());
        assertEquals(4, ((List<?>) polys.get(0)).size());
        assertEquals(List.of(1.5, -1.5), ((List<?>) polys.get(0)).get(0));
        assertArrayEquals(payload, ObstCodec.encode(m));
        assertNotNull(ObstCodec.toJsonIfLossless(payload));
    }

    /** Version-2 obst carries a string id before the polygons. */
    @Test
    void obstVersion2WithIdRoundTrips() {
        MessageWriter w = new MessageWriter();
        w.uint8(2).string("box");
        w.uint8(1).uint8(3);
        w.float16(0f).float16(0f);
        w.float16(2f).float16(0f);
        w.float16(0f).float16(2f);
        byte[] payload = w.toByteArray();

        Map<String, Object> m = ObstCodec.decode(payload);
        assertEquals(2L, m.get("version"));
        assertEquals("box", m.get("id"));
        assertArrayEquals(payload, ObstCodec.encode(m));
        assertNotNull(ObstCodec.toJsonIfLossless(payload));
    }

    @Test
    void multiplePolygonsRoundTrip() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).uint8(2);        // 2 polygons
        w.uint8(3).uint8(4);        // counts: 3, then 4 (all counts first)
        for(int i = 0; i < 3; i++) w.float16(i).float16(-i);
        for(int i = 0; i < 4; i++) w.float16(-i).float16(i);
        byte[] payload = w.toByteArray();

        Map<String, Object> m = ObstCodec.decode(payload);
        List<?> polys = (List<?>) m.get("polygons");
        assertEquals(2, polys.size());
        assertEquals(3, ((List<?>) polys.get(0)).size());
        assertEquals(4, ((List<?>) polys.get(1)).size());
        assertArrayEquals(payload, ObstCodec.encode(m));
    }

    @Test
    void trailingDataFallsBackToRaw() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).uint8(1).uint8(1).float16(1f).float16(1f);
        w.uint8(0x55);   // stray trailing byte
        assertNull(ObstCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void obstLayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("obst", obstV1()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("o.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("obst", m.entries.get(0).codec);
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Move a collision point (1.5 -> 2.5, both exact float16) and confirm it survives.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replaceFirst("1\\.5", "2.5");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        Map<String, Object> remodel = ObstCodec.decode(Packer.pack(dir).layers.get(0).data);
        List<?> first = (List<?>) ((List<?>) remodel.get("polygons")).get(0);
        assertEquals(2.5, ((List<?>) first.get(0)).get(0));
    }
}
