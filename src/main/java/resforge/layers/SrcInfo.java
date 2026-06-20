package resforge.layers;

import resforge.io.MessageReader;

import java.nio.charset.StandardCharsets;

/**
 * Read-only decoder for the {@code src} layer (from haven.Resource): an embedded
 * source file. The payload is a {@code uint8} version, a NUL-terminated file
 * name (e.g. {@code Tree.java}), and then the file's bytes (the rest of the
 * payload). Resources that ship server-authored code (see {@link CodeInfo})
 * also carry the corresponding pre-processed Java source in {@code src} layers.
 *
 * <p>This tool neither compiles nor edits the source; it only surfaces the file
 * name and contents so a modder can read what a resource is built from. The
 * layer itself stays raw/lossless.
 */
public final class SrcInfo {
    public boolean recognized;
    public int ver;
    public String fileName;
    public byte[] source;          // the embedded file bytes

    public static SrcInfo parse(byte[] payload) {
        SrcInfo si = new SrcInfo();
        try {
            MessageReader in = new MessageReader(payload);
            si.ver = in.uint8();
            si.fileName = in.string();
            si.source = in.bytes(in.remaining());
            si.recognized = true;
        } catch(RuntimeException e) {
            si.recognized = false;
        }
        return si;
    }

    /** The embedded source decoded as UTF-8 text. */
    public String text() {
        return source == null ? "" : new String(source, StandardCharsets.UTF_8);
    }
}
