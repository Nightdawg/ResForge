package resforge;

import resforge.layers.ImageInfo;
import resforge.model.GltfExport;
import resforge.model.GltfImport;
import resforge.res.Catalog;
import resforge.res.Layer;
import resforge.res.Manifest;
import resforge.res.Packer;
import resforge.res.Replacer;
import resforge.res.ResContainer;
import resforge.res.Unpacker;
import resforge.res.Verifier;
import resforge.io.SafeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for ResForge.
 *
 * Usage:
 *   info    <file.res>
 *   refs    <file.res>
 *   unpack  <file.res> [outDir]
 *   pack    <dir> [out.res]
 *   replace <file.res> <selector> <newfile> [out.res]
 *   gltf    <file.res> [out.glb]
 *   gltf-skan <animation.res> <skeleton.res> <model.res> [out.glb]
 *   rebuild-gltf <orig.res> <edited.glb> [out.res]
 *   rebuild-skan <orig.res> <edited.glb> [out.res]
 *   catalog <file.res | dir>
 *   cache-list [cacheDir]
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
            String msg = e.getMessage();
            System.err.println("error: " + (msg != null ? msg : e.toString()));
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
            case "refs":   refs(args);   break;
            case "unpack": unpack(args); break;
            case "pack":   pack(args);   break;
            case "replace": replace(args); break;
            case "gltf":   gltf(args);   break;
            case "gltf-skan": gltfSkan(args); break;
            case "rebuild-gltf": rebuildGltf(args); break;
            case "rebuild-skan": rebuildSkan(args); break;
            case "catalog": catalog(args); break;
            case "cache-list": cacheList(args); break;
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
        SafeFiles.write(out, data);
        System.out.printf("Fetched %s (%d bytes, res-version %d, %d layers) -> %s%n",
                resforge.net.ResourceFetcher.urlFor(null, path), data.length,
                res.version, res.layers.size(), out);
    }

    private static void refs(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("refs requires a .res file");
        Path file = Path.of(args[1]);
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        System.out.print(resforge.res.References.scan(res).render(file.getFileName().toString()));
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
            } else if(l.name.equals("anim")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.AnimCodec.decode(l.data);
                    java.util.List<?> frames = (java.util.List<?>) m.get("frames");
                    System.out.printf("  id=%s delay=%sms %d frames", m.get("id"), m.get("delay"), frames.size());
                } catch(RuntimeException e) {
                    /* opaque anim: leave as-is */
                }
            } else if(l.name.equals("neg")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.NegCodec.decode(l.data);
                    java.util.List<?> c = (java.util.List<?>) m.get("center");
                    java.util.List<?> eps = (java.util.List<?>) m.get("endpoints");
                    System.out.printf("  center=(%s,%s) %d endpoint group%s", c.get(0), c.get(1),
                            eps.size(), eps.size() == 1 ? "" : "s");
                } catch(RuntimeException e) {
                    /* opaque neg: leave as-is */
                }
            } else if(l.name.equals("obst")) {
                try {
                    java.util.Map<String, Object> m = resforge.layers.ObstCodec.decode(l.data);
                    java.util.List<?> polys = (java.util.List<?>) m.get("polygons");
                    int pts = 0;
                    for(Object p : polys)
                        pts += ((java.util.List<?>) p).size();
                    System.out.printf("  %d polygon%s, %d point%s", polys.size(),
                            polys.size() == 1 ? "" : "s", pts, pts == 1 ? "" : "s");
                } catch(RuntimeException e) {
                    /* opaque obst: leave as-is */
                }
            } else if(l.name.equals("code")) {
                resforge.layers.CodeInfo ci = resforge.layers.CodeInfo.parse(l.data);
                if(ci.recognized)
                    System.out.printf("  %s%s", ci.name, ci.isClassFile ? " (.class)" : "");
            } else if(l.name.equals("codeentry")) {
                resforge.layers.CodeEntryInfo ce = resforge.layers.CodeEntryInfo.parse(l.data);
                if(ce.recognized) {
                    for(resforge.layers.CodeEntryInfo.Entry en : ce.entries)
                        System.out.printf("  ent %s->%s", en.name, en.className);
                    for(resforge.layers.CodeEntryInfo.Dep d : ce.classpath)
                        System.out.printf("  use %s%s", d.name, d.ver >= 0 ? "@v" + d.ver : "");
                }
            } else if(l.name.equals("deps")) {
                resforge.layers.DepsInfo di = resforge.layers.DepsInfo.parse(l.data);
                if(di.recognized)
                    for(resforge.layers.DepsInfo.Dep d : di.deps)
                        System.out.printf("  dep %s@v%d", d.name, d.ver);
            } else if(l.name.equals("src")) {
                resforge.layers.SrcInfo si = resforge.layers.SrcInfo.parse(l.data);
                if(si.recognized)
                    System.out.printf("  %s (%d bytes)", si.fileName, si.source.length);
            } else if(l.name.equals("rlink")) {
                resforge.layers.RLinkInfo ri = resforge.layers.RLinkInfo.parse(l.data);
                for(resforge.layers.RLinkInfo.Link lk : ri.links)
                    System.out.printf("  link[%s] %s@v%d", lk.typeName,
                            lk.res.isEmpty() ? "<self>" : lk.res, lk.ver);
            } else if(l.name.equals("light")) {
                resforge.layers.LightInfo li = resforge.layers.LightInfo.parse(l.data);
                if(li.recognized)
                    System.out.printf("  %s id=%d", li.kind(), li.id);
            } else if(l.name.equals("skel")) {
                resforge.layers.SkelInfo si = resforge.layers.SkelInfo.parse(l.data);
                if(si.recognized)
                    System.out.printf("  %d bones", si.bones.size());
            } else if(l.name.equals("skan")) {
                resforge.layers.SkanInfo si = resforge.layers.SkanInfo.parse(l.data);
                if(si.recognized)
                    System.out.printf("  id=%d %d tracks %ss %s", si.id, si.tracks.size(),
                            (si.len == Math.rint(si.len)) ? Integer.toString((int) si.len) : Float.toString(si.len),
                            si.mode);
            } else if(l.name.equals("boneoff")) {
                resforge.layers.BoneOffInfo bo = resforge.layers.BoneOffInfo.parse(l.data);
                if(bo.recognized)
                    System.out.printf("  \"%s\" %d ops", bo.name, bo.ops.size());
            } else if(l.name.equals("manim")) {
                resforge.layers.MeshAnimInfo mi = resforge.layers.MeshAnimInfo.parse(l.data);
                if(mi.recognized)
                    System.out.printf("  %d frames %ss %s", mi.frames.size(),
                            (mi.len == Math.rint(mi.len)) ? Integer.toString((int) mi.len) : Float.toString(mi.len),
                            mi.random ? "random" : "seq");
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
        SafeFiles.write(out, res.serialize());
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

    private static void cacheList(String[] args) throws IOException {
        Path dir;
        if(args.length >= 2) {
            dir = Path.of(args[1]);
        } else {
            dir = resforge.net.CacheIndex.defaultCacheDir().orElseThrow(() ->
                    new UsageException("could not locate the default cache dir; pass one explicitly"));
        }
        if(!Files.isDirectory(dir))
            throw new RuntimeException("not a directory: " + dir);
        java.util.List<String> names = resforge.net.CacheIndex.scan(dir);
        for(String n : names)
            System.out.println(n);
        System.err.printf("%d resource(s) in %s%n", names.size(), dir);
    }

    private static void gltf(String[] args) throws IOException {
        if(args.length < 2)
            throw new UsageException("gltf requires a .res file");
        Path file = Path.of(args[1]);
        String name = file.getFileName().toString();
        Path out;
        if(args.length >= 3) {
            out = Path.of(args[2]);
        } else {
            String n = name;
            if(n.toLowerCase().endsWith(".res"))
                n = n.substring(0, n.length() - 4);
            out = file.resolveSibling(n + ".glb");
        }
        ResContainer res = ResContainer.parse(Files.readAllBytes(file));
        GltfExport.Result r = GltfExport.toGlb(res, name);
        if(r.vertices == 0 || r.triangles == 0)
            throw new RuntimeException("no 3D geometry (vbuf2/mesh) found in " + file);
        SafeFiles.write(out, r.glb);
        System.out.printf("Exported %d vertices, %d triangles (%d submeshes, %d texture(s)) -> %s%n",
                r.vertices, r.triangles, r.submeshes, r.textures, out);
    }

    private static void rebuildGltf(String[] args) throws IOException {
        if(args.length < 3)
            throw new UsageException("rebuild-gltf requires <original.res> <edited.glb> [out.res]");
        Path resFile = Path.of(args[1]);
        Path glbFile = Path.of(args[2]);
        Path out = (args.length >= 4) ? Path.of(args[3]) : resFile;
        byte[] orig = Files.readAllBytes(resFile);
        byte[] glb = Files.readAllBytes(glbFile);
        GltfImport.RebuildResult r = GltfImport.rebuild(orig, glb);
        SafeFiles.write(out, r.res);
        String extra = (r.skinned ? " (with skinning)" : "") + (r.skel ? " (skeleton re-posed)" : "");
        System.out.printf("Rebuilt geometry: %d vertices, %d triangles%s from %s -> %s%n",
                r.vertices, r.triangles, extra, glbFile, out);
    }

    private static void gltfSkan(String[] args) throws IOException {
        if(args.length < 4)
            throw new UsageException(
                    "gltf-skan requires <animation.res> <skeleton.res> <model.res> [out.glb]");
        Path animationFile = Path.of(args[1]);
        Path skeletonFile = Path.of(args[2]);
        Path modelFile = Path.of(args[3]);
        String name = animationFile.getFileName().toString();
        Path out;
        if(args.length >= 5) {
            out = Path.of(args[4]);
        } else {
            String base = name.toLowerCase().endsWith(".res")
                    ? name.substring(0, name.length() - 4) : name;
            out = animationFile.resolveSibling(base + ".glb");
        }
        ResContainer animation = ResContainer.parse(Files.readAllBytes(animationFile));
        ResContainer skeleton = ResContainer.parse(Files.readAllBytes(skeletonFile));
        ResContainer model = ResContainer.parse(Files.readAllBytes(modelFile));
        GltfExport.Result r = GltfExport.toGlb(model, skeleton, animation, name);
        if(r.vertices == 0 || r.triangles == 0)
            throw new RuntimeException("no 3D geometry (vbuf2/mesh) found in " + modelFile);
        SafeFiles.write(out, r.glb);
        System.out.printf("Exported skeletal animation preview: %d vertices, %d triangles "
                        + "(%d submeshes, %d texture(s)) -> %s%n",
                r.vertices, r.triangles, r.submeshes, r.textures, out);
    }

    private static void rebuildSkan(String[] args) throws IOException {
        if(args.length < 3)
            throw new UsageException("rebuild-skan requires <original.res> <edited.glb> [out.res]");
        Path resFile = Path.of(args[1]);
        Path glbFile = Path.of(args[2]);
        Path out = (args.length >= 4) ? Path.of(args[3]) : resFile;
        GltfImport.AnimationRebuildResult r = GltfImport.rebuildSkan(
                Files.readAllBytes(resFile), Files.readAllBytes(glbFile));
        SafeFiles.write(out, r.res);
        System.out.printf("Rebuilt skeletal animations: %d changed, %d unchanged from %s -> %s%n",
                r.changed, r.unchanged, glbFile, out);
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
        SafeFiles.write(out, res.serialize());
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
        System.out.println("  refs   <file.res>            List the resources this file references");
        System.out.println("  unpack <file.res> [outDir]   Decompile into an editable folder");
        System.out.println("  pack   <dir> [out.res]       Recompile a folder into a .res file");
        System.out.println("  replace <file.res> <layer> <newfile> [out.res]");
        System.out.println("                               Swap one asset (image/tex/audio2/font/midi/");
        System.out.println("                               tooltip/pagina text, or props/action JSON)");
        System.out.println("  gltf   <file.res> [out.glb]  Export 3D geometry to a binary glTF (Blender-ready)");
        System.out.println("  gltf-skan <animation.res> <skeleton.res> <model.res> [out.glb]");
        System.out.println("                               Export skeletal actions with a rigged preview model");
        System.out.println("  rebuild-gltf <orig.res> <edited.glb> [out.res]");
        System.out.println("                               Rebuild geometry from a glTF (allows added/removed vertices)");
        System.out.println("  rebuild-skan <orig.res> <edited.glb> [out.res]");
        System.out.println("                               Import edited skeletal actions from Blender");
        System.out.println("  catalog <file.res | dir>     List editable assets per file");
        System.out.println("  cache-list [cacheDir]        List resource names in the local game cache");
        System.out.println("                               (default: %APPDATA%\\Haven and Hearth\\data)");
        System.out.println("  verify <file.res | dir>      Round-trip + image-split validation");
    }

    private static class UsageException extends RuntimeException {
        UsageException(String msg) {
            super(msg);
        }
    }
}
