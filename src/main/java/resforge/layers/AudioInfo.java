package resforge.layers;

import resforge.io.MessageReader;

/**
 * Parses an {@code audio2} layer header and locates the embedded Ogg Vorbis
 * stream so the layer can be split into a verbatim header part and a
 * replaceable {@code .ogg} part.
 *
 * Audio layer format (from haven.Resource.Audio):
 * <pre>
 *   uint8  ver                       (1..3)
 *   string id
 *   if ver == 2: uint16 vol          (bvol = vol * 0.001)
 *   if ver >= 3: repeated [string key; tto value] until key==""
 *   &lt;coded audio: an Ogg Vorbis stream, read to end of payload&gt;
 * </pre>
 *
 * The audio runs to the end of the payload (like {@code image}), so the split
 * is a plain header/tail cut — no length recomputation is needed and the parts
 * concatenate back exactly. For ver 1/2 the header is parsed precisely; for the
 * rarer ver 3 (typed metadata) the split is found by scanning for the {@code
 * OggS} signature, which is also used to validate the parsed offset.
 */
public class AudioInfo {
    public boolean recognized;
    public int ver;
    public String id = "";
    public double bvol = 1.0;
    public int audioOffset = -1;   // byte index where the Ogg stream begins
    public String format;          // "ogg" or null

    public static AudioInfo parse(byte[] payload) {
        AudioInfo ai = new AudioInfo();
        try {
            MessageReader in = new MessageReader(payload);
            int ver = in.uint8();
            if(ver >= 1 && ver <= 3) {
                ai.ver = ver;
                ai.recognized = true;
                ai.id = in.string();
                if(ver == 2)
                    ai.bvol = in.uint16() * 0.001;
                if(ver <= 2)
                    ai.audioOffset = in.position();
                /* ver 3 carries a typed metadata map; locate the audio by signature instead. */
            }
        } catch(RuntimeException e) {
            ai.recognized = false;
        }

        if(!oggAt(payload, ai.audioOffset))
            ai.audioOffset = findOgg(payload, 1);
        ai.format = oggAt(payload, ai.audioOffset) ? "ogg" : null;
        if(ai.format == null)
            ai.audioOffset = -1;
        return ai;
    }

    private static int findOgg(byte[] b, int from) {
        for(int i = Math.max(0, from); i <= b.length - 4; i++) {
            if(oggAt(b, i))
                return i;
        }
        return -1;
    }

    private static boolean oggAt(byte[] b, int i) {
        if(i < 0 || i + 4 > b.length)
            return false;
        return (b[i] & 0xff) == 0x4F && (b[i + 1] & 0xff) == 0x67
                && (b[i + 2] & 0xff) == 0x67 && (b[i + 3] & 0xff) == 0x53;
    }
}
