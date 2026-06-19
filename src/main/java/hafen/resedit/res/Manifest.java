package hafen.resedit.res;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The manifest describes an unpacked resource: its version and the ordered list
 * of layers. Each layer references one or more "part" files whose contents,
 * concatenated in order, reconstruct the layer payload exactly.
 *
 * Layer order is significant and must be preserved on repack.
 */
public class Manifest {
    public static final String FILENAME = "manifest.txt";

    public static class Entry {
        public final String name;
        public final List<String> parts;

        public Entry(String name, List<String> parts) {
            this.name = name;
            this.parts = parts;
        }
    }

    public int version;
    public final List<Entry> entries = new ArrayList<>();

    public void write(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# hafen-resedit manifest\n");
        sb.append("# Layer order is significant — do not reorder entries.\n");
        sb.append("res-version: ").append(version).append('\n');
        for(Entry e : entries) {
            sb.append("layer\t").append(e.name).append('\t')
              .append(String.join(",", e.parts)).append('\n');
        }
        Files.write(dir.resolve(FILENAME), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Manifest read(Path dir) throws IOException {
        Manifest m = new Manifest();
        boolean haveVersion = false;
        for(String line : Files.readAllLines(dir.resolve(FILENAME), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if(trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;
            if(trimmed.startsWith("res-version:")) {
                m.version = Integer.parseInt(trimmed.substring("res-version:".length()).strip());
                haveVersion = true;
            } else if(line.startsWith("layer\t")) {
                String[] f = line.split("\t", 3);
                if(f.length != 3)
                    throw new IOException("Malformed layer line: " + line);
                List<String> parts = new ArrayList<>();
                for(String p : f[2].split(",")) {
                    if(!p.isEmpty())
                        parts.add(p);
                }
                m.entries.add(new Entry(f[1], parts));
            } else {
                throw new IOException("Unrecognized manifest line: " + line);
            }
        }
        if(!haveVersion)
            throw new IOException("Manifest missing res-version");
        return m;
    }
}
