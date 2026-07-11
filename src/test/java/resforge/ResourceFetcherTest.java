package resforge;

import resforge.net.ResourceFetcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceFetcherTest {
    @Test
    void buildsUrlFromBaseAndPath() {
        assertEquals("http://game.havenandhearth.com/res/gfx/borka/male.res",
                ResourceFetcher.urlFor(null, "gfx/borka/male"));
        // default base used when blank
        assertEquals(ResourceFetcher.DEFAULT_BASE + "x.res",
                ResourceFetcher.urlFor("", "x"));
        // trailing slash on base, leading slash on path normalised
        assertEquals("https://h/res/a/b.res",
                ResourceFetcher.urlFor("https://h/res", "/a/b"));
        // an explicit .res suffix is not doubled
        assertEquals("https://h/res/a.res",
                ResourceFetcher.urlFor("https://h/res/", "a.res"));
        // backslashes normalised to forward slashes
        assertEquals("https://h/res/a/b.res",
                ResourceFetcher.urlFor("https://h/res/", "a\\b"));
    }

    @Test
    void baseNameFromPath() {
        assertEquals("male", ResourceFetcher.baseName("gfx/borka/male"));
        assertEquals("male", ResourceFetcher.baseName("gfx/borka/male.res"));
        assertEquals("thing", ResourceFetcher.baseName("thing"));
    }

    @Test
    void encodesEachResourcePathSegment() {
        assertEquals("https://h/res/a%20b/caf%C3%A9.res",
                ResourceFetcher.urlFor("https://h/res", "a b/caf\u00e9"));
        assertEquals("https://h/res/a/b.res",
                ResourceFetcher.urlFor("https://h/res", "\\a\\b"));
    }

    @Test
    void rejectsAmbiguousResourcePaths() {
        for(String path : new String[]{
                "", "a#fragment", "a?query", "a%20b", "a//b", "a/../b", "a/\nb"
        })
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceFetcher.urlFor("https://h/res", path), path);
    }

    @Test
    void rejectsInvalidResourceBases() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFetcher.urlFor("file:///tmp", "a"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFetcher.urlFor("https://h/res?token=x", "a"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceFetcher.urlFor("https://h/res#fragment", "a"));
    }
}
