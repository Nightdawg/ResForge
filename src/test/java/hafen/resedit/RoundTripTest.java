package hafen.resedit;

import hafen.resedit.io.MessageWriter;
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
}
