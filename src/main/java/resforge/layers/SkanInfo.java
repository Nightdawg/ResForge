package resforge.layers;

import resforge.io.MessageReader;
import resforge.io.MessageWriter;

import java.util.ArrayList;
import java.util.Arrays;
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
 * resources referenced by effect events. Edited bone tracks can be encoded in
 * the original wire format while retaining effect tracks byte-for-byte.
 */
public final class SkanInfo {
    public static final class Track {
        public final String bone;
        public final int frames;
        public final float[] times;        // per-frame time (seconds)
        public final float[][] trans;      // per-frame local translation offset [x,y,z]
        public final float[][] rot;        // per-frame local rotation quaternion [w,x,y,z]

        public Track(String bone, float[] times, float[][] trans, float[][] rot) {
            if(times.length != trans.length || times.length != rot.length)
                throw new IllegalArgumentException("skan track arrays have different lengths");
            this.bone = bone;
            this.frames = times.length;
            this.times = times;
            this.trans = trans;
            this.rot = rot;
        }
    }

    public static final class Fx {
        public final int events;
        public final List<String> refs;   // resources spawned/overlaid by this track
        public final byte[] rawPayload;   // event count + events, in the layer's original format

        Fx(int events, List<String> refs, byte[] rawPayload) {
            this.events = events;
            this.refs = refs;
            this.rawPayload = rawPayload;
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
                if(bnm.equals("{ctl}")) {
                    int start = in.position();
                    Fx fx = parseFx(si.fmt, si.len, in);
                    si.fxTracks.add(new Fx(fx.events, fx.refs,
                            Arrays.copyOfRange(payload, start, in.position())));
                } else {
                    si.tracks.add(parseFrames(bnm, si.fmt, si.len, in));
                }
            }
            si.recognized = true;
            si.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            si.recognized = false;
        }
        return si;
    }

    private static Track parseFrames(String bone, int fmt, float len, MessageReader in) {
        int n = in.uint16();
        float[] times = new float[n];
        float[][] trans = new float[n][];
        float[][] rot = new float[n][];
        for(int i = 0; i < n; i++) {
            float tm;
            float[] tr = new float[3];
            float ang;
            float[] ax = new float[3];
            if(fmt == 0) {
                tm = (float) in.cpfloat();
                tr[0] = (float) in.cpfloat(); tr[1] = (float) in.cpfloat(); tr[2] = (float) in.cpfloat();
                ang = (float) in.cpfloat();
                ax[0] = (float) in.cpfloat(); ax[1] = (float) in.cpfloat(); ax[2] = (float) in.cpfloat();
            } else {
                tm = in.unorm16() * len;
                tr[0] = in.float16(); tr[1] = in.float16(); tr[2] = in.float16();
                ang = in.mnorm16() * 2 * (float) Math.PI;
                MessageReader.oct2uvec(ax, in.snorm16(), in.snorm16());
            }
            times[i] = tm;
            trans[i] = tr;
            float s = (float) Math.sin(ang / 2.0), c = (float) Math.cos(ang / 2.0);
            rot[i] = new float[]{c, s * ax[0], s * ax[1], s * ax[2]};
        }
        return new Track(bone, times, trans, rot);
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
        return new Fx(n, refs, new byte[0]);
    }

    /**
     * Encodes edited tracks using the layer's original format. Effect-track payloads
     * are copied verbatim, so unknown length-delimited events remain lossless.
     */
    public static byte[] encode(SkanInfo info) {
        if(info.fmt != 0 && info.fmt != 1)
            throw new IllegalArgumentException("cannot encode skan format " + info.fmt);
        if(!Float.isFinite(info.len) || info.len <= 0)
            throw new IllegalArgumentException("skan length must be positive and finite");
        int mode = modeCode(info.mode);
        boolean hasSpeed = info.nspeed >= 0;
        MessageWriter w = new MessageWriter();
        w.int16(info.id).uint8((info.fmt << 1) | (hasSpeed ? 1 : 0)).uint8(mode);
        writeNumber(w, info.fmt, info.len);
        if(hasSpeed)
            writeNumber(w, info.fmt, info.nspeed);
        for(Track track : info.tracks)
            writeTrack(w, info, track);
        for(Fx fx : info.fxTracks) {
            if(fx.rawPayload.length == 0)
                throw new IllegalArgumentException("skan effect track has no raw payload");
            w.string("{ctl}").bytes(fx.rawPayload);
        }
        return w.toByteArray();
    }

    private static void writeTrack(MessageWriter w, SkanInfo info, Track track) {
        if(track.frames > 0xffff)
            throw new IllegalArgumentException("skan track has too many frames: " + track.frames);
        w.string(track.bone).uint16(track.frames);
        float[] oct = new float[2];
        for(int i = 0; i < track.frames; i++) {
            float time = track.times[i];
            float[] tr = track.trans[i], q = track.rot[i];
            if(tr == null || tr.length != 3 || q == null || q.length != 4)
                throw new IllegalArgumentException("invalid skan frame in track " + track.bone);
            if(info.fmt == 0) {
                w.cpfloat(time);
                for(float v : tr)
                    w.cpfloat(v);
                float[] aa = axisAngle(q);
                w.cpfloat(aa[3]).cpfloat(aa[0]).cpfloat(aa[1]).cpfloat(aa[2]);
            } else {
                int tq = Math.round(Math.max(0f, Math.min(1f, time / info.len)) * 0xffff);
                w.uint16(tq);
                for(float v : tr)
                    w.float16(v);
                float[] aa = axisAngle(q);
                double turn = aa[3] / (2 * Math.PI);
                turn -= Math.floor(turn);
                w.uint16((int) Math.round(turn * 0x10000) & 0xffff);
                uvec2oct(oct, aa[0], aa[1], aa[2]);
                w.int16(snorm16(oct[0])).int16(snorm16(oct[1]));
            }
        }
    }

    private static void writeNumber(MessageWriter w, int fmt, double value) {
        if(!Double.isFinite(value))
            throw new IllegalArgumentException("non-finite skan value");
        if(fmt == 0)
            w.cpfloat(value);
        else
            w.float32((float) value);
    }

    private static int modeCode(String mode) {
        for(int i = 0; i < MODES.length; i++)
            if(MODES[i].equals(mode))
                return i;
        throw new IllegalArgumentException("unknown skan mode " + mode);
    }

    /** Returns unit axis xyz + angle radians, treating q and -q as the same rotation. */
    private static float[] axisAngle(float[] q) {
        double n = Math.sqrt((double) q[0] * q[0] + (double) q[1] * q[1]
                + (double) q[2] * q[2] + (double) q[3] * q[3]);
        if(!Double.isFinite(n) || n == 0)
            throw new IllegalArgumentException("invalid skan rotation quaternion");
        double w = q[0] / n, x = q[1] / n, y = q[2] / n, z = q[3] / n;
        if(w < 0) {
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }
        w = Math.max(-1, Math.min(1, w));
        double s = Math.sqrt(x * x + y * y + z * z);
        if(s < 1e-12)
            return new float[]{0, 0, 1, 0};
        return new float[]{(float) (x / s), (float) (y / s), (float) (z / s),
                (float) (2 * Math.atan2(s, w))};
    }

    private static int snorm16(float v) {
        int q = Math.round(Math.max(-1f, Math.min(1f, v)) * 0x7fff);
        return Math.max(-0x7fff, Math.min(0x7fff, q));
    }

    private static void uvec2oct(float[] out, float x, float y, float z) {
        float m = 1.0f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float hx = x * m, hy = y * m;
        if(z >= 0) {
            out[0] = hx;
            out[1] = hy;
        } else {
            out[0] = (1 - Math.abs(hy)) * Math.copySign(1, hx);
            out[1] = (1 - Math.abs(hx)) * Math.copySign(1, hy);
        }
    }

    public int totalFrames() {
        int n = 0;
        for(Track t : tracks)
            n += t.frames;
        return n;
    }
}
