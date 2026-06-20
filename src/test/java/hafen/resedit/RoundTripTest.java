package hafen.resedit;

import hafen.resedit.io.MessageWriter;
import hafen.resedit.layers.TexInfo;
import hafen.resedit.res.Layer;
import hafen.resedit.res.Manifest;
import hafen.resedit.res.Packer;
import hafen.resedit.res.ResContainer;
import hafen.resedit.res.Unpacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundTripTest {
    /** A minimal valid 1x1 PNG. */
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

    /** Builds an image-layer payload: simple header (ver<128) + PNG. */
    private static byte[] imageLayer() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);        // ver (low byte of z)
        w.int8(0);         // high byte of z  -> z = 0
        w.int16(0);        // subz
        w.uint8(0);        // flags (no info)
        w.int16(42);       // id
        w.int16(3);        // off.x
        w.int16(-4);       // off.y
        w.bytes(tinyPng());
        return w.toByteArray();
    }

    /** Builds a tex-layer payload: header + inline color image (PNG) + a mipmap part. */
    private static byte[] texLayer() {
        MessageWriter w = new MessageWriter();
        w.int16(7);          // id
        w.uint16(0);         // off.x
        w.uint16(0);         // off.y
        w.uint16(64);        // sz.x
        w.uint16(64);        // sz.y
        w.uint8(0);          // part tag: fl=0, t=0 (color image)
        byte[] png = tinyPng();
        w.int32(png.length); // embedded image length
        w.bytes(png);
        w.uint8(1);          // part tag: fl=0, t=1 (mipmap)
        w.uint8(0);          // mipmap value
        return w.toByteArray();
    }

    /** Builds an audio2-layer payload: ver2 header + an (OggS-prefixed) audio blob. */
    private static byte[] audioLayer() {
        MessageWriter w = new MessageWriter();
        w.uint8(2);             // ver
        w.string("cl");         // id
        w.uint16(1000);         // vol -> bvol 1.0
        w.bytes(new byte[]{0x4F, 0x67, 0x67, 0x53, 0x00, 0x02, 0x00, 0x00, 0x01, 0x02});
        return w.toByteArray();
    }

    /** Builds a font-layer payload: ver1/type0 header + a (TTF-magic) program blob. */
    private static byte[] fontLayer() {
        MessageWriter w = new MessageWriter();
        w.uint8(1);             // ver
        w.uint8(0);             // type (TrueType)
        w.bytes(new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x10, 0x20});
        return w.toByteArray();
    }

    private static ResContainer sample() {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("image", imageLayer()));
        res.layers.add(new Layer("tooltip", "A fine horse".getBytes(StandardCharsets.UTF_8)));
        res.layers.add(new Layer("pagina", "Some page text".getBytes(StandardCharsets.UTF_8)));
        res.layers.add(new Layer("neg", new byte[]{1, 2, 3, 4, 5, 0, (byte) 0x80, (byte) 0xFF}));
        res.layers.add(new Layer("anim", new byte[0]));
        return res;
    }

    @Test
    void containerSerializeParseIsLossless() {
        byte[] raw = sample().serialize();
        ResContainer reparsed = ResContainer.parse(raw);
        assertArrayEquals(raw, reparsed.serialize());
    }

    @Test
    void layerAddDeleteReorderSerializeInOrder() {
        // Mirrors what the GUI does to res.layers, then checks the file round-trips.
        ResContainer res = new ResContainer(5);
        res.layers.add(new Layer("a", new byte[]{1}));
        res.layers.add(new Layer("b", new byte[]{2}));
        res.layers.add(new Layer("c", new byte[]{3}));
        // reorder: move "c" to the front
        res.layers.add(0, res.layers.remove(2));
        // add a new layer after the first
        res.layers.add(1, new Layer("new", new byte[]{9, 9}));
        // delete "b"
        res.layers.removeIf(l -> l.name.equals("b"));

        ResContainer re = ResContainer.parse(res.serialize());
        assertEquals(List.of("c", "new", "a"), re.layers.stream().map(l -> l.name).toList());
        assertArrayEquals(new byte[]{9, 9}, re.layers.get(1).data);
        assertEquals(5, re.version);
    }

    @Test
    void unpackPackIsByteIdentical(@TempDir Path tmp) throws Exception {
        byte[] original = sample().serialize();

        Path dir = tmp.resolve("sample.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals(5, m.entries.size());

        // The image must have been split into header + png parts.
        assertEquals(2, m.entries.get(0).parts.size());
        assertTrue(m.entries.get(0).parts.get(1).endsWith(".png"));
        // Text layers exposed as .txt
        assertTrue(m.entries.get(1).parts.get(0).endsWith(".txt"));

        byte[] repacked = Packer.pack(dir).serialize();
        assertArrayEquals(original, repacked, "repacked .res must be byte-identical");
    }

    @Test
    void editingExtractedPngChangesOnlyImage(@TempDir Path tmp) throws Exception {
        byte[] original = sample().serialize();
        Path dir = tmp.resolve("sample.resdir");
        Files.createDirectories(dir);
        Unpacker.unpack(ResContainer.parse(original), dir);

        // Replace the PNG part with a different (still valid-looking) image.
        Path png = dir.resolve(Manifest.read(dir).entries.get(0).parts.get(1));
        ByteArrayOutputStream bigger = new ByteArrayOutputStream();
        bigger.writeBytes(tinyPng());
        bigger.writeBytes(new byte[16]);
        Files.write(png, bigger.toByteArray());

        ResContainer repacked = Packer.pack(dir);
        // Image layer grew by 16 bytes; other layers untouched.
        assertEquals(imageLayer().length + 16, repacked.layers.get(0).data.length);
        assertArrayEquals("A fine horse".getBytes(StandardCharsets.UTF_8), repacked.layers.get(1).data);
    }

    @Test
    void texLayerRoundTripIsByteIdentical(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", texLayer()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("tex.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);

        // tex is split into pre/image/post via the "tex" codec.
        assertEquals("tex", m.entries.get(0).codec);
        assertEquals(3, m.entries.get(0).parts.size());
        assertTrue(m.entries.get(0).parts.get(1).endsWith(".png"));

        byte[] repacked = Packer.pack(dir).serialize();
        assertArrayEquals(original, repacked, "untouched tex must repack byte-identically");
    }

    @Test
    void audioLayerSplitsAndRoundTrips(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("audio2", audioLayer()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("aud.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals(2, m.entries.get(0).parts.size());
        assertTrue(m.entries.get(0).parts.get(1).endsWith(".ogg"));

        assertArrayEquals(original, Packer.pack(dir).serialize(), "untouched audio must repack byte-identically");

        // Swapping the .ogg for a larger one grows only the audio layer.
        Path ogg = dir.resolve(m.entries.get(0).parts.get(1));
        ByteArrayOutputStream bigger = new ByteArrayOutputStream();
        bigger.writeBytes(Files.readAllBytes(ogg));
        bigger.writeBytes(new byte[64]);
        Files.write(ogg, bigger.toByteArray());
        assertEquals(audioLayer().length + 64, Packer.pack(dir).layers.get(0).data.length);
    }

    @Test
    void fontLayerSplitsAndRoundTrips(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("font", fontLayer()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("font.resdir");
        Files.createDirectories(dir);
        Manifest m = Unpacker.unpack(ResContainer.parse(original), dir);
        assertEquals(2, m.entries.get(0).parts.size());
        assertTrue(m.entries.get(0).parts.get(1).endsWith(".ttf"));
        assertArrayEquals(original, Packer.pack(dir).serialize(), "untouched font must repack byte-identically");
    }

    @Test
    void editingTexImageRecomputesEmbeddedLength(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(7);
        res.layers.add(new Layer("tex", texLayer()));
        byte[] original = res.serialize();

        Path dir = tmp.resolve("tex.resdir");
        Files.createDirectories(dir);
        Unpacker.unpack(ResContainer.parse(original), dir);

        // Swap the texture for a larger image; the embedded int32 length must follow.
        String imgPart = Manifest.read(dir).entries.get(0).parts.get(1);
        ByteArrayOutputStream bigger = new ByteArrayOutputStream();
        bigger.writeBytes(tinyPng());
        bigger.writeBytes(new byte[32]);
        Files.write(dir.resolve(imgPart), bigger.toByteArray());

        byte[] texData = Packer.pack(dir).layers.get(0).data;
        TexInfo ti = TexInfo.parse(texData);
        assertTrue(ti.found, "color image must still be locatable after swap");
        assertEquals(bigger.size(), ti.imageLen, "embedded length must match the new image size");
        // The trailing mipmap part (2 bytes) must be preserved after the image.
        assertEquals(texData.length, ti.imageOffset + ti.imageLen + 2);
    }
}
