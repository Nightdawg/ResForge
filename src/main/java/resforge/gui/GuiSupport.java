package resforge.gui;

import resforge.layers.ActionCodec;
import resforge.layers.AnimCodec;
import resforge.layers.AudioInfo;
import resforge.layers.CodeEntryInfo;
import resforge.layers.CodeInfo;
import resforge.layers.DepsInfo;
import resforge.layers.FontInfo;
import resforge.layers.ImageInfo;
import resforge.layers.Mat2Codec;
import resforge.layers.MeshInfo;
import resforge.layers.NegCodec;
import resforge.layers.ObstCodec;
import resforge.layers.PropsCodec;
import resforge.layers.RLinkInfo;
import resforge.layers.SrcInfo;
import resforge.layers.TexInfo;
import resforge.layers.Vbuf2Info;
import resforge.res.Layer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pure helpers that turn a {@link Layer} into things the GUI can show or edit,
 *  reusing the existing layer decoders/codecs. */
public final class GuiSupport {
    private GuiSupport() {
    }

    /** A friendly one-word kind for a layer name. */
    public static String kind(String name) {
        switch(name) {
            case "image":   return "icon";
            case "tex":     return "texture";
            case "audio2":  return "sound";
            case "font":    return "font";
            case "midi":    return "music";
            case "action":  return "keybind";
            case "props":   return "props";
            case "anim":    return "animation";
            case "neg":     return "hitbox";
            case "obst":    return "collision";
            case "tooltip":
            case "pagina":  return "text";
            case "vbuf2":
            case "mesh":    return "3D model";
            case "mat2":    return "material";
            case "code":
            case "codeentry": return "code";
            case "deps":    return "dependencies";
            case "rlink":   return "links";
            case "src":     return "source";
            default:        return "raw";
        }
    }

    /** A short human summary used in the layer table. */
    public static String summary(Layer l) {
        try {
            switch(l.name) {
                case "image": {
                    ImageInfo ii = ImageInfo.parse(l.data);
                    String fmt = ii.imageFormat != null ? ii.imageFormat : "image";
                    return ii.recognized ? "id=" + ii.id + "  " + fmt : fmt;
                }
                case "tex": {
                    TexInfo ti = TexInfo.parse(l.data);
                    if(!ti.found)
                        return "texture";
                    return (ti.recognized ? "id=" + ti.id + "  " : "")
                            + ti.szX + "x" + ti.szY + " " + ti.imageFormat;
                }
                case "audio2": {
                    AudioInfo ai = AudioInfo.parse(l.data);
                    if(ai.format == null)
                        return "audio";
                    return (ai.recognized && !ai.id.isEmpty() ? "\"" + ai.id + "\" " : "") + "Ogg Vorbis";
                }
                case "font": {
                    FontInfo fi = FontInfo.parse(l.data);
                    return fi.format != null ? fi.format.toUpperCase() + " font" : "font";
                }
                case "tooltip":
                case "pagina":
                    return "\"" + preview(new String(l.data, StandardCharsets.UTF_8), 30) + "\"";
                case "action": {
                    java.util.Map<String, Object> m = ActionCodec.decode(l.data);
                    return "\"" + m.get("name") + "\"";
                }
                case "props": {
                    java.util.Map<String, Object> m = PropsCodec.decode(l.data);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> p = (java.util.Map<String, Object>) m.get("props");
                    return p.size() + " propert" + (p.size() == 1 ? "y" : "ies");
                }
                case "vbuf2": {
                    Vbuf2Info vi = Vbuf2Info.parse(l.data);
                    return vi.recognized ? vi.num + " vertices" : "vertex buffer";
                }
                case "mesh": {
                    MeshInfo mi = MeshInfo.parse(l.data);
                    return mi.recognized ? mi.numTris + " triangles" : "mesh";
                }
                case "mat2": {
                    java.util.Map<String, Object> m = Mat2Codec.decode(l.data);
                    java.util.List<?> es = (java.util.List<?>) m.get("entries");
                    return es.size() + " material command" + (es.size() == 1 ? "" : "s");
                }
                case "code": {
                    CodeInfo ci = CodeInfo.parse(l.data);
                    if(!ci.recognized)
                        return "code";
                    int dot = ci.name.lastIndexOf('.');
                    return (dot >= 0 ? ci.name.substring(dot + 1) : ci.name) + ".class";
                }
                case "anim": {
                    java.util.Map<String, Object> m = AnimCodec.decode(l.data);
                    java.util.List<?> fr = (java.util.List<?>) m.get("frames");
                    return fr.size() + " frames @ " + m.get("delay") + "ms";
                }
                case "neg": {
                    java.util.Map<String, Object> m = NegCodec.decode(l.data);
                    java.util.List<?> c = (java.util.List<?>) m.get("center");
                    java.util.List<?> eps = (java.util.List<?>) m.get("endpoints");
                    return "click (" + c.get(0) + "," + c.get(1) + "), " + eps.size() + " endpoint grp";
                }
                case "obst": {
                    java.util.Map<String, Object> m = ObstCodec.decode(l.data);
                    java.util.List<?> polys = (java.util.List<?>) m.get("polygons");
                    int pts = 0;
                    for(Object p : polys)
                        pts += ((java.util.List<?>) p).size();
                    return polys.size() + " polygon" + (polys.size() == 1 ? "" : "s") + ", " + pts + " pts";
                }
                case "codeentry": {
                    CodeEntryInfo ce = CodeEntryInfo.parse(l.data);
                    return ce.entries.size() + " entry pt" + (ce.entries.size() == 1 ? "" : "s")
                            + ", " + ce.classpath.size() + " dep" + (ce.classpath.size() == 1 ? "" : "s");
                }
                case "deps": {
                    DepsInfo di = DepsInfo.parse(l.data);
                    if(!di.recognized)
                        return "dependencies";
                    return di.deps.size() + " dependenc" + (di.deps.size() == 1 ? "y" : "ies");
                }
                case "rlink": {
                    RLinkInfo ri = RLinkInfo.parse(l.data);
                    if(!ri.recognized && ri.links.isEmpty())
                        return "resource links";
                    return ri.links.size() + " link" + (ri.links.size() == 1 ? "" : "s");
                }
                case "src": {
                    SrcInfo si = SrcInfo.parse(l.data);
                    return si.recognized ? si.fileName : "source file";
                }
                default:
                    return l.data.length + " bytes";
            }
        } catch(RuntimeException e) {
            return l.data.length + " bytes";
        }
    }

    /** A preview image for image/tex layers, else null. */
    public static BufferedImage preview(Layer l) {
        try {
            byte[] img = embeddedImage(l);
            if(img != null)
                return ImageIO.read(new ByteArrayInputStream(img));
        } catch(Exception e) {
            /* no preview */
        }
        return null;
    }

    /** The embedded encoded-image bytes for image/tex layers, else null. */
    public static byte[] embeddedImage(Layer l) {
        if(l.name.equals("image")) {
            ImageInfo ii = ImageInfo.parse(l.data);
            if(ii.imageFormat != null && ii.imageOffset > 0)
                return Arrays.copyOfRange(l.data, ii.imageOffset, l.data.length);
        } else if(l.name.equals("tex")) {
            TexInfo ti = TexInfo.parse(l.data);
            if(ti.found)
                return Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
        }
        return null;
    }

    /** The embedded Ogg Vorbis bytes for an audio2 layer, else null. */
    public static byte[] audioBytes(Layer l) {
        if(l.name.equals("audio2")) {
            AudioInfo ai = AudioInfo.parse(l.data);
            if(ai.format != null && ai.audioOffset > 0)
                return Arrays.copyOfRange(l.data, ai.audioOffset, l.data.length);
        }
        return null;
    }

    /** Editable plain text for tooltip/pagina, else null. */
    public static String editableText(Layer l) {
        if(l.name.equals("tooltip") || l.name.equals("pagina"))
            return new String(l.data, StandardCharsets.UTF_8);
        return null;
    }

    /** Editable JSON for props/action/mat2 (only when losslessly reversible), else null. */
    public static String editableJson(Layer l) {
        if(l.name.equals("props"))
            return PropsCodec.toJsonIfLossless(l.data);
        if(l.name.equals("action"))
            return ActionCodec.toJsonIfLossless(l.data);
        if(l.name.equals("mat2"))
            return Mat2Codec.toJsonIfLossless(l.data);
        if(l.name.equals("anim"))
            return AnimCodec.toJsonIfLossless(l.data);
        if(l.name.equals("neg"))
            return NegCodec.toJsonIfLossless(l.data);
        if(l.name.equals("obst"))
            return ObstCodec.toJsonIfLossless(l.data);
        return null;
    }

    /** A read-only, human-readable description of a code/codeentry layer, else null. */
    public static String codeText(Layer l) {
        if(l.name.equals("code")) {
            CodeInfo ci = CodeInfo.parse(l.data);
            if(!ci.recognized)
                return "(unrecognized code layer)";
            return "Class: " + ci.name + "\n"
                    + (ci.isClassFile ? "Compiled Java .class" : "data") + ", " + ci.code.length + " bytes\n\n"
                    + "Server-authored bytecode (read-only). Export it to inspect or decompile externally.";
        }
        if(l.name.equals("codeentry")) {
            CodeEntryInfo ce = CodeEntryInfo.parse(l.data);
            if(!ce.recognized)
                return "(unrecognized codeentry layer)";
            StringBuilder sb = new StringBuilder("Code entry manifest (read-only).\n");
            if(!ce.entries.isEmpty()) {
                sb.append("\nEntry points:\n");
                for(CodeEntryInfo.Entry en : ce.entries)
                    sb.append("  ").append(en.name).append("  ->  ").append(en.className)
                      .append(en.args != null ? "  " + en.args : "").append('\n');
            }
            if(!ce.classpath.isEmpty()) {
                sb.append("\nClasspath dependencies:\n");
                for(CodeEntryInfo.Dep d : ce.classpath)
                    sb.append("  ").append(d.name).append(d.ver >= 0 ? " @ v" + d.ver : "").append('\n');
            }
            if(ce.entries.isEmpty() && ce.classpath.isEmpty())
                sb.append("\n(empty)\n");
            return sb.toString();
        }
        return null;
    }

    /** A read-only, human-readable view of a deps/rlink/src layer, else null. */
    public static String referenceText(Layer l) {
        if(l.name.equals("deps")) {
            DepsInfo di = DepsInfo.parse(l.data);
            if(!di.recognized)
                return "(unrecognized deps layer)";
            StringBuilder sb = new StringBuilder("Resource dependencies (read-only).\n");
            if(di.deps.isEmpty())
                sb.append("\n(none)\n");
            else {
                sb.append('\n');
                for(DepsInfo.Dep d : di.deps)
                    sb.append("  ").append(d.name).append("  @ v").append(d.ver).append('\n');
            }
            return sb.toString();
        }
        if(l.name.equals("rlink")) {
            RLinkInfo ri = RLinkInfo.parse(l.data);
            if(ri.links.isEmpty())
                return ri.recognized ? "Resource links (read-only).\n\n(none)\n"
                        : "(unrecognized rlink layer)";
            StringBuilder sb = new StringBuilder("Resource links (read-only).\n");
            for(RLinkInfo.Link lk : ri.links) {
                sb.append("\n  id ").append(lk.id).append("  ->  ")
                  .append(lk.res).append(" @ v").append(lk.ver).append('\n');
                if(lk.spec != null)
                    sb.append("      spec: ").append(lk.spec).append('\n');
            }
            if(!ri.recognized)
                sb.append("\n(\u2026remaining entries use an unrecognized form and were skipped)\n");
            return sb.toString();
        }
        if(l.name.equals("src")) {
            SrcInfo si = SrcInfo.parse(l.data);
            if(!si.recognized)
                return "(unrecognized src layer)";
            return "// " + si.fileName + "  (embedded source, read-only)\n\n" + si.text();
        }
        return null;
    }

    /** A read-only metadata line for an image/tex layer (id, z/sub-z, offset), else null. */
    public static String imageMeta(Layer l) {
        if(l.name.equals("image")) {
            ImageInfo ii = ImageInfo.parse(l.data);
            if(!ii.recognized)
                return null;
            StringBuilder sb = new StringBuilder("id=").append(ii.id);
            if(ii.headerVer < 128) {
                sb.append("   z=").append(ii.z).append("  sub-z=").append(ii.subz);
                if(ii.nooff)
                    sb.append("   (no offset)");
                else
                    sb.append("   offset=(").append(ii.offX).append(", ").append(ii.offY).append(")");
            } else {
                sb.append("   (typed header)");
            }
            return sb.toString();
        }
        if(l.name.equals("tex")) {
            TexInfo ti = TexInfo.parse(l.data);
            if(!ti.recognized)
                return null;
            return "id=" + ti.id + "   " + ti.szX + "\u00d7" + ti.szY
                    + "   offset=(" + ti.offX + ", " + ti.offY + ")";
        }
        return null;
    }

    /** A read-only multi-line detail for vbuf2/mesh layers (counts, attributes), else null. */
    public static String modelDetail(Layer l) {
        if(l.name.equals("vbuf2")) {
            Vbuf2Info vi = Vbuf2Info.parse(l.data);
            if(!vi.recognized)
                return "vbuf2 (unrecognized)";
            StringBuilder sb = new StringBuilder();
            sb.append(vi.num).append(" vertices   (format v").append(vi.ver);
            if(vi.ver >= 1)
                sb.append(", id=").append(vi.id);
            sb.append(")\nAttributes: ").append(String.join(", ", vi.attribs));
            if(vi.stoppedAt != null)
                sb.append("\n+ bone/skinning data (").append(vi.stoppedAt).append(")");
            return sb.toString();
        }
        if(l.name.equals("mesh")) {
            MeshInfo mi = MeshInfo.parse(l.data);
            if(!mi.recognized)
                return "mesh (unrecognized)";
            StringBuilder sb = new StringBuilder();
            sb.append(mi.numTris).append(" triangles\nvbuf=").append(mi.vbufid);
            if(mi.matid >= 0)
                sb.append("   material=").append(mi.matid);
            if(mi.id >= 0)
                sb.append("   id=").append(mi.id);
            if(mi.stripped)
                sb.append("   (delta-stripped indices)");
            return sb.toString();
        }
        return null;
    }

    /** A read-only metadata line for font/midi layers, else null. */
    public static String mediaMeta(Layer l) {
        if(l.name.equals("font")) {
            FontInfo fi = FontInfo.parse(l.data);
            if(fi.format != null)
                return "Embedded " + fi.format.toUpperCase() + " font (sfnt)   ver=" + fi.ver + " type=" + fi.type;
            return "font (unrecognized)";
        }
        if(l.name.equals("midi"))
            return "MIDI music (whole-file payload)";
        return null;
    }

    /** Result of preparing a layer's content for export. */
    public static final class Export {
        public final byte[] data;
        public final String ext;       // suggested file extension, no dot
        public final String desc;      // file-chooser description

        Export(byte[] data, String ext, String desc) {
            this.data = data;
            this.ext = ext;
            this.desc = desc;
        }
    }

    /** Prepares the most useful exportable form of a layer. */
    public static Export export(Layer l) {
        switch(l.name) {
            case "image": {
                byte[] img = embeddedImage(l);
                if(img != null) {
                    String e = ImageInfo.parse(l.data).imageFormat;
                    return new Export(img, e, e.toUpperCase() + " image");
                }
                break;
            }
            case "tex": {
                byte[] img = embeddedImage(l);
                if(img != null) {
                    String e = TexInfo.parse(l.data).imageFormat;
                    return new Export(img, e, e.toUpperCase() + " texture");
                }
                break;
            }
            case "audio2": {
                AudioInfo ai = AudioInfo.parse(l.data);
                if(ai.format != null)
                    return new Export(Arrays.copyOfRange(l.data, ai.audioOffset, l.data.length),
                            "ogg", "Ogg Vorbis audio");
                break;
            }
            case "font": {
                FontInfo fi = FontInfo.parse(l.data);
                if(fi.format != null)
                    return new Export(Arrays.copyOfRange(l.data, fi.fontOffset, l.data.length),
                            fi.format, fi.format.toUpperCase() + " font");
                break;
            }
            case "midi":
                return new Export(l.data.clone(), "mid", "MIDI music");
            case "tooltip":
            case "pagina":
                return new Export(l.data.clone(), "txt", "Text");
            case "props": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "action": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "mat2": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "anim": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "neg": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "obst": {
                String j = editableJson(l);
                if(j != null)
                    return new Export(j.getBytes(StandardCharsets.UTF_8), "json", "JSON");
                break;
            }
            case "code": {
                CodeInfo ci = CodeInfo.parse(l.data);
                if(ci.recognized && ci.code != null)
                    return new Export(ci.code, "class", "Java class file");
                break;
            }
            case "src": {
                SrcInfo si = SrcInfo.parse(l.data);
                if(si.recognized) {
                    String ext = extensionOf(si.fileName, "java");
                    return new Export(si.source, ext, "Source file");
                }
                break;
            }
            case "deps":
            case "rlink": {
                String t = referenceText(l);
                if(t != null)
                    return new Export(t.getBytes(StandardCharsets.UTF_8), "txt", "Reference listing");
                break;
            }
            default:
                break;
        }
        return new Export(l.data.clone(), "bin", "Raw bytes");
    }

    private static String preview(String s, int max) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    /** The lower-cased file extension of a name, or {@code fallback} if none. */
    private static String extensionOf(String name, String fallback) {
        if(name != null) {
            int dot = name.lastIndexOf('.');
            if(dot >= 0 && dot < name.length() - 1)
                return name.substring(dot + 1).toLowerCase();
        }
        return fallback;
    }
}
