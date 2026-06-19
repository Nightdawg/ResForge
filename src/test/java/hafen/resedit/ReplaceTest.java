package hafen.resedit;

import hafen.resedit.io.MessageWriter;
import hafen.resedit.layers.ActionCodec;
import hafen.resedit.layers.AudioInfo;
import hafen.resedit.layers.ImageInfo;
import hafen.resedit.layers.TexInfo;
import hafen.resedit.res.Layer;
import hafen.resedit.res.Replacer;
import hafen.resedit.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaceTest {
    private static byte[] tinyPng() {
        return new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
        };
    }

    private static byte[] imageLayer() {
        MessageWriter w = new MessageWriter();
        w.uint8(0).int8(0).int16(0).uint8(0).int16(42).int16(3).int16(-4).bytes(tinyPng());
        return w.toByteArray();
    }

    private static byte[] texLayer() {
        MessageWriter w = new MessageWriter();
        w.int16(7).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(tinyPng().length).bytes(tinyPng());
        w.uint8(1).uint8(0);
        return w.toByteArray();
    }

    private static byte[] audioLayer() {
        MessageWriter w = new MessageWriter();
        w.uint8(2).string("cl").uint16(1000).bytes(new byte[]{0x4F, 0x67, 0x67, 0x53, 1, 2, 3});
        return w.toByteArray();
    }

    private static byte[] biggerPng() {
        byte[] base = tinyPng();
        byte[] out = Arrays.copyOf(base, base.length + 40);
        return out; // still PNG-magic prefixed
    }

    @Test
    void replaceImageKeepsHeaderSwapsPicture() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tooltip", "hi".getBytes(StandardCharsets.UTF_8)));
        res.layers.add(new Layer("image", imageLayer()));

        Replacer.replace(res, "image", biggerPng());
        byte[] data = res.layers.get(1).data;
        ImageInfo ii = ImageInfo.parse(data);
        // Header preserved (id=42), and the image part equals the new PNG.
        assertEquals(42, ii.id);
        assertArrayEquals(biggerPng(), Arrays.copyOfRange(data, ii.imageOffset, data.length));
        // Other layer untouched.
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), res.layers.get(0).data);
    }

    @Test
    void replaceTexRecomputesLength() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", texLayer()));
        Replacer.replace(res, "tex", biggerPng());
        TexInfo ti = TexInfo.parse(res.layers.get(0).data);
        assertTrue(ti.found);
        assertEquals(biggerPng().length, ti.imageLen);
    }

    @Test
    void replaceAudioRequiresOgg() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("audio2", audioLayer()));
        // Wrong format rejected.
        assertThrows(Replacer.ReplaceException.class,
                () -> Replacer.replace(res, "audio2", tinyPng()));
        // Correct Ogg accepted.
        byte[] ogg = new byte[]{0x4F, 0x67, 0x67, 0x53, 9, 9, 9, 9};
        Replacer.replace(res, "audio2", ogg);
        AudioInfo ai = AudioInfo.parse(res.layers.get(0).data);
        assertArrayEquals(ogg, Arrays.copyOfRange(res.layers.get(0).data, ai.audioOffset, res.layers.get(0).data.length));
    }

    @Test
    void replaceActionFromJson() {
        ResContainer res = new ResContainer(7);
        MessageWriter w = new MessageWriter();
        w.string("p").uint16(1).string("Old").string("").uint16(65).uint16(0);
        res.layers.add(new Layer("action", w.toByteArray()));

        String json = "{\"parent\":\"p\",\"parentVer\":1,\"name\":\"New\","
                + "\"prereq\":\"\",\"hotkey\":90,\"ad\":[]}";
        Replacer.replace(res, "action", json.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> m = ActionCodec.decode(res.layers.get(0).data);
        assertEquals("New", m.get("name"));
        assertEquals(90L, m.get("hotkey"));
    }

    @Test
    void selectorByOccurrenceAndIndex() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("image", imageLayer()));
        res.layers.add(new Layer("image", imageLayer()));

        Replacer.replace(res, "image#1", biggerPng());
        assertEquals(imageLayer().length, res.layers.get(0).data.length);       // first untouched
        assertTrue(res.layers.get(1).data.length > imageLayer().length);        // second grew

        Replacer.replace(res, "#0", biggerPng());
        assertTrue(res.layers.get(0).data.length > imageLayer().length);
    }

    @Test
    void unknownSelectorThrows() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("neg", new byte[]{1, 2, 3}));
        assertThrows(Replacer.ReplaceException.class,
                () -> Replacer.replace(res, "image", tinyPng()));
        // 'neg' is not a replaceable layer type.
        assertThrows(Replacer.ReplaceException.class,
                () -> Replacer.replace(res, "neg", tinyPng()));
    }
}
