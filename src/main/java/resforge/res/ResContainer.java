package resforge.res;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory model of a .res file: a version number plus an ordered list of
 * layers. Parsing and serialization are exact inverses (byte-for-byte) of the
 * container format used by haven.Resource.load().
 *
 * Format:
 *   "Haven Resource 1"   16-byte ASCII signature
 *   uint16 LE            resource version
 *   repeat until EOF:
 *       string           NUL-terminated UTF-8 layer name
 *       int32 LE         payload length
 *       <length bytes>   payload
 */
public class ResContainer {
    public static final byte[] SIGNATURE = "Haven Resource 1".getBytes(StandardCharsets.US_ASCII);

    public int version;
    public final List<Layer> layers = new ArrayList<>();

    public ResContainer(int version) {
        this.version = version;
    }

    public static ResContainer parse(byte[] raw) {
        MessageReader in = new MessageReader(raw);
        byte[] sig = in.bytes(SIGNATURE.length);
        if(!Arrays.equals(sig, SIGNATURE))
            throw new IllegalArgumentException("Invalid .res signature (not a Haven resource file)");
        ResContainer res = new ResContainer(in.uint16());
        while(!in.eom()) {
            String name = in.string();
            int len = in.int32();
            if(len < 0)
                throw new IllegalArgumentException("Negative layer length for '" + name + "'");
            res.layers.add(new Layer(name, in.bytes(len)));
        }
        return res;
    }

    public byte[] serialize() {
        MessageWriter out = new MessageWriter();
        out.bytes(SIGNATURE);
        out.uint16(version);
        for(Layer l : layers) {
            out.string(l.name);
            out.int32(l.data.length);
            out.bytes(l.data);
        }
        return out.toByteArray();
    }
}
