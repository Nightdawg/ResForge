package hafen.resedit.res;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Repacks an unpacked folder back into a .res file by concatenating each
 * layer's part files (in manifest order) into its payload.
 */
public class Packer {
    public static ResContainer pack(Path dir) throws IOException {
        Manifest manifest = Manifest.read(dir);
        ResContainer res = new ResContainer(manifest.version);
        for(Manifest.Entry e : manifest.entries) {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            for(String part : e.parts) {
                Path p = dir.resolve(part);
                if(!Files.exists(p))
                    throw new IOException("Missing part file referenced by manifest: " + part);
                payload.writeBytes(Files.readAllBytes(p));
            }
            res.layers.add(new Layer(e.name, payload.toByteArray()));
        }
        return res;
    }
}
