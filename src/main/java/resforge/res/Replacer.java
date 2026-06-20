package resforge.res;

import resforge.io.Json;
import resforge.layers.ActionCodec;
import resforge.layers.AnimCodec;
import resforge.layers.AudioInfo;
import resforge.layers.FontInfo;
import resforge.layers.ImageInfo;
import resforge.layers.ImageMagic;
import resforge.layers.Mat2Codec;
import resforge.layers.NegCodec;
import resforge.layers.PropsCodec;
import resforge.layers.TexInfo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot replacement of a single layer's editable content, without unpacking
 * the whole resource. Swaps the embedded media of {@code image}/{@code tex}/
 * {@code audio2}/{@code font}/{@code midi} (preserving the layer's header and,
 * for {@code tex}, recomputing the embedded image length), replaces the text of
 * {@code tooltip}/{@code pagina}, or re-encodes {@code props}/{@code action}
 * from a JSON file.
 */
public class Replacer {
    public static class ReplaceException extends RuntimeException {
        public ReplaceException(String msg) {
            super(msg);
        }
    }

    /**
     * Replaces the content of the layer named by {@code selector} with the bytes
     * of {@code newFile}. Selector forms: a layer name (first occurrence, e.g.
     * {@code image}); a name plus occurrence (e.g. {@code tex#2}, 0-based among
     * layers of that name); or an absolute layer index (e.g. {@code #5}).
     */
    public static int replace(ResContainer res, String selector, byte[] newFile) {
        int idx = resolve(res, selector);
        Layer layer = res.layers.get(idx);
        byte[] payload = rebuild(layer, newFile);
        res.layers.set(idx, new Layer(layer.name, payload));
        return idx;
    }

    private static int resolve(ResContainer res, String selector) {
        if(selector == null || selector.isEmpty())
            throw new ReplaceException("empty selector");
        if(selector.startsWith("#")) {
            int n = parseIndex(selector.substring(1));
            if(n < 0 || n >= res.layers.size())
                throw new ReplaceException("layer index " + n + " out of range (0.."
                        + (res.layers.size() - 1) + ")");
            return n;
        }
        String name = selector;
        int occ = 0;
        int hash = selector.indexOf('#');
        if(hash >= 0) {
            name = selector.substring(0, hash);
            occ = parseIndex(selector.substring(hash + 1));
        }
        int seen = 0;
        for(int i = 0; i < res.layers.size(); i++) {
            if(res.layers.get(i).name.equals(name)) {
                if(seen == occ)
                    return i;
                seen++;
            }
        }
        if(seen == 0)
            throw new ReplaceException("no '" + name + "' layer found. Available: " + names(res));
        throw new ReplaceException("only " + seen + " '" + name + "' layer(s); occurrence "
                + occ + " requested");
    }

    private static byte[] rebuild(Layer layer, byte[] nf) {
        switch(layer.name) {
            case "image": {
                ImageInfo ii = ImageInfo.parse(layer.data);
                if(ii.imageOffset <= 0 || ii.imageFormat == null)
                    throw new ReplaceException("could not locate the existing image to replace");
                requireImage(nf, "image");
                return concat(slice(layer.data, 0, ii.imageOffset), nf);
            }
            case "tex": {
                TexInfo ti = TexInfo.parse(layer.data);
                if(!ti.found)
                    throw new ReplaceException("could not locate the existing texture to replace");
                requireImage(nf, "tex");
                byte[] pre = slice(layer.data, 0, ti.lenFieldPos);
                byte[] post = slice(layer.data, ti.imageOffset + ti.imageLen, layer.data.length);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.writeBytes(pre);
                out.writeBytes(int32le(nf.length));
                out.writeBytes(nf);
                out.writeBytes(post);
                return out.toByteArray();
            }
            case "audio2": {
                AudioInfo ai = AudioInfo.parse(layer.data);
                if(ai.audioOffset <= 0 || ai.format == null)
                    throw new ReplaceException("could not locate the existing audio to replace");
                if(!startsWith(nf, 0x4F, 0x67, 0x67, 0x53))
                    throw new ReplaceException("replacement audio must be an Ogg Vorbis file (OggS)");
                return concat(slice(layer.data, 0, ai.audioOffset), nf);
            }
            case "font": {
                FontInfo fi = FontInfo.parse(layer.data);
                if(fi.fontOffset <= 0 || fi.format == null)
                    throw new ReplaceException("could not locate the existing font to replace");
                if(!isSfnt(nf))
                    throw new ReplaceException("replacement font must be TrueType/OpenType (sfnt)");
                return concat(slice(layer.data, 0, fi.fontOffset), nf);
            }
            case "midi":
                if(!startsWith(nf, 'M', 'T', 'h', 'd'))
                    throw new ReplaceException("replacement music must be a MIDI file (MThd)");
                return nf.clone();
            case "tooltip":
            case "pagina":
                return nf.clone();
            case "props":
                return encodeJson(nf, true);
            case "action":
                return encodeJson(nf, false);
            case "mat2":
                return encodeMat2(nf);
            case "anim":
                return encodeAnim(nf);
            case "neg":
                return encodeNeg(nf);
            default:
                throw new ReplaceException("layer '" + layer.name + "' is not replaceable");
        }
    }

    private static byte[] encodeJson(byte[] nf, boolean props) {
        String json = new String(nf, StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) Json.parse(json);
            return props ? PropsCodec.encode(model) : ActionCodec.encode(model);
        } catch(RuntimeException e) {
            throw new ReplaceException("invalid replacement JSON: " + e.getMessage());
        }
    }

    private static byte[] encodeMat2(byte[] nf) {
        String json = new String(nf, StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) Json.parse(json);
            return Mat2Codec.encode(model);
        } catch(RuntimeException e) {
            throw new ReplaceException("invalid replacement JSON: " + e.getMessage());
        }
    }

    private static byte[] encodeAnim(byte[] nf) {
        String json = new String(nf, StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) Json.parse(json);
            return AnimCodec.encode(model);
        } catch(RuntimeException e) {
            throw new ReplaceException("invalid replacement JSON: " + e.getMessage());
        }
    }

    private static byte[] encodeNeg(byte[] nf) {
        String json = new String(nf, StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) Json.parse(json);
            return NegCodec.encode(model);
        } catch(RuntimeException e) {
            throw new ReplaceException("invalid replacement JSON: " + e.getMessage());
        }
    }

    private static void requireImage(byte[] nf, String what) {
        if(ImageMagic.formatAt(nf, 0) == null)
            throw new ReplaceException("replacement " + what
                    + " must be a PNG/JPEG/GIF/BMP image");
    }

    private static boolean isSfnt(byte[] b) {
        return startsWith(b, 0x00, 0x01, 0x00, 0x00) || startsWith(b, 'O', 'T', 'T', 'O')
                || startsWith(b, 't', 'r', 'u', 'e') || startsWith(b, 't', 't', 'c', 'f');
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if(b.length < sig.length)
            return false;
        for(int i = 0; i < sig.length; i++) {
            if((b[i] & 0xff) != (sig[i] & 0xff))
                return false;
        }
        return true;
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch(NumberFormatException e) {
            throw new ReplaceException("invalid index in selector: '" + s + "'");
        }
    }

    private static String names(ResContainer res) {
        List<String> ns = new ArrayList<>();
        for(Layer l : res.layers)
            ns.add(l.name);
        return String.join(", ", ns);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, to - from);
        return r;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static byte[] int32le(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
