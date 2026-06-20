package hafen.resedit;

import hafen.resedit.net.ResourceFetcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
