package resforge.res;

import resforge.io.Json;
import resforge.layers.ActionCodec;
import resforge.layers.Mat2Codec;
import resforge.layers.PropsCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Repacks an unpacked folder back into a .res file. Most layers use the "raw"
 * codec (their part files are simply concatenated). The "tex" codec reassembles
 * a texture layer as {@code pre + int32(len(image)) + image + post}, recomputing
 * the embedded image length so the texture can be swapped for one of any size.
 */
public class Packer {
    public static ResContainer pack(Path dir) throws IOException {
        Manifest manifest = Manifest.read(dir);
        ResContainer res = new ResContainer(manifest.version);
        for(Manifest.Entry e : manifest.entries)
            res.layers.add(new Layer(e.name, buildPayload(dir, e)));
        return res;
    }

    private static byte[] buildPayload(Path dir, Manifest.Entry e) throws IOException {
        if(e.codec.equals("tex")) {
            if(e.parts.size() != 3)
                throw new IOException("tex codec expects 3 parts (pre, image, post) for layer '"
                        + e.name + "', found " + e.parts.size());
            byte[] pre = read(dir, e.parts.get(0));
            byte[] image = read(dir, e.parts.get(1));
            byte[] post = read(dir, e.parts.get(2));
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.writeBytes(pre);
            payload.writeBytes(int32le(image.length));
            payload.writeBytes(image);
            payload.writeBytes(post);
            return payload.toByteArray();
        }
        if(e.codec.equals("props")) {
            if(e.parts.size() != 1)
                throw new IOException("props codec expects 1 part (json) for layer '"
                        + e.name + "', found " + e.parts.size());
            String json = new String(read(dir, e.parts.get(0)), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = (Map<String, Object>) Json.parse(json);
                return PropsCodec.encode(model);
            } catch(RuntimeException ex) {
                throw new IOException("Failed to encode props layer '" + e.name + "': " + ex.getMessage(), ex);
            }
        }
        if(e.codec.equals("action")) {
            if(e.parts.size() != 1)
                throw new IOException("action codec expects 1 part (json) for layer '"
                        + e.name + "', found " + e.parts.size());
            String json = new String(read(dir, e.parts.get(0)), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = (Map<String, Object>) Json.parse(json);
                return ActionCodec.encode(model);
            } catch(RuntimeException ex) {
                throw new IOException("Failed to encode action layer '" + e.name + "': " + ex.getMessage(), ex);
            }
        }
        if(e.codec.equals("mat2")) {
            if(e.parts.size() != 1)
                throw new IOException("mat2 codec expects 1 part (json) for layer '"
                        + e.name + "', found " + e.parts.size());
            String json = new String(read(dir, e.parts.get(0)), StandardCharsets.UTF_8);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = (Map<String, Object>) Json.parse(json);
                return Mat2Codec.encode(model);
            } catch(RuntimeException ex) {
                throw new IOException("Failed to encode mat2 layer '" + e.name + "': " + ex.getMessage(), ex);
            }
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for(String part : e.parts)
            payload.writeBytes(read(dir, part));
        return payload.toByteArray();
    }

    private static byte[] read(Path dir, String part) throws IOException {
        Path p = dir.resolve(part);
        if(!Files.exists(p))
            throw new IOException("Missing part file referenced by manifest: " + part);
        return Files.readAllBytes(p);
    }

    private static byte[] int32le(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
