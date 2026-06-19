package hafen.resedit.res;

import hafen.resedit.layers.ImageInfo;

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
            List<String> parts = writeParts(layer, base, outDir, layersDir);
            manifest.entries.add(new Manifest.Entry(layer.name, parts));
        }

        manifest.write(outDir);
        return manifest;
    }

    private static List<String> writeParts(Layer layer, String base, Path outDir, Path layersDir) throws IOException {
        if(layer.name.equals("image")) {
            ImageInfo ii = ImageInfo.parse(layer.data);
            if(ii.imageOffset > 0 && ii.imageFormat != null && ii.imageOffset <= layer.data.length) {
                byte[] header = Arrays.copyOfRange(layer.data, 0, ii.imageOffset);
                byte[] image = Arrays.copyOfRange(layer.data, ii.imageOffset, layer.data.length);
                String hdrPart = LAYERS_SUBDIR + "/" + base + ".imghdr";
                String imgPart = LAYERS_SUBDIR + "/" + base + "." + ii.imageFormat;
                Files.write(outDir.resolve(hdrPart), header);
                Files.write(outDir.resolve(imgPart), image);
                return new ArrayList<>(Arrays.asList(hdrPart, imgPart));
            }
        } else if(layer.name.equals("tooltip") || layer.name.equals("pagina")) {
            String part = LAYERS_SUBDIR + "/" + base + ".txt";
            Files.write(outDir.resolve(part), layer.data);
            return new ArrayList<>(List.of(part));
        }
        String part = LAYERS_SUBDIR + "/" + base + ".bin";
        Files.write(outDir.resolve(part), layer.data);
        return new ArrayList<>(List.of(part));
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
