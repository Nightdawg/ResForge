package resforge.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveAsTest {
    @Test
    void successfulSaveChangesActiveDestination(@TempDir Path temp) {
        Path oldFile = temp.resolve("old.res");
        Path destination = temp.resolve("new.res");
        byte[] data = {1, 2, 3};
        List<String> errors = new ArrayList<>();

        Path activeFile = oldFile;
        if(ResForgeFrame.writeRes(destination, data, errors::add))
            activeFile = destination;

        assertEquals(destination, activeFile);
        assertArrayEquals(data, read(destination));
        assertTrue(errors.isEmpty());
    }

    @Test
    void failedSaveKeepsPreviousActiveDestination(@TempDir Path temp) throws Exception {
        Path oldFile = temp.resolve("old.res");
        Path destination = temp.resolve("destination.res");
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("keep.txt"), "unchanged");
        List<String> errors = new ArrayList<>();

        Path activeFile = oldFile;
        if(ResForgeFrame.writeRes(destination, new byte[]{1, 2, 3}, errors::add))
            activeFile = destination;

        assertEquals(oldFile, activeFile);
        assertTrue(Files.isDirectory(destination));
        assertEquals("unchanged", Files.readString(destination.resolve("keep.txt")));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Could not save:"));
        assertFalse(errors.get(0).isBlank());
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch(Exception e) {
            throw new AssertionError(e);
        }
    }
}
