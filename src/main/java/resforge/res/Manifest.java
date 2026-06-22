package resforge.res;

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
        public final String codec;

        public Entry(String name, List<String> parts) {
            this(name, parts, "raw");
        }

        public Entry(String name, List<String> parts, String codec) {
            this.name = name;
            this.parts = parts;
            this.codec = (codec == null || codec.isEmpty()) ? "raw" : codec;
        }
    }

    public int version;
    public final List<Entry> entries = new ArrayList<>();

    public void write(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ResForge manifest\n");
        sb.append("# Layer order is significant — do not reorder entries.\n");
        sb.append("res-version: ").append(version).append('\n');
        for(Entry e : entries) {
            sb.append("layer\t").append(esc(e.name)).append('\t')
              .append(String.join(",", e.parts));
            if(!e.codec.equals("raw"))
                sb.append('\t').append(esc(e.codec));
            sb.append('\n');
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
                String vs = trimmed.substring("res-version:".length()).strip();
                int v;
                try {
                    v = Integer.parseInt(vs);
                } catch(NumberFormatException e) {
                    throw new IOException("Invalid res-version '" + vs + "' in manifest");
                }
                if(v < 0 || v > 0xffff)
                    throw new IOException("res-version out of range [0, 65535]: " + v);
                m.version = v;
                haveVersion = true;
            } else if(line.startsWith("layer\t")) {
                String[] f = line.split("\t", 4);
                if(f.length < 3)
                    throw new IOException("Malformed layer line: " + line);
                List<String> parts = new ArrayList<>();
                for(String p : f[2].split(",")) {
                    if(!p.isEmpty())
                        parts.add(p);
                }
                String codec = (f.length >= 4) ? unesc(f[3].strip()) : "raw";
                m.entries.add(new Entry(unesc(f[1]), parts, codec));
            } else {
                throw new IOException("Unrecognized manifest line: " + line);
            }
        }
        if(!haveVersion)
            throw new IOException("Manifest missing res-version");
        return m;
    }

    /** Backslash-escapes the manifest field delimiters so a layer name containing
     *  a tab/newline/CR (permitted in the container format) still round-trips. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch(c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t");  break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                default:   b.append(c);
            }
        }
        return b.toString();
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if(c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch(n) {
                    case '\\': b.append('\\'); break;
                    case 't':  b.append('\t'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    default:   b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
