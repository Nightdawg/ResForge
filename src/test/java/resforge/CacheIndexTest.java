package resforge;

import resforge.net.CacheIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the HashDirCache reader used by "Open from game cache". */
class CacheIndexTest {

    /** Build a version-1 cache header exactly as the client's writehead does. */
    private static byte[] header(String cid, String name) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(1);
        out.writeUTF(cid);
        out.writeUTF(name);
        out.writeBytes("Haven Resource 1");   // a stand-in for the .res body
        return bo.toByteArray();
    }

    private static final String RENDER = "http://game.havenandhearth.com/render/";
    private static final String JAVA = "http://game.havenandhearth.com/java/";

    @Test
    void readsValidHeaderAndStripsResPrefix() throws IOException {
        Optional<CacheIndex.Header> h = CacheIndex.readHeader(header(RENDER, "res/gfx/borka/male"));
        assertTrue(h.isPresent());
        assertEquals(RENDER, h.get().cid);
        assertEquals("res/gfx/borka/male", h.get().name);
        assertEquals(Optional.of("gfx/borka/male"), CacheIndex.resourcePath(h.get()));
    }

    @Test
    void nonResourceEntriesYieldNoPath() throws IOException {
        CacheIndex.Header grid = CacheIndex.readHeader(header(JAVA, "map/abc/grid-123")).orElseThrow();
        assertEquals(Optional.empty(), CacheIndex.resourcePath(grid));
        // A bare "res/" with nothing after it is not a usable path.
        CacheIndex.Header bare = CacheIndex.readHeader(header(RENDER, "res/")).orElseThrow();
        assertEquals(Optional.empty(), CacheIndex.resourcePath(bare));
    }

    @Test
    void badInputReturnsEmptyNeverThrows() throws IOException {
        assertTrue(CacheIndex.readHeader(new byte[0]).isEmpty(), "empty input");
        assertTrue(CacheIndex.readHeader(new byte[]{2, 0, 0}).isEmpty(), "wrong version byte");
        assertTrue(CacheIndex.readHeader(new byte[]{1, 0x7f, (byte) 0xff}).isEmpty(),
                "version 1 but a truncated UTF length");
    }

    @Test
    void cacheFileNameFilterMatchesTheClientPattern() {
        assertTrue(CacheIndex.isCacheFileName("0007af003cf5d387.0"));
        assertTrue(CacheIndex.isCacheFileName("000624cd46a7e34f.10"));
        assertFalse(CacheIndex.isCacheFileName("0007af003cf5d387"), "no chain index");
        assertFalse(CacheIndex.isCacheFileName("0007af003cf5d387.x"), "non-digit index");
        assertFalse(CacheIndex.isCacheFileName("0007AF003CF5D387.0"), "uppercase hex");
        assertFalse(CacheIndex.isCacheFileName("manifest.json"));
    }

    @Test
    void scanCollectsSortedDistinctResourcePaths(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("0000000000000000.0"), header(RENDER, "res/gfx/borka/male"));
        Files.write(dir.resolve("0000000000000001.0"), header(RENDER, "res/gfx/borka/female"));
        Files.write(dir.resolve("0000000000000002.0"), header(RENDER, "res/paginae/craft/lanterns"));
        // A duplicate of an existing resource (different file / collision chain).
        Files.write(dir.resolve("0000000000000003.1"), header(RENDER, "res/gfx/borka/male"));
        // A map grid — not a fetchable resource.
        Files.write(dir.resolve("0000000000000004.0"), header(JAVA, "map/abc/grid-1"));
        // A non-cache filename — ignored even though its bytes are a valid header.
        Files.write(dir.resolve("notcache.bin"), header(RENDER, "res/should/be/ignored"));
        // A garbage file with a cache-like name — skipped, no throw.
        Files.write(dir.resolve("0000000000000005.0"), new byte[]{9, 9, 9});

        List<String> paths = CacheIndex.scan(dir);
        assertEquals(List.of("gfx/borka/female", "gfx/borka/male", "paginae/craft/lanterns"), paths);
    }

    @Test
    void scanOfMissingDirIsEmpty(@TempDir Path dir) throws IOException {
        assertTrue(CacheIndex.scan(dir.resolve("does-not-exist")).isEmpty());
    }

    @Test
    void defaultCacheDirEndsWithData() {
        CacheIndex.defaultCacheDir().ifPresent(p ->
                assertEquals("data", p.getFileName().toString()));
    }
}
