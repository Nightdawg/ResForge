package resforge.layers;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.Arrays;

/**
 * Reads and re-encodes the editable fields of an {@code audio2} layer header
 * (version 1 or 2) so the GUI can change a clip's id or its volume without
 * disturbing the embedded Ogg Vorbis stream.
 *
 * <p>Header (from haven.Resource.Audio, ver 1–2):
 * <pre>
 *   uint8  ver            (1 or 2)
 *   string id
 *   if ver == 2: uint16 vol     (bvol = vol * 0.001)
 *   &lt;Ogg Vorbis bytes, to end of payload&gt;
 * </pre>
 *
 * Version 3 carries a typed (tto) metadata map and is not handled here (the GUI
 * shows it read-only). Editing is offered only when a straight re-encode
 * reproduces the original bytes ({@link #editable}); the audio stream is kept
 * verbatim, so an unchanged header round-trips exactly.
 */
public final class AudioHeaderCodec {
    public int ver;
    public String id = "";
    public int vol = 1000;       // raw uint16; bvol = vol * 0.001
    public boolean hasVol;       // true for ver == 2
    public boolean editable;     // ver 1–2 that re-encodes byte-for-byte

    private byte[] audio = new byte[0];   // verbatim Ogg stream

    public static AudioHeaderCodec parse(byte[] payload) {
        AudioHeaderCodec h = new AudioHeaderCodec();
        try {
            MessageReader in = new MessageReader(payload);
            int ver = in.uint8();
            if(ver < 1 || ver > 2)
                return h;                    // ver 3 (typed) or unknown: not editable here
            h.ver = ver;
            h.id = in.string();
            if(ver == 2) {
                h.vol = in.uint16();
                h.hasVol = true;
            }
            int audioStart = in.position();
            h.audio = Arrays.copyOfRange(payload, audioStart, payload.length);
            h.editable = Arrays.equals(payload, h.encode());
        } catch(RuntimeException e) {
            h.editable = false;
        }
        return h;
    }

    /** The volume as a multiplier (bvol = vol/1000). */
    public double bvol() {
        return vol * 0.001;
    }

    /** Re-encodes the header (current field values) + the verbatim audio stream. */
    public byte[] encode() {
        MessageWriter out = new MessageWriter();
        out.uint8(ver);
        out.string(id);
        if(hasVol)
            out.uint16(vol);
        out.bytes(audio);
        return out.toByteArray();
    }

    /**
     * Applies a new id and (for ver 2) a new raw volume, returning the new
     * payload. {@code vol} is ignored for ver-1 clips (which have no volume
     * field). Throws if {@code vol} is outside the {@code uint16} range.
     */
    public byte[] encodeWith(String id, int vol) {
        if(id == null)
            throw new IllegalArgumentException("id must not be null");
        if(hasVol && (vol < 0 || vol > 65535))
            throw new IllegalArgumentException("volume must be in [0, 65535] (bvol 0.000–65.535)");
        this.id = id;
        if(hasVol)
            this.vol = vol;
        return encode();
    }

    /** Convenience: set the volume from a bvol multiplier (rounded to the raw uint16). */
    public byte[] encodeWithBvol(String id, double bvol) {
        long raw = Math.round(bvol * 1000.0);
        return encodeWith(id, (int) raw);
    }
}
