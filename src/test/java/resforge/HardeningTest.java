package resforge;

import resforge.io.MessageReader;
import resforge.io.SafeFiles;
import resforge.res.Layer;
import resforge.res.Manifest;
import resforge.res.Packer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hardening against hostile/corrupt input and unsafe writes (Tier-1 review fixes). */
class HardeningTest {

    private static byte[] container(String layerName, int declaredLen, byte[] payload) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(ResContainer.SIGNATURE);
        b.write(7); b.write(0);                         // uint16 LE version = 7
        b.writeBytes(layerName.getBytes(StandardCharsets.UTF_8));
        b.write(0);                                     // NUL terminator
        b.write(declaredLen & 0xff);
        b.write((declaredLen >> 8) & 0xff);
        b.write((declaredLen >> 16) & 0xff);
        b.write((declaredLen >> 24) & 0xff);
        if(payload != null)
            b.writeBytes(payload);
        return b.toByteArray();
    }

    @Test
    void hugeDeclaredLengthIsRejectedNotOom() {
        // 0x7fffffff would overflow pos+n and try to allocate ~2GB.
        byte[] raw = container("image", 0x7fffffff, null);
        assertThrows(IllegalArgumentException.class, () -> ResContainer.parse(raw));
    }

    @Test
    void truncatedLayerIsRejected() {
        byte[] raw = container("x", 100, new byte[]{1, 2, 3, 4, 5});
        assertThrows(IllegalArgumentException.class, () -> ResContainer.parse(raw));
    }

    @Test
    void negativeLayerLengthIsRejected() {
        byte[] raw = container("x", -4, new byte[]{1, 2, 3, 4});
        assertThrows(IllegalArgumentException.class, () -> ResContainer.parse(raw));
    }

    @Test
    void readerRejectsNegativeSkipAndBytes() {
        assertThrows(RuntimeException.class, () -> new MessageReader(new byte[10]).skip(-1));
        assertThrows(RuntimeException.class, () -> new MessageReader(new byte[10]).bytes(-1));
        assertThrows(RuntimeException.class, () -> new MessageReader(new byte[10], 4, -1));
    }

    @Test
    void malformedUtf8LayerNameIsRejected() {
        // Bytes 0xC0 0x80 before the NUL: an invalid (overlong) UTF-8 sequence that
        // a lenient decoder would silently turn into U+FFFD (changing the bytes).
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(ResContainer.SIGNATURE);
        b.write(7); b.write(0);
        b.write(0xC0); b.write(0x80); b.write(0x00);          // bad name + NUL
        b.write(0); b.write(0); b.write(0); b.write(0);       // int32 len = 0
        byte[] bad = b.toByteArray();
        assertThrows(RuntimeException.class, () -> ResContainer.parse(bad));
    }

    @Test
    void manifestRoundTripsDelimiterInLayerName(@TempDir Path tmp) throws Exception {
        ResContainer res = new ResContainer(3);
        res.layers.add(new Layer("we\tird", new byte[]{1, 2, 3}));     // tab in name
        res.layers.add(new Layer("two\nlines", new byte[]{4, 5}));     // newline in name
        res.layers.add(new Layer("back\\slash", new byte[]{6}));       // backslash in name
        byte[] original = res.serialize();

        Path dir = tmp.resolve("weird.resdir");
        Files.createDirectories(dir);
        Unpacker.unpack(ResContainer.parse(original), dir);
        byte[] repacked = Packer.pack(dir).serialize();
        assertArrayEquals(original, repacked, "weird layer names must survive unpack/pack");
    }

    @Test
    void packRejectsPartPathTraversal(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("evil.resdir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(Manifest.FILENAME),
                "res-version: 1\nlayer\tx\t../escape.bin\n");
        Files.writeString(tmp.resolve("escape.bin"), "secret");
        IOException ex = assertThrows(IOException.class, () -> Packer.pack(dir));
        assertTrue(ex.getMessage().toLowerCase().contains("escape"));
    }

    @Test
    void safeFilesWriteReplacesAndLeavesNoTemp(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("out.res");
        SafeFiles.write(target, "ORIGINAL".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("ORIGINAL".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        SafeFiles.write(target, "REPLACED".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("REPLACED".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        try(Stream<Path> s = Files.list(tmp)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().startsWith(".resforge")),
                    "no temp files should be left behind");
        }
    }

    @Test
    void safeFilesWriteLeavesOriginalOnFailure(@TempDir Path tmp) throws Exception {
        // Force the rename to fail by making the target a non-empty directory.
        Path target = tmp.resolve("collide");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "x");

        assertThrows(IOException.class,
                () -> SafeFiles.write(target, "NEW".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Files.isDirectory(target), "original target must be untouched on failure");
        assertEquals("x", Files.readString(target.resolve("keep.txt")));
    }
}
