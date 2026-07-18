package resforge.layers;

import resforge.io.MessageReader;

import java.util.Arrays;

/**
 * Read-only decoder for the {@code mesh} (FastMesh) layer: a triangle-index list
 * referencing a {@code vbuf2} by id. It fully parses the header and the index
 * stream — including the delta-stripped form (haven.FastMesh.unstrip/decdelta) —
 * so the byte span lines up exactly with the payload ({@link #reachedEnd}), which
 * validates the decoder against real files.
 *
 * Old form (fl &amp; 0x80 == 0): uint16 num, int16 matid, optional id/ref/rdat/
 * vbufid, then num*3 indices (raw uint16, or delta-stripped if fl &amp; 32).
 * New form (fl &amp; 0x80 != 0, ver==1): int16 id, int16 vbufid, tto info map,
 * string fmt ("" raw | "strips"), uint16 num, indices.
 */
public class MeshInfo {
    public boolean recognized;
    public boolean reachedEnd;
    public int numTris;
    public int matid = -1;
    public int ref = -1;
    public int id = -1;
    public int vbufid = 0;
    public boolean modern;
    /** Verbatim modern tto metadata, including the terminating empty key. */
    public byte[] modernInfo;
    public boolean stripped;
    public short[] indices;          // num*3 vertex indices (decoded), or null

    public static MeshInfo parse(byte[] payload) {
        MeshInfo mi = new MeshInfo();
        try {
            MessageReader in = new MessageReader(payload);
            int fl = in.uint8();
            if((fl & 0x80) == 0) {
                int num = in.uint16();
                mi.matid = in.int16();
                mi.id = ((fl & 2) != 0) ? in.int16() : -1;
                mi.ref = ((fl & 4) != 0) ? in.int16() : -1;
                if((fl & 8) != 0) {
                    while(true) {
                        String k = in.string();
                        if(k.isEmpty()) break;
                        in.string();                          // value
                    }
                }
                mi.vbufid = ((fl & 16) != 0) ? in.int16() : 0;
                mi.stripped = (fl & 32) != 0;
                if((fl & ~63) != 0)
                    return mi;
                mi.numTris = num;
                mi.indices = readIndices(in, num, mi.stripped);
                mi.recognized = true;
            } else {
                int ver = fl & 0x7f;
                if(ver != 1)
                    return mi;
                mi.modern = true;
                mi.id = in.int16();
                mi.vbufid = in.int16();
                int infoStart = in.position();
                while(true) {
                    String k = in.string();
                    if(k.isEmpty()) break;
                    Integer v = TtoSkip.readIntegerValue(in);
                    if(v != null) {
                        if(k.equals("mat"))
                            mi.matid = v;
                        else if(k.equals("ref"))
                            mi.ref = v;
                    }
                }
                mi.modernInfo = Arrays.copyOfRange(payload, infoStart, in.position());
                String fmt = in.string();
                if(fmt.equals("")) {
                    mi.stripped = false;
                } else if(fmt.equals("strips")) {
                    mi.stripped = true;
                } else {
                    return mi;
                }
                mi.numTris = in.uint16();
                mi.indices = readIndices(in, mi.numTris, mi.stripped);
                mi.recognized = true;
            }
            mi.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            mi.reachedEnd = false;
        }
        return mi;
    }

    private static short[] readIndices(MessageReader in, int num, boolean stripped) {
        short[] ind = new short[num * 3];
        if(stripped) {
            unstrip(in, ind);
        } else {
            for(int i = 0; i < ind.length; i++)
                ind[i] = (short) in.uint16();
        }
        return ind;
    }

    /* Faithful port of haven.FastMesh.decdelta / unstrip. */
    private static int decdelta(MessageReader in, boolean[] pickp) {
        int b = in.uint8();
        if(pickp != null)
            pickp[0] = (b & 0x80) != 0;
        int ret = b & 0x3f;
        int bits = 6;
        boolean c = (b & 0x40) != 0;
        while(c) {
            b = in.uint8();
            c = (b & 0x80) != 0;
            ret |= (b & 0x7f) << bits;
            bits += 7;
        }
        ret = signExtend(ret, bits);
        return (ret >= 0) ? ret + 1 : ret;
    }

    private static int signExtend(int v, int bits) {
        if(bits >= 32)
            return v;
        int shift = 32 - bits;
        return (v << shift) >> shift;
    }

    private static void unstrip(MessageReader in, short[] ind) {
        int f = 0, o = 0, n = ind.length / 3;
        int[] face = new int[3], nface = new int[3];
        boolean[] pick = {false};
        while(f < n) {
            ind[o++] = (short) (face[0] = in.uint16());
            ind[o++] = (short) (face[1] = face[0] + decdelta(in, null));
            ind[o++] = (short) (face[2] = face[1] + decdelta(in, null));
            f++;
            int rn = in.uint8();
            for(int ri = 0; ri < rn; ri++) {
                nface[2] = face[2] + decdelta(in, pick);
                if(!pick[0]) {
                    nface[0] = face[0];
                    nface[1] = face[2];
                } else {
                    nface[0] = face[2];
                    nface[1] = face[1];
                }
                int[] t = face; face = nface; nface = t;
                for(int i = 0; i < 3; i++)
                    ind[o++] = (short) face[i];
                f++;
            }
        }
    }
}
