package hafen.resedit.res;

import hafen.resedit.layers.ImageInfo;
import hafen.resedit.layers.PropsCodec;
import hafen.resedit.layers.TexInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Validation harness for real {@code .res} files. For each file it checks:
 * <ol>
 *   <li>container parse-&gt;serialize is byte-identical (sanity),</li>
 *   <li>unpack-&gt;pack is byte-identical (parts-model sanity),</li>
 *   <li>each {@code image} layer's split image part is an independently
 *       complete, {@link ImageIO}-decodable image — proving the header/image
 *       boundary is at the right place so a PNG swap actually works.</li>
 * </ol>
 * It also aggregates a layer-name histogram and image-header-version histogram
 * and lists anomalies, so a batch of real files quickly surfaces shapes the
 * synthetic tests never exercised.
 */
public class Verifier {
    public static class Summary {
        public int total, passed, failed;
        public final Map<String, Integer> layerHist = new TreeMap<>();
        public final Map<String, Integer> imageHeaderHist = new TreeMap<>();
        public final Map<String, Integer> texHist = new TreeMap<>();
        public final Map<String, Integer> propsHist = new TreeMap<>();
    }

    static class FileResult {
        final Path file;
        long size;
        boolean parsed;
        boolean containerOk;
        boolean partsOk;
        boolean imagesOk = true;
        final List<String> anomalies = new ArrayList<>();

        FileResult(Path file) {
            this.file = file;
        }

        boolean pass() {
            return parsed && containerOk && partsOk && imagesOk && anomalies.isEmpty();
        }
    }

    public static Summary run(Path target, PrintStream out) throws IOException {
        List<Path> files = collect(target);
        Summary sum = new Summary();
        sum.total = files.size();

        if(files.isEmpty()) {
            out.printf("No .res files found under %s%n", target);
            return sum;
        }

        for(Path f : files) {
            FileResult r = verifyOne(f, sum);
            if(r.pass()) {
                sum.passed++;
                out.printf("PASS  %-50s %8d bytes%n", rel(target, f), r.size);
            } else {
                sum.failed++;
                out.printf("FAIL  %-50s %8d bytes%n", rel(target, f), r.size);
                for(String a : r.anomalies)
                    out.printf("        - %s%n", a);
            }
        }

        out.println();
        out.printf("Verified %d file(s): %d passed, %d failed%n", sum.total, sum.passed, sum.failed);
        printHist(out, "Layer histogram", sum.layerHist);
        printHist(out, "Image-header histogram", sum.imageHeaderHist);
        printHist(out, "Tex histogram", sum.texHist);
        printHist(out, "Props histogram", sum.propsHist);
        return sum;
    }

    private static FileResult verifyOne(Path f, Summary sum) {
        FileResult r = new FileResult(f);
        byte[] raw;
        try {
            raw = Files.readAllBytes(f);
            r.size = raw.length;
        } catch(IOException e) {
            r.anomalies.add("read failed: " + e.getMessage());
            return r;
        }

        ResContainer res;
        try {
            res = ResContainer.parse(raw);
            r.parsed = true;
        } catch(RuntimeException e) {
            r.anomalies.add("parse failed: " + e.getMessage());
            return r;
        }

        for(Layer l : res.layers)
            sum.layerHist.merge(l.name, 1, Integer::sum);

        try {
            r.containerOk = Arrays.equals(raw, res.serialize());
            if(!r.containerOk)
                r.anomalies.add("container round-trip differs");
        } catch(RuntimeException e) {
            r.anomalies.add("serialize failed: " + e.getMessage());
        }

        r.partsOk = verifyParts(res, raw, r);

        for(int i = 0; i < res.layers.size(); i++) {
            Layer l = res.layers.get(i);
            if(l.name.equals("image"))
                verifyImage(l, i, r, sum);
            else if(l.name.equals("tex"))
                verifyTex(l, i, r, sum);
            else if(l.name.equals("props"))
                sum.propsHist.merge(PropsCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
        }
        return r;
    }

    private static boolean verifyParts(ResContainer res, byte[] raw, FileResult r) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("resedit-verify");
            Unpacker.unpack(res, tmp);
            byte[] repacked = Packer.pack(tmp).serialize();
            boolean ok = Arrays.equals(raw, repacked);
            if(!ok)
                r.anomalies.add("unpack/pack round-trip differs (orig=" + raw.length
                        + ", repacked=" + repacked.length + ")");
            return ok;
        } catch(IOException | RuntimeException e) {
            r.anomalies.add("unpack/pack failed: " + e.getMessage());
            return false;
        } finally {
            if(tmp != null)
                deleteTree(tmp);
        }
    }

    private static void verifyImage(Layer l, int idx, FileResult r, Summary sum) {
        ImageInfo ii;
        try {
            ii = ImageInfo.parse(l.data);
        } catch(RuntimeException e) {
            r.imagesOk = false;
            r.anomalies.add("image[" + idx + "]: header parse threw " + e.getMessage());
            sum.imageHeaderHist.merge("error", 1, Integer::sum);
            return;
        }
        sum.imageHeaderHist.merge(headerKind(ii), 1, Integer::sum);

        if(ii.imageFormat == null || ii.imageOffset < 0) {
            r.imagesOk = false;
            r.anomalies.add("image[" + idx + "]: embedded image not located (split point unknown)");
            return;
        }
        byte[] slice = Arrays.copyOfRange(l.data, ii.imageOffset, l.data.length);
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(slice));
            if(bi == null) {
                r.imagesOk = false;
                r.anomalies.add("image[" + idx + "]: " + ii.imageFormat
                        + " part at +" + ii.imageOffset + " not decodable by ImageIO");
            }
        } catch(IOException e) {
            r.imagesOk = false;
            r.anomalies.add("image[" + idx + "]: decode failed at +" + ii.imageOffset
                    + ": " + e.getMessage());
        }
    }

    private static void verifyTex(Layer l, int idx, FileResult r, Summary sum) {
        TexInfo ti;
        try {
            ti = TexInfo.parse(l.data);
        } catch(RuntimeException e) {
            sum.texHist.merge("error", 1, Integer::sum);
            return;
        }
        if(!ti.found) {
            sum.texHist.merge("raw (no inline image)", 1, Integer::sum);
            return;
        }
        byte[] slice = Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(slice));
            if(bi == null) {
                r.imagesOk = false;
                r.anomalies.add("tex[" + idx + "]: " + ti.imageFormat
                        + " part at +" + ti.imageOffset + " not decodable by ImageIO");
                sum.texHist.merge("undecodable", 1, Integer::sum);
            } else {
                sum.texHist.merge("decoded:" + ti.imageFormat, 1, Integer::sum);
            }
        } catch(IOException e) {
            r.imagesOk = false;
            r.anomalies.add("tex[" + idx + "]: decode failed at +" + ti.imageOffset
                    + ": " + e.getMessage());
            sum.texHist.merge("undecodable", 1, Integer::sum);
        }
    }

    private static String headerKind(ImageInfo ii) {
        if(!ii.recognized)
            return "unrecognized";
        if(ii.headerVer < 128)
            return "old(v<128)";
        return "new(v=" + ii.headerVer + ")";
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

    private static String rel(Path base, Path f) {
        try {
            if(Files.isDirectory(base))
                return base.relativize(f).toString();
        } catch(RuntimeException ignored) {
        }
        return f.getFileName().toString();
    }

    private static void printHist(PrintStream out, String title, Map<String, Integer> hist) {
        out.println(title + ":");
        if(hist.isEmpty()) {
            out.println("  (none)");
            return;
        }
        hist.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> out.printf("  %-16s %d%n", e.getKey(), e.getValue()));
    }

    private static void deleteTree(Path dir) {
        try(Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch(IOException ignored) {
                }
            });
        } catch(IOException ignored) {
        }
    }
}
