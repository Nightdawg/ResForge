package resforge.layers;

import resforge.io.MessageReader;

/**
 * Consumes (skips over) a single typed-object ({@code tto}) value from a stream,
 * covering the full type set of {@code haven.Message.tto0}. Used where a layer
 * embeds tto metadata that this tool does not need to interpret but must step
 * past to keep byte offsets aligned.
 */
public final class TtoSkip {
    private static final int T_END = 0, T_INT = 1, T_STR = 2, T_COORD = 3, T_UINT8 = 4,
            T_UINT16 = 5, T_COLOR = 6, T_FCOLOR = 7, T_TTOL = 8, T_INT8 = 9, T_INT16 = 10,
            T_NIL = 12, T_UID = 13, T_BYTES = 14, T_FLOAT32 = 15, T_FLOAT64 = 16,
            T_FCOORD32 = 18, T_FCOORD64 = 19, T_FLOAT8 = 21, T_FLOAT16 = 22,
            T_SNORM8 = 23, T_UNORM8 = 24, T_MNORM8 = 25, T_SNORM16 = 26, T_UNORM16 = 27,
            T_MNORM16 = 28, T_SNORM32 = 29, T_UNORM32 = 30, T_MNORM32 = 31, T_MAP = 32,
            T_LONG = 33, T_RESSPEC = 34, T_RESID = 35;

    private TtoSkip() {
    }

    /** Reads a type tag and skips its value. */
    public static void skipValue(MessageReader in) {
        skipTagged(in, in.uint8());
    }

    /** Skips a {@code tto} value list ({@code Message.list}) read until a
     *  {@code T_END} (0) tag or end of message. */
    public static void skipList(MessageReader in) {
        while(!in.eom()) {
            int t = in.uint8();
            if(t == T_END)
                break;
            skipTagged(in, t);
        }
    }

    private static void skipTagged(MessageReader in, int t) {
        switch(t) {
            case T_INT:      in.skip(4); break;
            case T_STR:      in.string(); break;
            case T_COORD:    in.skip(8); break;
            case T_UINT8:    in.skip(1); break;
            case T_UINT16:   in.skip(2); break;
            case T_COLOR:    in.skip(4); break;
            case T_FCOLOR:   in.skip(16); break;
            case T_TTOL:     skipList(in); break;
            case T_INT8:     in.skip(1); break;
            case T_INT16:    in.skip(2); break;
            case T_NIL:      break;
            case T_UID:      in.skip(8); break;
            case T_BYTES: {
                int len = in.uint8();
                if((len & 128) != 0)
                    len = in.int32();
                in.skip(len);
                break;
            }
            case T_FLOAT32:  in.skip(4); break;
            case T_FLOAT64:  in.skip(8); break;
            case T_FCOORD32: in.skip(8); break;
            case T_FCOORD64: in.skip(16); break;
            case T_FLOAT8:   in.skip(1); break;
            case T_FLOAT16:  in.skip(2); break;
            case T_SNORM8: case T_UNORM8: case T_MNORM8:    in.skip(1); break;
            case T_SNORM16: case T_UNORM16: case T_MNORM16: in.skip(2); break;
            case T_SNORM32: case T_UNORM32: case T_MNORM32: in.skip(4); break;
            case T_MAP:      skipList(in); break;
            case T_LONG:     in.skip(8); break;
            case T_RESSPEC:  in.string(); in.skip(2); break;
            case T_RESID:    in.skip(2); break;
            default:
                throw new IllegalStateException("unknown tto type tag: " + t);
        }
    }
}
