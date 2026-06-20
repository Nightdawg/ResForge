package resforge;

import resforge.layers.ImageInfo;
import resforge.model.ObjExport;
import resforge.model.Vbuf2Codec;
import resforge.res.Catalog;
import resforge.res.Layer;
import resforge.res.Manifest;
import resforge.res.Packer;
import resforge.res.Replacer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import resforge.res.Verifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for ResForge.
 *
 * Usage:
 *   info    <file.res>
 *   unpack  <file.res> [outDir]
 *   pack    <dir> [out.res]
 *   replace <file.res> <selector> <newfile> [out.res]
 *   obj     <file.res> [out.obj]
 *   transform <file.res> <sx> <sy> <sz> [out.res]
 *   catalog <file.res | dir>
 *   verify  <file.res | dir>
 */
public class Main {
    public static void main(String[] args) {
        try {
            run(args);
        } catch(UsageException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
        } catch(Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if(args.length == 0) {
            if(!java.awt.GraphicsEnvironment.isHeadless()) {
                resforge.gui.ResForgeFrame.launch(null);
                return;
            }
            throw new UsageException("no command given");
        }
        switch(args[0]) {
            case "gui":    gui(args);    break;
            case "fetch":  fetch(args);  break;
            case "info":   info(args);   break;
            case "unpack": unpack(args); break;
            case "pack":   pack(args);   break;
            case "replace": replace(args); break;
            case "obj":    obj(args);    break;
            case "transform": transform(args); break;
            case "catalog": catalog(args); break;
            case "verify": verify(args); break;
            case "-h": case "--help": case "help": usage(); break;
            default: throw new UsageException("unknown command: " + args[0]);
        }
    }

    private static void gui(String[] args) {
        if(java.awt.GraphicsEnvironment.isHeadless())
            throw new RuntimeException("no graphical display available (headless environment)");
        Path initial = (args.length >= 2) ? Path.of(args[1]) : null;
        resforge.gui.ResForgeFrame.launch(initial);
    }

    private static void fetch(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("fetch requires a resource path (e.g. gfx/borka/male)");
        String path = args[1];
        Path out = (args.length >= 3)
                ? Path.of(args[2])
                : Path.of(resforge.net.ResourceFetcher.baseName(path) + ".res");
        byte[] data;
        try {
            data = resforge.net.ResourceFetcher.fetch(null, path);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("fetch interrupted");
        }
        ResContainer res = ResContainer.parse(data);   // validate it is a real .res
        Files.write(out, data);
        System.out.printf("Fetched %s (%d bytes, res-version %d, %d layers) -> %s%n",
                resforge.net.ResourceFetcher.urlFor(null, path), data.length,
                res.version, res.layers.size(), out);
    }

    private static void info(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("info requires a .res file");
        Path file = Path.of(args[1]);
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        System.out.printf("%s%n", file);
        System.out.printf("  res-version: %d%n", res.version);
        System.out.printf("  layers: %d%n", res.layers.size());
        for(int i = 0; i < res.layers.size(); i++) {
            Layer l = res.layers.get(i);
            System.out.printf("  [%3d] %-12s %8d bytes", i, l.name, l.data.length);
            if(l.name.equals("image")) {
                ImageInfo ii = ImageInfo.parse(l.data);
                StringBuilder extra = new StringBuilder();
                if(ii.recognized)
                    extra.append(String.format(" id=%d z=%d subz=%d", ii.id, ii.z, ii.subz));
                if(ii.imageFormat != null)
                    extra.append("  ").append(ii.imageFormat).append(" @ +").append(ii.imageOffset);
                System.out.print(extra);
            } else if(l.name.equals("tex")) {
                resforge.layers.TexInfo ti = resforge.layers.TexInfo.parse(l.data);
                StringBuilder extra = new StringBuilder();
                if(ti.recognized)
                    extra.append(String.format(" id=%d sz=%dx%d", ti.id, ti.szX, ti.szY));
                if(ti.found)
                    extra.append("  ").append(ti.imageFormat).append(" @ +").append(ti.imageOffset);
                System.out.print(extra);
            } else if(l.name.equals("tooltip") || l.name.equals("pagina")) {
                System.out.printf("  \"%s\"", Unpacker.previewText(l.data, 40));
            } else if(l.name.equals("props")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.PropsCodec.decode(l.data);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> p = (java.util.Map<String, Object>) m.get("props");
                    boolean editable = resforge.layers.PropsCodec.toJsonIfLossless(l.data) != null;
                    System.out.printf("  %d props%s", p.size(), editable ? " (editable JSON)" : "");
                } catch(RuntimeException e) {
                    /* opaque props: leave as-is */
                }
            } else if(l.name.equals("audio2")) {
                resforge.layers.AudioInfo ai = resforge.layers.AudioInfo.parse(l.data);
                if(ai.recognized)
                    System.out.printf(" id=\"%s\" vol=%.3f", ai.id, ai.bvol);
                if(ai.format != null)
                    System.out.printf("  %s @ +%d", ai.format, ai.audioOffset);
            } else if(l.name.equals("action")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.ActionCodec.decode(l.data);
                    long hk = ((Number) m.get("hotkey")).longValue();
                    String hkc = (hk >= 33 && hk < 127) ? " '" + (char) hk + "'" : "";
                    System.out.printf("  \"%s\" hotkey=%d%s", m.get("name"), hk, hkc);
                } catch(RuntimeException e) {
                    /* opaque action: leave as-is */
                }
            } else if(l.name.equals("mat2")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.Mat2Codec.decode(l.data);
                    java.util.List<?> entries = (java.util.List<?>) m.get("entries");
                    boolean editable = resforge.layers.Mat2Codec.toJsonIfLossless(l.data) != null;
                    System.out.printf("  id=%s %d command%s%s", m.get("id"), entries.size(),
                            entries.size() == 1 ? "" : "s", editable ? " (editable JSON)" : "");
                } catch(RuntimeException e) {
                    /* opaque mat2: leave as-is */
                }
            } else if(l.name.equals("font")) {
                resforge.layers.FontInfo fi = resforge.layers.FontInfo.parse(l.data);
                if(fi.format != null)
                    System.out.printf("  %s @ +%d", fi.format, fi.fontOffset);
            } else if(l.name.equals("vbuf2")) {
                resforge.layers.Vbuf2Info vi = resforge.layers.Vbuf2Info.parse(l.data);
                if(vi.recognized) {
                    System.out.printf("  %d verts [%s]", vi.num, String.join(", ", vi.attribs));
                    if(vi.stoppedAt != null)
                        System.out.printf(" +%s", vi.stoppedAt);
                }
            } else if(l.name.equals("mesh")) {
                resforge.layers.MeshInfo mi = resforge.layers.MeshInfo.parse(l.data);
                if(mi.recognized)
                    System.out.printf("  %d tris vbuf=%d%s%s", mi.numTris, mi.vbufid,
                            mi.matid >= 0 ? " mat=" + mi.matid : "", mi.stripped ? " stripped" : "");
            }
            System.out.println();
        }
    }

    private static void unpack(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("unpack requires a .res file");
        Path file = Path.of(args[1]);
        Path outDir;
        if(args.length >= 3) {
            outDir = Path.of(args[2]);
        } else {
            String n = file.getFileName().toString();
            if(n.toLowerCase().endsWith(".res"))
                n = n.substring(0, n.length() - 4);
            outDir = file.resolveSibling(n + ".resdir");
        }
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        Files.createDirectories(outDir);
        Manifest m = Unpacker.unpack(res, outDir);
        System.out.printf("Unpacked %d layers (res-version %d) into %s%n",
                m.entries.size(), m.version, outDir);
    }

    private static void pack(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("pack requires an unpacked directory");
        Path dir = Path.of(args[1]);
        Path out;
        if(args.length >= 3) {
            out = Path.of(args[2]);
        } else {
            String n = dir.getFileName().toString();
            if(n.toLowerCase().endsWith(".resdir"))
                n = n.substring(0, n.length() - ".resdir".length());
            out = dir.resolveSibling(n + ".res");
        }
        ResContainer res = Packer.pack(dir);
        Files.write(out, res.serialize());
        System.out.printf("Packed %d layers (res-version %d) into %s%n",
                res.layers.size(), res.version, out);
    }

    private static void verify(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("verify requires a .res file or directory");
        Path target = Path.of(args[1]);
        Verifier.Summary s = Verifier.run(target, System.out);
        if(s.failed > 0)
            System.exit(1);
    }

    private static void catalog(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("catalog requires a .res file or directory");
        Catalog.run(Path.of(args[1]), System.out);
    }

    private static void transform(String[] args) throws IOException {
        if(args.length < 5)
            throw new UsageException("transform requires <file.res> <sx> <sy> <sz> [out.res]");
        Path file = Path.of(args[1]);
        float sx = Float.parseFloat(args[2]);
        float sy = Float.parseFloat(args[3]);
        float sz = Float.parseFloat(args[4]);
        Path out;
        if(args.length >= 6) {
            out = Path.of(args[5]);
        } else {
            String n = file.getFileName().toString();
            if(n.toLowerCase().endsWith(".res"))
                n = n.substring(0, n.length() - 4);
            out = file.resolveSibling(n + "-transformed.res");
        }
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        int edited = 0;
        long verts = 0;
        for(int i = 0; i < res.layers.size(); i++) {
            Layer l = res.layers.get(i);
            if(!l.name.equals("vbuf2"))
                continue;
            Vbuf2Codec c = Vbuf2Codec.parse(l.data);
            if(c.position() == null)
                continue;
            float[] p = c.decodePositions();
            for(int v = 0; v < c.num; v++) {
                p[v * 3]     *= sx;
                p[v * 3 + 1] *= sy;
                p[v * 3 + 2] *= sz;
            }
            c.setPositions(p);
            res.layers.set(i, new Layer("vbuf2", c.encode()));
            edited++;
            verts += c.num;
        }
        if(edited == 0)
            throw new RuntimeException("no editable vbuf2 positions found in " + file);
        Files.write(out, res.serialize());
        System.out.printf("Scaled %d vertices across %d vbuf2 by (%s, %s, %s) -> %s%n",
                verts, edited, args[2], args[3], args[4], out);
        System.out.println("NOTE: this is a write path - load the result in-game to confirm it renders.");
    }

    private static void obj(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("obj requires a .res file");
        Path file = Path.of(args[1]);
        String name = file.getFileName().toString();
        Path out;
        if(args.length >= 3) {
            out = Path.of(args[2]);
        } else {
            String n = name;
            if(n.toLowerCase().endsWith(".res"))
                n = n.substring(0, n.length() - 4);
            out = file.resolveSibling(n + ".obj");
        }
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        ObjExport.Result r = ObjExport.toObj(res, name);
        if(r.vertices == 0 || r.triangles == 0)
            throw new RuntimeException("no 3D geometry (vbuf2/mesh) found in " + file);
        Files.writeString(out, r.obj);
        System.out.printf("Exported %d vertices, %d triangles (%d submeshes) -> %s%n",
                r.vertices, r.triangles, r.submeshes, out);
    }

    private static void replace(String[] args) throws IOException {
        if(args.length < 4)
            throw new UsageException("replace requires <file.res> <selector> <newfile> [out.res]");
        Path file = Path.of(args[1]);
        String selector = args[2];
        Path newFile = Path.of(args[3]);
        Path out = (args.length >= 5) ? Path.of(args[4]) : file;
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        int idx = Replacer.replace(res, selector, Files.readAllBytes(newFile));
        Files.write(out, res.serialize());
        System.out.printf("Replaced layer [%d] %s in %s with %s -> %s%n",
                idx, res.layers.get(idx).name, file, newFile, out);
    }

    private static void usage() {
        System.out.println("ResForge — Haven & Hearth .res mod tool");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  (no args)                    Open the graphical editor (GUI)");
        System.out.println("  gui    [file.res]            Open the GUI, optionally with a file");
        System.out.println("  fetch  <path> [out.res]      Download a resource from the server (e.g. gfx/borka/male)");
        System.out.println("  info   <file.res>            Show version and layer summary");
        System.out.println("  unpack <file.res> [outDir]   Decompile into an editable folder");
        System.out.println("  pack   <dir> [out.res]       Recompile a folder into a .res file");
        System.out.println("  replace <file.res> <layer> <newfile> [out.res]");
        System.out.println("                               Swap one asset (image/tex/audio2/font/midi/");
        System.out.println("                               tooltip/pagina text, or props/action JSON)");
        System.out.println("  obj    <file.res> [out.obj]  Export 3D geometry to a Wavefront OBJ");
        System.out.println("  transform <file.res> <sx> <sy> <sz> [out.res]");
        System.out.println("                               Scale a model's vertices (re-quantizes positions)");
        System.out.println("  catalog <file.res | dir>     List editable assets per file");
        System.out.println("  verify <file.res | dir>      Round-trip + image-split validation");
    }

    private static class UsageException extends RuntimeException {
        UsageException(String msg) {
            super(msg);
        }
    }
}
