package resforge.gui;

import resforge.layers.ActionCodec;
import resforge.layers.AudioInfo;
import resforge.layers.FontInfo;
import resforge.layers.ImageInfo;
import resforge.layers.MeshInfo;
import resforge.layers.PropsCodec;
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
            case "tooltip":
            case "pagina":  return "text";
            case "vbuf2":
            case "mesh":    return "3D model";
            default:        return "raw";
        }
    }

    /** A short human summary used in the layer table. */
    public static String summary(Layer l) {
        try {
            switch(l.name) {
                case "image": {
                    ImageInfo ii = ImageInfo.parse(l.data);
                    return ii.imageFormat != null ? ii.imageFormat + " image" : "image";
                }
                case "tex": {
                    TexInfo ti = TexInfo.parse(l.data);
                    return ti.found ? ti.szX + "x" + ti.szY + " " + ti.imageFormat : "texture";
                }
                case "audio2": {
                    AudioInfo ai = AudioInfo.parse(l.data);
                    return ai.format != null ? "Ogg Vorbis" : "audio";
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

    /** Editable JSON for props/action (only when losslessly reversible), else null. */
    public static String editableJson(Layer l) {
        if(l.name.equals("props"))
            return PropsCodec.toJsonIfLossless(l.data);
        if(l.name.equals("action"))
            return ActionCodec.toJsonIfLossless(l.data);
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
            default:
                break;
        }
        return new Export(l.data.clone(), "bin", "Raw bytes");
    }

    private static String preview(String s, int max) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }
}
