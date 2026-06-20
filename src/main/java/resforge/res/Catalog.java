package resforge.res;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Scans a folder of {@code .res} files and reports, per file and in aggregate,
 * which editable asset kinds each one contains (icon, texture, sound, font,
 * music, keybind, props, text, 3D model). A quick way to find what is moddable
 * across a large resource set.
 */
public class Catalog {
    /** Layer name -> friendly editable kind. Order here is the display order. */
    private static final Map<String, String> KIND = new LinkedHashMap<>();
    static {
        KIND.put("image", "icon");
        KIND.put("tex", "texture");
        KIND.put("audio2", "sound");
        KIND.put("font", "font");
        KIND.put("midi", "music");
        KIND.put("anim", "animation");
        KIND.put("action", "keybind");
        KIND.put("props", "props");
        KIND.put("mat2", "material");
        KIND.put("tooltip", "text");
        KIND.put("pagina", "text");
        KIND.put("neg", "hitbox");
        KIND.put("vbuf2", "3D-model");
        KIND.put("mesh", "3D-model");
    }

    public static void run(Path target, PrintStream out) throws IOException {
        List<Path> files = collect(target);
        if(files.isEmpty()) {
            out.printf("No .res files found under %s%n", target);
            return;
        }
        Map<String, Integer> agg = new TreeMap<>();
        int readable = 0;
        for(Path f : files) {
            String rel = Files.isDirectory(target) ? target.relativize(f).toString() : f.getFileName().toString();
            Map<String, Integer> kinds = new LinkedHashMap<>();
            try {
                ResContainer res = ResContainer.parse(Files.readAllBytes(f));
                readable++;
                for(Layer l : res.layers) {
                    String k = KIND.get(l.name);
                    if(k != null)
                        kinds.merge(k, 1, Integer::sum);
                }
                for(String k : kinds.keySet())
                    agg.merge(k, 1, Integer::sum);
                out.printf("%-52s %s%n", rel, format(kinds));
            } catch(RuntimeException e) {
                out.printf("%-52s (not a resource)%n", rel);
            }
        }
        out.println();
        out.printf("%d .res file(s), %d readable%n", files.size(), readable);
        out.println("Files containing each editable kind:");
        agg.entrySet().stream()
           .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                   .thenComparing(Map.Entry.comparingByKey()))
           .forEach(e -> out.printf("  %-10s %d%n", e.getKey(), e.getValue()));
    }

    private static String format(Map<String, Integer> kinds) {
        if(kinds.isEmpty())
            return "-";
        List<String> parts = new ArrayList<>();
        for(String k : dedupOrder(kinds.keySet())) {
            int n = kinds.get(k);
            parts.add(n > 1 ? k + "x" + n : k);
        }
        return String.join(", ", parts);
    }

    private static List<String> dedupOrder(java.util.Set<String> have) {
        List<String> ordered = new ArrayList<>();
        for(String k : KIND.values()) {
            if(have.contains(k) && !ordered.contains(k))
                ordered.add(k);
        }
        return ordered;
    }

    private static List<Path> collect(Path target) throws IOException {
        if(Files.isRegularFile(target))
            return List.of(target);
        if(!Files.isDirectory(target))
            throw new IOException("No such file or directory: " + target);
        try(Stream<Path> s = Files.walk(target)) {
            List<Path> out = new ArrayList<>();
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".res"))
             .sorted(Comparator.comparing(Path::toString))
             .forEach(out::add);
            return out;
        }
    }
}
