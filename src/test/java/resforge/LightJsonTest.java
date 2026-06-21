package resforge;

import resforge.io.MessageWriter;
import resforge.layers.LightCodec;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightJsonTest {

    /** A ver-0 (cpfloat) point light: int8 id, 3 RGBA colours, then an attenuation tag. */
    private static byte[] lightV0() {
        MessageWriter w = new MessageWriter();
        w.uint8(0).int8(7);
        for(double v : new double[]{0.5, 0.25, 0.125, 1.0}) w.cpfloat(v);   // ambient
        for(double v : new double[]{1.0, 0.5, 0.25, 1.0}) w.cpfloat(v);     // diffuse
        for(double v : new double[]{1.0, 1.0, 1.0, 1.0}) w.cpfloat(v);      // specular
        w.uint8(1).cpfloat(0.5).cpfloat(0.0).cpfloat(0.001);                // attenuation
        return w.toByteArray();
    }

    /** A ver-1 (float32) spotlight: int16 id, 3 colours, attenuation + exponent. */
    private static byte[] lightV1Spot() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(300);
        for(float v : new float[]{0.5f, 0.2f, 0f, 1f}) w.float32(v);        // ambient
        for(float v : new float[]{1f, 0.6f, 0.2f, 1f}) w.float32(v);        // diffuse
        for(float v : new float[]{1f, 0.75f, 0.45f, 1f}) w.float32(v);      // specular
        w.uint8(1).float32(1f).float32(0f).float32(0.0015f);               // attenuation
        w.uint8(3).float32(2.5f);                                          // exponent
        return w.toByteArray();
    }

    @Test
    void v0PointLightRoundTripsAndDecodes() {
        byte[] payload = lightV0();
        Map<String, Object> m = LightCodec.decode(payload);
        assertEquals(0L, m.get("version"));
        assertEquals(7L, m.get("id"));
        assertEquals(0.5, ((List<?>) m.get("ambient")).get(0));
        List<?> att = (List<?>) m.get("attenuation");
        assertEquals(0.5, att.get(0));
        assertEquals(0.0, att.get(1));
        assertEquals(0.001, ((Number) att.get(2)).doubleValue(), 1e-9);   // cpfloat is a binary approximation
        assertFalse(m.containsKey("exponent"), "a plain point light has no exponent");

        assertArrayEquals(payload, LightCodec.encode(m));
        assertNotNull(LightCodec.toJsonIfLossless(payload));
    }

    @Test
    void v1SpotlightRoundTrips() {
        byte[] payload = lightV1Spot();
        Map<String, Object> m = LightCodec.decode(payload);
        assertEquals(1L, m.get("version"));
        assertEquals(300L, m.get("id"));
        assertTrue(m.containsKey("attenuation"));
        assertTrue(m.containsKey("exponent"), "a spotlight carries an exponent");
        assertArrayEquals(payload, LightCodec.encode(m));
        assertNotNull(LightCodec.toJsonIfLossless(payload));
    }

    @Test
    void directionalLightHasNoExtras() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(1);
        for(int i = 0; i < 12; i++) w.float32(0.5f);   // amb/dif/spc, no tags
        byte[] payload = w.toByteArray();

        Map<String, Object> m = LightCodec.decode(payload);
        assertFalse(m.containsKey("attenuation"));
        assertFalse(m.containsKey("direction"));
        assertArrayEquals(payload, LightCodec.encode(m));
    }

    @Test
    void unknownVersionFallsBackToRaw() {
        assertNull(LightCodec.toJsonIfLossless(new byte[]{5, 0, 0, 0}));
    }

    @Test
    void unknownDataTagFallsBackToRaw() {
        MessageWriter w = new MessageWriter();
        w.uint8(1).int16(0);
        for(int i = 0; i < 12; i++) w.float32(0f);
        w.uint8(9);                                    // bogus tag
        assertNull(LightCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void editedColourSurvivesUnpackRepack(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("light", lightV1Spot()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("l.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("light", m.entries.get(0).codec);
        assertArrayEquals(original, Packer.pack(dir).serialize(), "unedited light must round-trip");

        // Recolour the diffuse red channel from 1.0 to 0.5 (both exact float32).
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String text = Files.readString(json, StandardCharsets.UTF_8);
        String edited = text.replaceFirst("\"diffuse\"\\s*:\\s*\\[\\s*1\\.0", "\"diffuse\": [\n    0.5");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        Map<String, Object> remodel = LightCodec.decode(Packer.pack(dir).layers.get(0).data);
        assertEquals(0.5, ((List<?>) remodel.get("diffuse")).get(0));
    }
}
