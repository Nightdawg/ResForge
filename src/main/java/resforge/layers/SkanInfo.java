package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code skan} layer (from haven.Skeleton.ResPose): a
 * skeletal animation. It has an id, a length, a wrap mode (once / loop / pong /
 * pong-loop), an optional normalised speed, and a set of per-bone keyframe
 * tracks plus optional effect ({@code {ctl}}) tracks that fire events (spawn a
 * sprite, trigger, add/remove an overlay) at given times.
 *
 * <p>The encoding has two formats selected by a flag: format 0 uses custom-packed
 * floats ({@code cpfloat}); format 1 uses quantised values (unorm/half/mnorm/
 * snorm). This decoder walks the whole structure to report counts and the
 * resources referenced by effect events; it neither plays nor edits the
 * animation, and the layer stays raw/lossless.
 */
public final class SkanInfo {
    public static final class Track {
        public final String bone;
        public final int frames;

        Track(String bone, int frames) {
            this.bone = bone;
            this.frames = frames;
        }
    }

    public static final class Fx {
        public final int events;
        public final List<String> refs;   // resources spawned/overlaid by this track

        Fx(int events, List<String> refs) {
            this.events = events;
            this.refs = refs;
        }
    }

    private static final String[] MODES = {"once", "loop", "pong", "pong-loop"};

    public boolean recognized;
    public boolean reachedEnd;
    public int id;
    public int fmt;
    public String mode = "?";
    public float len;
    public double nspeed = -1;
    public final List<Track> tracks = new ArrayList<>();
    public final List<Fx> fxTracks = new ArrayList<>();

    public static SkanInfo parse(byte[] payload) {
        SkanInfo si = new SkanInfo();
        try {
            MessageReader in = new MessageReader(payload);
            si.id = in.int16();
            int fl = in.uint8();
            si.fmt = (fl & 6) >> 1;
            int mode = in.uint8();
            if(mode < 0 || mode > 3)
                throw new IllegalStateException("animation mode " + mode);
            si.mode = MODES[mode];
            si.len = (si.fmt == 0) ? (float) in.cpfloat() : in.float32();
            if((fl & 1) != 0)
                si.nspeed = (si.fmt == 0) ? in.cpfloat() : in.float32();
            while(!in.eom()) {
                String bnm = in.string();
                if(bnm.equals("{ctl}"))
                    si.fxTracks.add(parseFx(si.fmt, si.len, in));
                else
                    si.tracks.add(new Track(bnm, parseFrames(si.fmt, in)));
            }
            si.recognized = true;
            si.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            si.recognized = false;
        }
        return si;
    }

    private static int parseFrames(int fmt, MessageReader in) {
        int n = in.uint16();
        for(int i = 0; i < n; i++) {
            if(fmt == 0) {
                in.cpfloat();                       // time
                in.cpfloat(); in.cpfloat(); in.cpfloat();   // translation
                in.cpfloat();                       // rotation angle
                in.cpfloat(); in.cpfloat(); in.cpfloat();   // rotation axis
            } else {
                in.uint16();                        // time (unorm16 * len)
                in.int16(); in.int16(); in.int16(); // translation (half float)
                in.uint16();                        // angle (mnorm16)
                in.int16(); in.int16();             // axis (oct snorm16)
            }
        }
        return n;
    }

    private static Fx parseFx(int fmt, float len, MessageReader in) {
        int n = in.uint16();
        List<String> refs = new ArrayList<>();
        for(int i = 0; i < n; i++) {
            if(fmt == 0)
                in.cpfloat();
            else
                in.uint16();
            int t = in.uint8();
            MessageReader sub = in;
            boolean exhaust = false;
            if((t & 0x80) != 0) {
                sub = new MessageReader(in.bytes(in.uint16()));
                t &= 0x7f;
                exhaust = true;
            }
            switch(t) {
                case 0:
                case 2: {
                    String resnm = sub.string();
                    sub.uint16();                       // resource version
                    sub.bytes(sub.uint8());             // sprite data
                    int fl = (t == 2) ? sub.uint8() : 0;
                    if((fl & 1) != 0)
                        sub.string();                   // equip-point name
                    refs.add(resnm);
                    break;
                }
                case 1:
                    sub.string();                       // trigger id
                    break;
                case 3: {
                    sub.uint8();                        // flags
                    sub.string();                      // overlay id
                    String resnm = sub.string();
                    sub.uint16();
                    sub.bytes(sub.uint8());
                    refs.add(resnm);
                    break;
                }
                case 4:
                    sub.string();                       // overlay id to remove
                    break;
                default:
                    if(!exhaust)
                        throw new IllegalStateException("control event " + t);
            }
        }
        return new Fx(n, refs);
    }

    public int totalFrames() {
        int n = 0;
        for(Track t : tracks)
            n += t.frames;
        return n;
    }
}
