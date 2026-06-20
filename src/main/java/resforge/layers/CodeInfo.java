package resforge.layers;

import resforge.io.MessageReader;

/**
 * Read-only decoder for the {@code code} layer (from haven.Resource.Code): a
 * NUL-terminated class name followed by a compiled Java {@code .class} file
 * (the rest of the payload). The game ships server-authored JVM bytecode in
 * these layers and loads it through a custom class loader. This tool does not
 * execute or edit it — it only surfaces the class name and the class bytes so a
 * modder can see (or export and decompile) what a resource runs.
 */
public final class CodeInfo {
    public boolean recognized;
    public String name;            // fully-qualified class name, e.g. haven.res.lib.globfx.Effect
    public byte[] code;            // the embedded class-file bytes
    public boolean isClassFile;    // true if the bytes begin with the Java magic 0xCAFEBABE

    public static CodeInfo parse(byte[] payload) {
        CodeInfo ci = new CodeInfo();
        try {
            MessageReader in = new MessageReader(payload);
            ci.name = in.string();
            ci.code = in.bytes(in.remaining());
            ci.isClassFile = ci.code.length >= 4
                    && (ci.code[0] & 0xff) == 0xCA && (ci.code[1] & 0xff) == 0xFE
                    && (ci.code[2] & 0xff) == 0xBA && (ci.code[3] & 0xff) == 0xBE;
            ci.recognized = true;
        } catch(RuntimeException e) {
            ci.recognized = false;
        }
        return ci;
    }
}
