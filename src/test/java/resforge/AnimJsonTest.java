package resforge;

import resforge.io.MessageWriter;
import resforge.layers.AnimCodec;
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

class AnimJsonTest {
    /** Mirrors cleave.res: id=-1, delay=100, 8 frames with image ids 128..135. */
    private static byte[] cleaveLikeAnim() {
        MessageWriter w = new MessageWriter();
        w.int16(-1);          // id
        w.uint16(100);        // delay (ms)
        w.uint16(8);          // frame count
        for(int i = 128; i <= 135; i++)
            w.int16(i);
        return w.toByteArray();
    }

    @Test
    void animDecodeEncodeIsLossless() {
        byte[] payload = cleaveLikeAnim();
        Map<String, Object> m = AnimCodec.decode(payload);
        assertEquals(-1L, m.get("id"));
        assertEquals(100L, m.get("delay"));
        assertEquals(List.of(128L, 129L, 130L, 131L, 132L, 133L, 134L, 135L), m.get("frames"));
        assertArrayEquals(payload, AnimCodec.encode(m));
        assertNotNull(AnimCodec.toJsonIfLossless(payload));
    }

    @Test
    void trailingDataFallsBackToRaw() {
        MessageWriter w = new MessageWriter();
        w.int16(-1).uint16(100).uint16(1).int16(128);
        w.uint8(0xFF);   // stray trailing byte
        assertNull(AnimCodec.toJsonIfLossless(w.toByteArray()));
    }

    @Test
    void animLayerRoundTripsAndIsEditable(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(1);
        res.layers.add(new Layer("anim", cleaveLikeAnim()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("a.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals("anim", m.entries.get(0).codec);
        assertTrue(m.entries.get(0).parts.get(0).endsWith(".json"));

        // Untouched repack is byte-identical.
        assertArrayEquals(original, Packer.pack(dir).serialize());

        // Speed it up (100 -> 50) and drop the last frame; confirm both survive a repack.
        Path json = dir.resolve(m.entries.get(0).parts.get(0));
        String edited = Files.readString(json, StandardCharsets.UTF_8)
                .replace("\"delay\": 100", "\"delay\": 50")
                .replace(",\n    135", "");
        Files.writeString(json, edited, StandardCharsets.UTF_8);

        Map<String, Object> remodel = AnimCodec.decode(Packer.pack(dir).layers.get(0).data);
        assertEquals(50L, remodel.get("delay"));
        assertEquals(List.of(128L, 129L, 130L, 131L, 132L, 133L, 134L), remodel.get("frames"));
    }
}
