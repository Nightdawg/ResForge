package resforge;

import resforge.io.Json;
import resforge.io.MessageWriter;
import resforge.layers.ActionCodec;
import resforge.layers.PropsCodec;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropsJsonTest {
    /** Mirrors knarr.res: { "place": ["surface", "map"] } with a top-level list (no trailing T_END). */
    private static byte[] knarrLikeProps() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);                          // version
        w.uint8(2).string("place");          // T_STR key
        w.uint8(8);                          // T_TTOL
        w.uint8(2).string("surface");        // T_STR
        w.uint8(2).string("map");            // T_STR
        w.uint8(0);                          // T_END (closes nested list)
        return w.toByteArray();
    }

    @Test
    void jsonWriteParseRoundTrip() {
        Object v = Json.parse(Json.write(Map.of()));
        assertTrue(((Map<?, ?>) v).isEmpty());

        String src = "{\n  \"a\": \"x\\n\\\"y\",\n  \"n\": 42,\n  \"f\": 1.5,\n"
                + "  \"b\": true,\n  \"z\": null,\n  \"list\": [1, 2, [\"deep\"]]\n}\n";
        Object parsed = Json.parse(src);
        // Re-serialize and re-parse must be stable.
        Object reparsed = Json.parse(Json.write(parsed));
        assertEquals(Json.write(parsed), Json.write(reparsed));

        Map<?, ?> m = (Map<?, ?>) parsed;
        assertEquals("x\n\"y", m.get("a"));
        assertEquals(42L, m.get("n"));
        assertEquals(1.5, m.get("f"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertNull(m.get("z"));
        assertEquals(List.of(1L, 2L, List.of("deep")), m.get("list"));
    }

    @Test
    void propsDecodeEncodeIsLossless() {
        byte[] payload = knarrLikeProps();
        Map<String, Object> model = PropsCodec.decode(payload);
        assertEquals(1L, model.get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) model.get("props");
        assertEquals(Map.of("list", List.of("surface", "map")), props.get("place"));
        assertArrayEquals(payload, PropsCodec.encode(model));

        String json = PropsCodec.toJsonIfLossless(payload);
        assertNotNull(json, "knarr-like props must be exposable as lossless JSON");
    }

    @Test
    void propsIntegerValuesRoundTripCanonically() {
        // Values that addtto would canonicalize as u8 / u16 / i8 / i16 / i32.
        MessageWriter w = new MessageWriter();
        w.uint8(1);
        w.uint8(2).string("a"); w.uint8(4).uint8(200);        // T_UINT8 200
        w.uint8(2).string("b"); w.uint8(5).uint16(50000);     // T_UINT16 50000
        w.uint8(2).string("c"); w.uint8(9).int8(-5);          // T_INT8 -5
        w.uint8(2).string("d"); w.uint8(10).int16(-1000);     // T_INT16 -1000
        w.uint8(2).string("e"); w.uint8(1).int32(123456);     // T_INT 123456
        byte[] payload = w.toByteArray();
        assertArrayEquals(payload, PropsCodec.encode(PropsCodec.decode(payload)));
        assertNotNull(PropsCodec.toJsonIfLossless(payload));
    }

    @Test
    void unsupportedPropsFallsBackToRaw() {
        // A float16 value (T_FLOAT16) is deliberately not modelled -> must stay raw.
        MessageWriter w = new MessageWriter();
        w.uint8(1);
        w.uint8(2).string("h");
        w.uint8(22).uint16(0x3c00);   // T_FLOAT16 (half 1.0)
        assertNull(PropsCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void pathologicallyDeepTtoNestingIsRejectedCleanly() {
        // A key whose value is thousands of nested tto lists (each a lone T_TTOL tag,
        // never closed). Without the depth guard this StackOverflowErrors during decode
        // — an Error that escapes the lossless-or-raw catch(RuntimeException). It must
        // now fail with a clear RuntimeException, and simply stay raw.
        MessageWriter w = new MessageWriter();
        w.uint8(1);                 // version
        w.uint8(2).string("k");     // key
        for(int i = 0; i < 5000; i++)
            w.uint8(8);             // T_TTOL: open a nested list, never closed
        byte[] payload = w.toByteArray();
        assertThrows(RuntimeException.class, () -> PropsCodec.decode(payload));
        assertNull(PropsCodec.toJsonIfLossless(payload), "a too-deep props layer stays raw");
    }

    @Test
    void propsLayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(90);
        res.layers.add(new Layer("props", knarrLikeProps()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("p.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("props", m.entries.get(0).codec);
        assertTrue(m.entries.get(0).parts.get(0).endsWith(".json"));

        // Untouched repack is byte-identical.
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Edit the JSON (rename "map" -> "ocean") and confirm the change survives a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replace("\"map\"", "\"ocean\"");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        byte[] propsData = Packer.pack(dir).layers.get(0).data;
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) PropsCodec.decode(propsData).get("props");
        assertEquals(Map.of("list", List.of("surface", "ocean")), props.get("place"));
    }

    /** A props layer carrying the newly-supported non-native tto types. */
    private static byte[] richProps() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);                                              // version
        w.uint8(2).string("pos");
        w.uint8(3).int32(10).int32(-20);                         // T_COORD
        w.uint8(2).string("col");
        w.uint8(6).uint8(204).uint8(204).uint8(204).uint8(255);  // T_COLOR
        w.uint8(2).string("fcol");
        w.uint8(7).float32(0.5f).float32(0.25f).float32(0f).float32(1f); // T_FCOLOR
        w.uint8(2).string("scale");
        w.uint8(15).float32(1.5f);                               // T_FLOAT32
        w.uint8(2).string("blob");
        w.uint8(14).uint8(3).bytes(new byte[]{1, 2, 3});         // T_BYTES (short form)
        w.uint8(2).string("id");
        w.uint8(35).uint16(42);                                  // T_RESID
        w.uint8(2).string("ref");
        w.uint8(34).string("gfx/terobjs/foo").uint16(7);         // T_RESSPEC
        return w.toByteArray();
    }

    @Test
    void propsRichTtoTypesRoundTrip() {
        byte[] payload = richProps();
        Map<String, Object> model = PropsCodec.decode(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) model.get("props");
        assertEquals(Map.of("coord", List.of(10L, -20L)), p.get("pos"));
        assertEquals(Map.of("color", List.of(204L, 204L, 204L, 255L)), p.get("col"));
        assertEquals(Map.of("fcolor", List.of(0.5, 0.25, 0.0, 1.0)), p.get("fcol"));
        assertEquals(Map.of("f32", 1.5), p.get("scale"));
        assertEquals(Map.of("bytes", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})), p.get("blob"));
        assertEquals(Map.of("resid", 42L), p.get("id"));
        assertEquals(Map.of("resspec", List.of("gfx/terobjs/foo", 7L)), p.get("ref"));

        assertArrayEquals(payload, PropsCodec.encode(model));
        assertNotNull(PropsCodec.toJsonIfLossless(payload), "rich props must be exposable as lossless JSON");
    }

    @Test
    void propsLongByteBlobRoundTrips() {
        // A blob >= 128 bytes exercises the 0x80 + int32 length-prefix path.
        byte[] blob = new byte[200];
        for(int i = 0; i < blob.length; i++)
            blob[i] = (byte) i;
        MessageWriter w = new MessageWriter();
        w.uint8(1);
        w.uint8(2).string("data");
        w.uint8(14).uint8(0x80).int32(blob.length).bytes(blob);  // T_BYTES (long form)
        byte[] payload = w.toByteArray();
        assertArrayEquals(payload, PropsCodec.encode(PropsCodec.decode(payload)));
        assertNotNull(PropsCodec.toJsonIfLossless(payload));
    }

    @Test
    void propsNestedMapWithTaggedScalarsRoundTrips() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);
        w.uint8(2).string("opts");
        w.uint8(32);                                          // T_MAP
        w.uint8(2).string("n"); w.uint8(4).uint8(7);          // "n" -> u8 7
        w.uint8(2).string("v"); w.uint8(15).float32(2.5f);    // "v" -> f32 2.5
        w.uint8(0);                                           // T_END (close map)
        byte[] payload = w.toByteArray();
        Map<String, Object> model = PropsCodec.decode(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) model.get("props");
        assertEquals(Map.of("map", Map.of("n", Map.of("u8", 7L), "v", Map.of("f32", 2.5))), p.get("opts"));
        assertArrayEquals(payload, PropsCodec.encode(model));
        assertNotNull(PropsCodec.toJsonIfLossless(payload));
    }

    @Test
    void propsCoordEditSurvivesRepack(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(91);
        res.layers.add(new Layer("props", richProps()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("rich.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("props", m.entries.get(0).codec);
        assertTrue(m.entries.get(0).parts.get(0).endsWith(".json"));
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Move the coord from [10, -20] to [11, -20] and confirm it survives a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8).replace("10,", "11,");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        byte[] propsData = Packer.pack(dir).layers.get(0).data;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) PropsCodec.decode(propsData).get("props");
        assertEquals(Map.of("coord", List.of(11L, -20L)), p.get("pos"));
    }

    /** Mirrors a real action record: parent + ver + name + prereq + hotkey + ad[]. */
    private static byte[] actionLayer() {
        MessageWriter w = new MessageWriter();
        w.string("customclient/menugrid/CustomClientExtras"); // parent
        w.uint16(4);                                          // parentVer
        w.string("| Bots |");                                 // name
        w.string("");                                         // prereq
        w.uint16(66);                                         // hotkey 'B'
        w.uint16(0);                                          // adCount
        return w.toByteArray();
    }

    @Test
    void actionDecodeEncodeIsLossless() {
        byte[] payload = actionLayer();
        Map<String, Object> m = ActionCodec.decode(payload);
        assertEquals("| Bots |", m.get("name"));
        assertEquals(66L, m.get("hotkey"));
        assertEquals(4L, m.get("parentVer"));
        assertEquals(List.of(), m.get("ad"));
        assertArrayEquals(payload, ActionCodec.encode(m));
        assertNotNull(ActionCodec.toJsonIfLossless(payload));
    }

    @Test
    void actionLayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(50);
        res.layers.add(new Layer("action", actionLayer()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("a.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("action", m.entries.get(0).codec);
        assertTrue(m.entries.get(0).parts.get(0).endsWith(".json"));
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Rebind the hotkey 'B'(66) -> 'C'(67) and confirm it survives a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8)
                .replace("\"hotkey\": 66", "\"hotkey\": 67");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        byte[] actionData = Packer.pack(dir).layers.get(0).data;
        assertEquals(67L, ActionCodec.decode(actionData).get("hotkey"));
    }
}
