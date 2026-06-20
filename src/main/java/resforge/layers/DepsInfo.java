package resforge.layers;

import resforge.io.MessageReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only decoder for the {@code deps} layer (from haven.Resource): the
 * explicit list of other resources this one depends on. The payload is a
 * {@code uint8} version followed by, until end of message, a sequence of
 * {@code [string name, uint16 version]} records. The version is the minimum
 * resource version the client should load for that dependency.
 *
 * <p>This is purely informational — it tells a modder which other {@code .res}
 * files a resource needs at load time. The layer itself stays raw/lossless.
 */
public final class DepsInfo {
    public static final class Dep {
        public final String name;
        public final int ver;

        Dep(String name, int ver) {
            this.name = name;
            this.ver = ver;
        }
    }

    public boolean recognized;
    public boolean reachedEnd;
    public int ver;
    public final List<Dep> deps = new ArrayList<>();

    public static DepsInfo parse(byte[] payload) {
        DepsInfo di = new DepsInfo();
        try {
            MessageReader in = new MessageReader(payload);
            di.ver = in.uint8();
            while(!in.eom()) {
                String name = in.string();
                int ver = in.uint16();
                di.deps.add(new Dep(name, ver));
            }
            di.recognized = true;
            di.reachedEnd = in.eom();
        } catch(RuntimeException e) {
            di.recognized = false;
        }
        return di;
    }
}
