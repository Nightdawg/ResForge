package resforge;

import resforge.io.MessageWriter;
import resforge.layers.NegCodec;
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

class NegJsonTest {
    /** Byte-for-byte the real cleave.res neg: center (15,50), bounds, no endpoints. */
    private static byte[] cleaveLikeNeg() {
        MessageWriter w = new MessageWriter();
        w.int16(15).int16(50);                          // center
        w.int16(0).int16(0);                            // bounds[0]
        w.int16(0).int16(0);                            // bounds[1]
        w.int16(583).int16(65);                         // bounds[2]
        w.uint8(0);                                     // 0 endpoint groups
        return w.toByteArray();
    }

    @Test
    void negDecodeEncodeIsLosslessAndMatchesRealShape() {
        byte[] payload = cleaveLikeNeg();
        // Confirms our fixture equals the actual 17-byte cleave.res neg layer.
        assertEquals(17, payload.length);

        Map<String, Object> m = NegCodec.decode(payload);
        assertEquals(List.of(15L, 50L), m.get("center"));
        assertEquals(List.of(List.of(0L, 0L), List.of(0L, 0L), List.of(583L, 65L)), m.get("bounds"));
        assertEquals(List.of(), m.get("endpoints"));
        assertArrayEquals(payload, NegCodec.encode(m));
        assertNotNull(NegCodec.toJsonIfLossless(payload));
    }

    /** Exercises the endpoint-group path (en>0), which the current samples don't have. */
    @Test
    void negWithEndpointGroupsRoundTrips() {
        MessageWriter w = new MessageWriter();
        w.int16(1).int16(2);                            // center
        w.int16(0).int16(0).int16(0).int16(0).int16(0).int16(0);  // bounds (3 zero coords)
        w.uint8(2);                                     // 2 endpoint groups
        w.uint8(3).uint16(2).int16(10).int16(20).int16(30).int16(40);  // id 3: (10,20),(30,40)
        w.uint8(5).uint16(1).int16(-7).int16(8);                       // id 5: (-7,8)
        byte[] payload = w.toByteArray();

        Map<String, Object> m = NegCodec.decode(payload);
        List<?> eps = (List<?>) m.get("endpoints");
        assertEquals(2, eps.size());
        Map<?, ?> g0 = (Map<?, ?>) eps.get(0);
        assertEquals(3L, g0.get("id"));
        assertEquals(List.of(List.of(10L, 20L), List.of(30L, 40L)), g0.get("coords"));
        Map<?, ?> g1 = (Map<?, ?>) eps.get(1);
        assertEquals(5L, g1.get("id"));
        assertEquals(List.of(List.of(-7L, 8L)), g1.get("coords"));

        assertArrayEquals(payload, NegCodec.encode(m));
        assertNotNull(NegCodec.toJsonIfLossless(payload));
    }

    @Test
    void trailingDataFallsBackToRaw() {
        MessageWriter w = new MessageWriter();
        w.int16(1).int16(2);
        w.int16(0).int16(0).int16(0).int16(0).int16(0).int16(0);
        w.uint8(0);
        w.uint8(0xAB);   // stray trailing byte
        assertNull(NegCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void negLayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        MessageWriter w = new MessageWriter();
        w.int16(1).int16(2);
        w.int16(0).int16(0).int16(0).int16(0).int16(0).int16(0);
        w.uint8(1).uint8(5).uint16(1).int16(-7).int16(8);   // one group, id=5
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("neg", w.toByteArray()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("n.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("neg", m.entries.get(0).codec);
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Rebind the endpoint group's id 5 -> 6 and confirm it survives a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replace("\"id\": 5", "\"id\": 6");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        Map<String, Object> remodel = NegCodec.decode(Packer.pack(dir).layers.get(0).data);
        Map<?, ?> g = (Map<?, ?>) ((List<?>) remodel.get("endpoints")).get(0);
        assertEquals(6L, g.get("id"));
    }
}
