package resforge.res;

import resforge.layers.ActionCodec;
import resforge.layers.AnimCodec;
import resforge.layers.AudioInfo;
import resforge.layers.BoneOffCodec;
import resforge.layers.CodeEntryInfo;
import resforge.layers.CodeInfo;
import resforge.layers.DepsInfo;
import resforge.layers.FontInfo;
import resforge.layers.ImageInfo;
import resforge.layers.LightCodec;
import resforge.layers.Mat2Codec;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.NegCodec;
import resforge.layers.ObstCodec;
import resforge.layers.PropsCodec;
import resforge.layers.RLinkInfo;
import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.layers.SrcInfo;
import resforge.layers.TexInfo;
import resforge.layers.Vbuf2Info;
import resforge.model.Vbuf2Codec;

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
        public final Map<String, Integer> audioHist = new TreeMap<>();
        public final Map<String, Integer> actionHist = new TreeMap<>();
        public final Map<String, Integer> matHist = new TreeMap<>();
        public final Map<String, Integer> animHist = new TreeMap<>();
        public final Map<String, Integer> negHist = new TreeMap<>();
        public final Map<String, Integer> obstHist = new TreeMap<>();
        public final Map<String, Integer> codeHist = new TreeMap<>();
        public final Map<String, Integer> codeEntryHist = new TreeMap<>();
        public final Map<String, Integer> depsHist = new TreeMap<>();
        public final Map<String, Integer> srcHist = new TreeMap<>();
        public final Map<String, Integer> rlinkHist = new TreeMap<>();
        public final Map<String, Integer> lightHist = new TreeMap<>();
        public final Map<String, Integer> skelHist = new TreeMap<>();
        public final Map<String, Integer> skanHist = new TreeMap<>();
        public final Map<String, Integer> boneoffHist = new TreeMap<>();
        public final Map<String, Integer> manimHist = new TreeMap<>();
        public final Map<String, Integer> fontHist = new TreeMap<>();
        public final Map<String, Integer> vbufHist = new TreeMap<>();
        public final Map<String, Integer> meshHist = new TreeMap<>();
        public final Map<String, Integer> vbufReencHist = new TreeMap<>();
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
        printHist(out, "Audio histogram", sum.audioHist);
        printHist(out, "Action histogram", sum.actionHist);
        printHist(out, "Mat2 histogram", sum.matHist);
        printHist(out, "Anim histogram", sum.animHist);
        printHist(out, "Neg histogram", sum.negHist);
        printHist(out, "Obst histogram", sum.obstHist);
        printHist(out, "Code histogram", sum.codeHist);
        printHist(out, "CodeEntry histogram", sum.codeEntryHist);
        printHist(out, "Deps histogram", sum.depsHist);
        printHist(out, "Src histogram", sum.srcHist);
        printHist(out, "RLink histogram", sum.rlinkHist);
        printHist(out, "Light histogram", sum.lightHist);
        printHist(out, "Skel histogram", sum.skelHist);
        printHist(out, "Skan histogram", sum.skanHist);
        printHist(out, "BoneOff histogram", sum.boneoffHist);
        printHist(out, "Manim histogram", sum.manimHist);
        printHist(out, "Font histogram", sum.fontHist);
        printHist(out, "Vbuf2 histogram", sum.vbufHist);
        printHist(out, "Mesh histogram", sum.meshHist);
        printHist(out, "Vbuf2 re-encode histogram", sum.vbufReencHist);
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
            else if(l.name.equals("audio2")) {
                AudioInfo ai = AudioInfo.parse(l.data);
                sum.audioHist.merge(ai.format != null ? "ogg" : "raw (no ogg)", 1, Integer::sum);
            }
            else if(l.name.equals("action"))
                sum.actionHist.merge(ActionCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("mat2"))
                sum.matHist.merge(Mat2Codec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("anim"))
                sum.animHist.merge(AnimCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("neg"))
                sum.negHist.merge(NegCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("obst"))
                sum.obstHist.merge(ObstCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("code")) {
                CodeInfo ci = CodeInfo.parse(l.data);
                sum.codeHist.merge(!ci.recognized ? "unrecognized" : ci.isClassFile ? "class" : "data",
                        1, Integer::sum);
            }
            else if(l.name.equals("codeentry")) {
                CodeEntryInfo ce = CodeEntryInfo.parse(l.data);
                sum.codeEntryHist.merge(!ce.recognized ? "unrecognized" : ce.reachedEnd ? "decoded" : "partial",
                        1, Integer::sum);
            }
            else if(l.name.equals("deps")) {
                DepsInfo di = DepsInfo.parse(l.data);
                sum.depsHist.merge(!di.recognized ? "unrecognized" : "decoded", 1, Integer::sum);
            }
            else if(l.name.equals("src")) {
                SrcInfo si = SrcInfo.parse(l.data);
                sum.srcHist.merge(si.recognized ? "decoded" : "unrecognized", 1, Integer::sum);
            }
            else if(l.name.equals("rlink")) {
                RLinkInfo ri = RLinkInfo.parse(l.data);
                sum.rlinkHist.merge(ri.recognized ? "decoded" : ri.links.isEmpty() ? "unrecognized" : "partial",
                        1, Integer::sum);
            }
            else if(l.name.equals("light"))
                sum.lightHist.merge(LightCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("skel")) {
                SkelInfo si = SkelInfo.parse(l.data);
                sum.skelHist.merge(si.recognized ? "decoded" : "unrecognized", 1, Integer::sum);
            }
            else if(l.name.equals("skan")) {
                SkanInfo si = SkanInfo.parse(l.data);
                sum.skanHist.merge(si.recognized ? "decoded" : "unrecognized", 1, Integer::sum);
            }
            else if(l.name.equals("boneoff"))
                sum.boneoffHist.merge(BoneOffCodec.toJsonIfLossless(l.data) != null ? "json" : "raw",
                        1, Integer::sum);
            else if(l.name.equals("manim")) {
                MeshAnimInfo mi = MeshAnimInfo.parse(l.data);
                sum.manimHist.merge(mi.recognized ? "decoded" : "unrecognized", 1, Integer::sum);
            }
            else if(l.name.equals("font")) {
                FontInfo fi = FontInfo.parse(l.data);
                sum.fontHist.merge(fi.format != null ? fi.format : "raw", 1, Integer::sum);
            }
            else if(l.name.equals("vbuf2")) {
                Vbuf2Info vi = Vbuf2Info.parse(l.data);
                String key = !vi.recognized ? "unrecognized"
                        : vi.fullyWalked ? "walked"
                        : (vi.stoppedAt != null ? "stopped@" + vi.stoppedAt : "partial");
                sum.vbufHist.merge(key, 1, Integer::sum);
                try {
                    boolean exact = java.util.Arrays.equals(l.data, Vbuf2Codec.parse(l.data).encode());
                    sum.vbufReencHist.merge(exact ? "exact" : "differs", 1, Integer::sum);
                } catch(RuntimeException e) {
                    sum.vbufReencHist.merge("error", 1, Integer::sum);
                }
            }
            else if(l.name.equals("mesh")) {
                MeshInfo mi = MeshInfo.parse(l.data);
                sum.meshHist.merge(!mi.recognized ? "unrecognized"
                        : (mi.reachedEnd ? "decoded" : "partial"), 1, Integer::sum);
            }
        }
        return r;
    }

    private static boolean verifyParts(ResContainer res, byte[] raw, FileResult r) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("resforge-verify");
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
