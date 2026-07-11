package resforge.audio;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes an Ogg Vorbis stream into raw PCM (interleaved signed 16-bit
 * little-endian) using the bundled JOrbis library. The whole stream is decoded
 * into memory, which makes seeking trivial for the in-app player. Adapted from
 * JOrbis's canonical {@code DecodeExample}.
 */
public final class OggVorbis {
    private OggVorbis() {
    }

    public static final class Pcm {
        public final byte[] data;     // interleaved signed 16-bit LE
        public final int rate;
        public final int channels;

        public Pcm(byte[] data, int rate, int channels) {
            this.data = data;
            this.rate = rate;
            this.channels = channels;
        }

        public int frames() {
            return data.length / (channels * 2);
        }

        public double seconds() {
            return frames() / (double) rate;
        }
    }

    public static Pcm decode(byte[] ogg) throws IOException {
        InputStream input = new ByteArrayInputStream(ogg);
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.max(64, Math.min((long) ogg.length * 4, 1 << 26)));   // cap initial guess at 64 MiB

        SyncState oy = new SyncState();
        StreamState os = new StreamState();
        Page og = new Page();
        Packet op = new Packet();
        Info vi = new Info();
        Comment vc = new Comment();
        DspState vd = new DspState();
        Block vb = new Block(vd);

        try {
        byte[] buffer;
        int bytes;
        oy.init();

        // ---- first page / first header packet ----
        int index = oy.buffer(4096);
        buffer = oy.data;
        bytes = readSome(input, buffer, index, 4096);
        oy.wrote(bytes);

        if(oy.pageout(og) != 1)
            throw new IOException("not an Ogg bitstream");

        os.init(og.serialno());
        vi.init();
        vc.init();
        if(os.pagein(og) < 0)
            throw new IOException("error reading first Ogg page");
        if(os.packetout(op) != 1)
            throw new IOException("error reading initial header packet");
        if(vi.synthesis_headerin(vc, op) < 0)
            throw new IOException("not Vorbis audio data");

        // ---- remaining two header packets ----
        int i = 0;
        while(i < 2) {
            while(i < 2) {
                int result = oy.pageout(og);
                if(result == 0)
                    break;
                if(result == 1) {
                    os.pagein(og);
                    while(i < 2) {
                        result = os.packetout(op);
                        if(result == 0)
                            break;
                        if(result == -1 || vi.synthesis_headerin(vc, op) < 0)
                            throw new IOException("corrupt secondary Vorbis header");
                        i++;
                    }
                }
            }
            index = oy.buffer(4096);
            buffer = oy.data;
            bytes = readSome(input, buffer, index, 4096);
            if(bytes == 0 && i < 2)
                throw new IOException("end of file before all Vorbis headers");
            oy.wrote(bytes);
        }

        int rate = vi.rate;
        int channels = vi.channels;
        if(channels < 1 || rate < 1)
            throw new IOException("invalid Vorbis stream (rate=" + rate + ", channels=" + channels + ")");

        int convsize = 4096 / channels;
        byte[] convbuffer = new byte[convsize * 2 * channels];

        vd.synthesis_init(vi);
        vb.init(vd);

        float[][][] pcmf = new float[1][][];
        int[] pcmIndex = new int[channels];

        boolean eos = false;
        while(!eos) {
            while(!eos) {
                int result = oy.pageout(og);
                if(result == 0)
                    break;          // need more data
                if(result != -1) {  // -1 == hole in data; skip but keep going
                    os.pagein(og);
                    while(true) {
                        result = os.packetout(op);
                        if(result == 0)
                            break;
                        if(result == -1)
                            continue;
                        if(vb.synthesis(op) == 0)
                            vd.synthesis_blockin(vb);
                        int samples;
                        while((samples = vd.synthesis_pcmout(pcmf, pcmIndex)) > 0) {
                            float[][] pcm = pcmf[0];
                            int bout = Math.min(samples, convsize);
                            for(int ch = 0; ch < channels; ch++) {
                                int ptr = ch * 2;
                                int mono = pcmIndex[ch];
                                for(int j = 0; j < bout; j++) {
                                    int val = (int) (pcm[ch][mono + j] * 32767.0);
                                    if(val > 32767)  val = 32767;
                                    if(val < -32768) val = -32768;
                                    if(val < 0)      val = val | 0x8000;
                                    convbuffer[ptr] = (byte) val;
                                    convbuffer[ptr + 1] = (byte) (val >>> 8);
                                    ptr += 2 * channels;
                                }
                            }
                            out.write(convbuffer, 0, 2 * channels * bout);
                            vd.synthesis_read(bout);
                        }
                    }
                    if(og.eos() != 0)
                        eos = true;
                }
            }
            if(!eos) {
                index = oy.buffer(4096);
                buffer = oy.data;
                bytes = readSome(input, buffer, index, 4096);
                oy.wrote(bytes);
                if(bytes == 0)
                    eos = true;
            }
        }

        return new Pcm(out.toByteArray(), rate, channels);
        } finally {
            os.clear();
            vb.clear();
            vd.clear();
            vi.clear();
            oy.clear();
        }
    }

    private static int readSome(InputStream in, byte[] buf, int off, int len) throws IOException {
        int n = in.read(buf, off, len);
        return n < 0 ? 0 : n;
    }
}
