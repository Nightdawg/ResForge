package resforge.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads Haven &amp; Hearth's local resource cache (a {@code HashDirCache}) to
 * recover the <em>names</em> of the resources the player already has on disk, so
 * they can be re-fetched fresh from the server (we never open the cached bytes —
 * the point is always to get the latest version).
 *
 * <p>The cache lives in {@code <localdir>/data}, where {@code localdir} is
 * {@code %APPDATA%\Haven and Hearth} on Windows and {@code ~/.haven} elsewhere
 * (mirroring the client's {@code Config.localdir()}). Each cache file is named
 * {@code %016x.%d} (a name-hash plus a collision-chain index) and begins with a
 * small header written by the client's {@code HashDirCache.writehead}:
 *
 * <pre>
 *   writeByte(1)        version byte
 *   writeUTF(cid)       cache-identity URI (the base URL)
 *   writeUTF(name)      resource name, e.g. "res/gfx/borka/male"
 *   &lt;the .res bytes&gt;     (ignored here — we only want the name)
 * </pre>
 *
 * <p>{@code writeUTF} is Java's modified-UTF-8 with a 16-bit big-endian length,
 * so it is decoded with {@link DataInputStream#readUTF()} — the same routine the
 * client uses. Resource entries carry a {@code name} starting with {@code "res/"}
 * (the rest of the cache is map grids and other non-resource data, which is
 * skipped); stripping that prefix yields exactly the path
 * {@link ResourceFetcher#urlFor(String, String)} expects.
 *
 * <p>This is implemented from the documented cache format using the JDK API; no
 * third-party code is used.
 */
public final class CacheIndex {
    private static final int INDEX_MAGIC = 0x52464958; // RFIX
    private static final int INDEX_VERSION = 1;
    private static final int MAX_INDEX_NAMES = 1_000_000;
    private static final long MAX_INDEX_BYTES = 64L * 1024 * 1024;

    private CacheIndex() {}

    /** Resource names in the cache are prefixed with this; stripped to get the path. */
    public static final String RES_PREFIX = "res/";

    /** Prefix of dynamic, account-attached resources (server-generated; they may be
     *  removed server-side and aren't reliably re-fetchable). Sorted last. */
    public static final String DYN_PREFIX = "dyn/";

    /** True if {@code path} is a dynamic, account-attached resource (under {@code dyn/}). */
    public static boolean isDynamic(String path) {
        return path != null && path.startsWith(DYN_PREFIX);
    }

    /** Ordering for listed resources: stable resources first (alphabetical), then
     *  the dynamic {@code dyn/} ones (alphabetical), so the volatile entries sink
     *  to the bottom of any list. */
    public static final java.util.Comparator<String> ORDER =
            java.util.Comparator.comparing(CacheIndex::isDynamic)
                    .thenComparing(java.util.Comparator.naturalOrder());

    /** A decoded cache-file header: the cache identity and the resource name. */
    public static final class Header {
        public final String cid;
        public final String name;

        public Header(String cid, String name) {
            this.cid = cid;
            this.name = name;
        }
    }

    /** The resource paths loaded for the picker, including whether the saved index
     *  was reused and any non-fatal index read/write warning. */
    public static final class ScanResult {
        public final List<String> paths;
        public final boolean reusedIndex;
        public final String warning;

        private ScanResult(List<String> paths, boolean reusedIndex, String warning) {
            this.paths = List.copyOf(paths);
            this.reusedIndex = reusedIndex;
            this.warning = warning;
        }
    }

    /** The default Haven cache {@code data} directory for this OS, or
     *  {@link Optional#empty()} if it can't be determined. Existence is not
     *  required (the caller decides what to do if it's missing). */
    public static Optional<Path> defaultCacheDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if(os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if(appData != null && !appData.isBlank())
                return Optional.of(Path.of(appData, "Haven and Hearth", "data"));
        }
        String home = System.getProperty("user.home");
        if(home != null && !home.isBlank())
            return Optional.of(Path.of(home, ".haven", "data"));
        return Optional.empty();
    }

    /** The private ResForge index file used to avoid reopening every cache entry
     *  when the game cache directory has not changed. */
    public static Optional<Path> defaultIndexFile() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if(os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if(localAppData != null && !localAppData.isBlank())
                return Optional.of(Path.of(localAppData, "ResForge", "cache-index-v1.bin"));
        }
        String home = System.getProperty("user.home");
        if(home != null && !home.isBlank())
            return Optional.of(Path.of(home, ".cache", "resforge", "cache-index-v1.bin"));
        return Optional.empty();
    }

    /** True if {@code fileName} matches a HashDirCache entry: 16 lowercase-hex
     *  characters, a dot, then a decimal chain index (e.g. {@code 0007af00….0}). */
    public static boolean isCacheFileName(String fileName) {
        int dot = 16;
        if(fileName.length() < 18 || fileName.charAt(dot) != '.')
            return false;
        for(int i = 0; i < dot; i++) {
            char c = fileName.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if(!hex)
                return false;
        }
        for(int i = dot + 1; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if(c < '0' || c > '9')
                return false;
        }
        return true;
    }

    /** Decode a cache file's header from its leading bytes. Returns
     *  {@link Optional#empty()} for anything that isn't a version-1 header
     *  (truncated, wrong version, malformed UTF) — never throws on bad input. */
    public static Optional<Header> readHeader(byte[] bytes) {
        return readHeader(new ByteArrayInputStream(bytes));
    }

    /** As {@link #readHeader(byte[])} but reads only as far into {@code in} as the
     *  header requires, so a large cache file's body is never loaded. */
    public static Optional<Header> readHeader(InputStream in) {
        try {
            DataInputStream din = new DataInputStream(in);
            int ver = din.readByte();
            if(ver != 1)
                return Optional.empty();
            String cid = din.readUTF();
            String name = din.readUTF();
            return Optional.of(new Header(cid, name));
        } catch(IOException e) {
            return Optional.empty();
        }
    }

    /** If {@code h} names a fetchable resource (its name starts with
     *  {@code "res/"}), return the resource path with that prefix stripped;
     *  otherwise {@link Optional#empty()} (map grids, dynamic data, etc.). */
    public static Optional<String> resourcePath(Header h) {
        if(h != null && h.name != null && h.name.startsWith(RES_PREFIX)) {
            String path = h.name.substring(RES_PREFIX.length());
            if(!path.isEmpty())
                return Optional.of(path);
        }
        return Optional.empty();
    }

    /** Scan a cache directory and return the sorted, de-duplicated resource paths
     *  it holds (ready to hand to {@link ResourceFetcher}). Non-cache files,
     *  unreadable files, and non-resource entries (map grids, …) are skipped.
     *  Files are read in parallel (a cache holds tens of thousands of entries). */
    public static List<String> scan(Path dir) throws IOException {
        if(!Files.isDirectory(dir))
            return new ArrayList<>();
        List<Path> files;
        try(Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> {
                Path fn = f.getFileName();
                return fn != null && isCacheFileName(fn.toString());
            }).collect(Collectors.toList());
        }
        TreeSet<String> paths = files.parallelStream()
                .map(CacheIndex::resourcePathOf)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toCollection(() -> new TreeSet<>(ORDER)));
        return new ArrayList<>(paths);
    }

    /** Load resource paths from ResForge's saved index when the cache directory is
     *  unchanged; otherwise perform a full scan and refresh the index. */
    public static ScanResult scanCached(Path dir) throws IOException {
        Optional<Path> indexFile = defaultIndexFile();
        if(indexFile.isEmpty())
            return new ScanResult(scan(dir), false, null);
        return scanCached(dir, indexFile.get());
    }

    /** As {@link #scanCached(Path)}, with an explicit index file for tests and
     *  callers that manage their own application cache location. */
    public static ScanResult scanCached(Path dir, Path indexFile) throws IOException {
        if(!Files.isDirectory(dir))
            return new ScanResult(new ArrayList<>(), false, null);

        Path source = dir.toAbsolutePath().normalize();
        long stamp = Files.getLastModifiedTime(source).toMillis();
        String warning = null;
        try {
            Optional<List<String>> saved = readIndex(indexFile, source, stamp);
            if(saved.isPresent())
                return new ScanResult(saved.get(), true, null);
        } catch(IOException e) {
            warning = "saved cache index could not be read: " + messageOf(e);
        }

        List<String> paths = scan(source);
        long scannedStamp = Files.getLastModifiedTime(source).toMillis();
        if(stamp == scannedStamp) {
            try {
                writeIndex(indexFile, source, stamp, paths);
            } catch(IOException e) {
                String writeWarning = "cache index could not be saved: " + messageOf(e);
                warning = warning == null ? writeWarning : warning + "; " + writeWarning;
            }
        }
        return new ScanResult(paths, false, warning);
    }

    private static Optional<List<String>> readIndex(Path indexFile, Path source, long stamp)
            throws IOException {
        if(!Files.isRegularFile(indexFile))
            return Optional.empty();
        if(Files.size(indexFile) > MAX_INDEX_BYTES)
            throw new IOException("index exceeds " + MAX_INDEX_BYTES + " bytes");

        try(DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(indexFile)))) {
            if(in.readInt() != INDEX_MAGIC || in.readInt() != INDEX_VERSION)
                throw new IOException("unrecognised index format");
            String savedSource = in.readUTF();
            long savedStamp = in.readLong();
            if(!savedSource.equals(source.toString()) || savedStamp != stamp)
                return Optional.empty();

            int count = in.readInt();
            if(count < 0 || count > MAX_INDEX_NAMES)
                throw new IOException("invalid resource count " + count);
            List<String> paths = new ArrayList<>(count);
            String previous = null;
            for(int i = 0; i < count; i++) {
                String path = in.readUTF();
                if(path.isEmpty() || (previous != null && ORDER.compare(previous, path) >= 0))
                    throw new IOException("resource names are not strictly sorted");
                paths.add(path);
                previous = path;
            }
            if(in.read() != -1)
                throw new IOException("trailing index data");
            return Optional.of(paths);
        } catch(EOFException e) {
            throw new IOException("truncated index", e);
        }
    }

    private static void writeIndex(Path indexFile, Path source, long stamp, List<String> paths)
            throws IOException {
        Path target = indexFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if(parent == null)
            throw new IOException("index path has no parent");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "cache-index-", ".tmp");
        try {
            try(DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(INDEX_MAGIC);
                out.writeInt(INDEX_VERSION);
                out.writeUTF(source.toString());
                out.writeLong(stamp);
                out.writeInt(paths.size());
                for(String path : paths)
                    out.writeUTF(path);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch(java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String messageOf(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Read one cache file and return its fetchable resource path, if any. */
    private static Optional<String> resourcePathOf(Path file) {
        try(InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return readHeader(in).flatMap(CacheIndex::resourcePath);
        } catch(IOException e) {
            return Optional.empty();
        }
    }
}
