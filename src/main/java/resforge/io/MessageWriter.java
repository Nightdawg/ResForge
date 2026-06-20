package resforge.io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Sequential little-endian writer, matching haven.Message add* methods. */
public class MessageWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public MessageWriter int8(int v) {
        out.write(v & 0xff);
        return this;
    }

    public MessageWriter uint8(int v) {
        return int8(v);
    }

    public MessageWriter int16(int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        return this;
    }

    public MessageWriter uint16(int v) {
        return int16(v);
    }

    public MessageWriter int32(int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
        return this;
    }

    public MessageWriter int64(long v) {
        for(int i = 0; i < 8; i++)
            out.write((int) ((v >> (i * 8)) & 0xff));
        return this;
    }

    public MessageWriter float32(float v) {
        return int32(Float.floatToIntBits(v));
    }

    public MessageWriter float64(double v) {
        return int64(Double.doubleToLongBits(v));
    }

    /** NUL-terminated UTF-8 string. */
    public MessageWriter string(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return this;
    }

    public MessageWriter bytes(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    public MessageWriter bytes(byte[] b, int off, int len) {
        out.write(b, off, len);
        return this;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    public int size() {
        return out.size();
    }
}
