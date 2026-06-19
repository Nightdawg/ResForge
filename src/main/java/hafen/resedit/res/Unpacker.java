package hafen.resedit.res;

import hafen.resedit.layers.ActionCodec;
import hafen.resedit.layers.AudioInfo;
import hafen.resedit.layers.ImageInfo;
import hafen.resedit.layers.PropsCodec;
import hafen.resedit.layers.TexInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unpacks a .res file into an editable folder using the "parts" model:
 * every layer is written as one or more part files that, concatenated, equal
 * the original payload. This guarantees a byte-identical repack of unmodified
 * files while exposing common layers (images, text) in an editable form.
 */
public class Unpacker {
    private static final String LAYERS_SUBDIR = "layers";

    public static Manifest unpack(ResContainer res, Path outDir) throws IOException {
        Path layersDir = outDir.resolve(LAYERS_SUBDIR);
        Files.createDirectories(layersDir);

        Manifest manifest = new Manifest();
        manifest.version = res.version;

        for(int i = 0; i < res.layers.size(); i++) {
            Layer layer = res.layers.get(i);
            String base = String.format("%03d_%s", i, sanitize(layer.name));
            manifest.entries.add(writeParts(layer, base, outDir, layersDir));
        }

        manifest.write(outDir);
        return manifest;
    }

    private static Manifest.Entry writeParts(Layer layer, String base, Path outDir, Path layersDir) throws IOException {
        if(layer.name.equals("image")) {
            ImageInfo ii = ImageInfo.parse(layer.data);
            if(ii.imageOffset > 0 && ii.imageFormat != null && ii.imageOffset <= layer.data.length) {
                byte[] header = Arrays.copyOfRange(layer.data, 0, ii.imageOffset);
                byte[] image = Arrays.copyOfRange(layer.data, ii.imageOffset, layer.data.length);
                String hdrPart = LAYERS_SUBDIR + "/" + base + ".imghdr";
                String imgPart = LAYERS_SUBDIR + "/" + base + "." + ii.imageFormat;
                Files.write(outDir.resolve(hdrPart), header);
                Files.write(outDir.resolve(imgPart), image);
                return new Manifest.Entry(layer.name, new ArrayList<>(Arrays.asList(hdrPart, imgPart)));
            }
        } else if(layer.name.equals("tex")) {
            TexInfo ti = TexInfo.parse(layer.data);
            if(ti.found) {
                byte[] pre = Arrays.copyOfRange(layer.data, 0, ti.lenFieldPos);
                byte[] image = Arrays.copyOfRange(layer.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
                byte[] post = Arrays.copyOfRange(layer.data, ti.imageOffset + ti.imageLen, layer.data.length);
                String prePart = LAYERS_SUBDIR + "/" + base + ".pre.bin";
                String imgPart = LAYERS_SUBDIR + "/" + base + "." + ti.imageFormat;
                String postPart = LAYERS_SUBDIR + "/" + base + ".post.bin";
                Files.write(outDir.resolve(prePart), pre);
                Files.write(outDir.resolve(imgPart), image);
                Files.write(outDir.resolve(postPart), post);
                return new Manifest.Entry(layer.name,
                        new ArrayList<>(Arrays.asList(prePart, imgPart, postPart)), "tex");
            }
        } else if(layer.name.equals("audio2")) {
            AudioInfo ai = AudioInfo.parse(layer.data);
            if(ai.audioOffset > 0 && ai.format != null && ai.audioOffset <= layer.data.length) {
                byte[] header = Arrays.copyOfRange(layer.data, 0, ai.audioOffset);
                byte[] audio = Arrays.copyOfRange(layer.data, ai.audioOffset, layer.data.length);
                String hdrPart = LAYERS_SUBDIR + "/" + base + ".audhdr";
                String audPart = LAYERS_SUBDIR + "/" + base + "." + ai.format;
                Files.write(outDir.resolve(hdrPart), header);
                Files.write(outDir.resolve(audPart), audio);
                return new Manifest.Entry(layer.name, new ArrayList<>(Arrays.asList(hdrPart, audPart)));
            }
        } else if(layer.name.equals("props")) {
            String json = PropsCodec.toJsonIfLossless(layer.data);
            if(json != null) {
                String part = LAYERS_SUBDIR + "/" + base + ".json";
                Files.write(outDir.resolve(part), json.getBytes(StandardCharsets.UTF_8));
                return new Manifest.Entry(layer.name, new ArrayList<>(List.of(part)), "props");
            }
        } else if(layer.name.equals("action")) {
            String json = ActionCodec.toJsonIfLossless(layer.data);
            if(json != null) {
                String part = LAYERS_SUBDIR + "/" + base + ".json";
                Files.write(outDir.resolve(part), json.getBytes(StandardCharsets.UTF_8));
                return new Manifest.Entry(layer.name, new ArrayList<>(List.of(part)), "action");
            }
        } else if(layer.name.equals("tooltip") || layer.name.equals("pagina")) {
            String part = LAYERS_SUBDIR + "/" + base + ".txt";
            Files.write(outDir.resolve(part), layer.data);
            return new Manifest.Entry(layer.name, new ArrayList<>(List.of(part)));
        }
        String part = LAYERS_SUBDIR + "/" + base + ".bin";
        Files.write(outDir.resolve(part), layer.data);
        return new Manifest.Entry(layer.name, new ArrayList<>(List.of(part)));
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.length() == 0 ? "layer" : sb.toString();
    }

    public static String previewText(byte[] data, int max) {
        String s = new String(data, StandardCharsets.UTF_8);
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
