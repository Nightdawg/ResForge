package resforge.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File writes that never leave a half-written or truncated output in place. The
 * data is written to a sibling temp file and then moved over the target with an
 * atomic rename (falling back to a plain replace where the filesystem can't do
 * an atomic move). A crash, full disk, or I/O error mid-write therefore leaves
 * the original file untouched instead of destroying it.
 */
public final class SafeFiles {
    private SafeFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path abs = target.toAbsolutePath();
        Path dir = abs.getParent();
        if(dir == null)
            throw new IOException("target has no parent directory: " + target);
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".resforge", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch(AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException | RuntimeException | Error e) {
            try {
                Files.deleteIfExists(tmp);
            } catch(IOException ignored) {
                /* best effort cleanup */
            }
            throw e;
        }
    }
}
