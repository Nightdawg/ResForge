package resforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import resforge.res.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {
    @Test
    void writeAtomicallyReplacesExistingManifestAndLeavesNoTemp(@TempDir Path temp)
            throws Exception {
        Files.writeString(temp.resolve(Manifest.FILENAME), "old");
        Manifest manifest = manifest();

        manifest.write(temp);

        Manifest read = Manifest.read(temp);
        assertEquals(7, read.version);
        assertEquals(1, read.entries.size());
        assertEquals("image", read.entries.get(0).name);
        try(Stream<Path> files = Files.list(temp)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".resforge")));
        }
    }

    @Test
    void failedPublicationPreservesExistingTarget(@TempDir Path temp) throws Exception {
        Path target = temp.resolve(Manifest.FILENAME);
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "unchanged");

        assertThrows(IOException.class, () -> manifest().write(temp));

        assertTrue(Files.isDirectory(target));
        assertEquals("unchanged", Files.readString(target.resolve("keep.txt")));
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.version = 7;
        manifest.entries.add(new Manifest.Entry("image",
                List.of("layers/000_image.bin")));
        return manifest;
    }
}
