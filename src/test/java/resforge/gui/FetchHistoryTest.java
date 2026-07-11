package resforge.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the Fetch-dialog path history (pure logic, no Swing). */
class FetchHistoryTest {

    @Test
    void parseDropsBlanksAndExactDuplicatesKeepingOrder() {
        List<String> h = FetchHistory.parse("gfx/borka/male\n\n  gfx/borka/female  \ngfx/borka/male\n");
        assertEquals(List.of("gfx/borka/male", "gfx/borka/female"), h);
    }

    @Test
    void parseOfEmptyOrNullIsEmpty() {
        assertTrue(FetchHistory.parse("").isEmpty());
        assertTrue(FetchHistory.parse(null).isEmpty());
    }

    @Test
    void serializeIsTheInverseOfParse() {
        List<String> h = List.of("gfx/borka/male", "gfx/terobjs/items/coin");
        assertEquals(h, FetchHistory.parse(FetchHistory.serialize(h)));
    }

    @Test
    void serializeNeverExceedsPreferencesValueLimit() {
        String large = "x".repeat(Preferences.MAX_VALUE_LENGTH);
        String serialized = FetchHistory.serialize(List.of("recent/path", large, "older/path"));

        assertTrue(serialized.length() <= Preferences.MAX_VALUE_LENGTH);
        assertEquals(List.of("recent/path", "older/path"), FetchHistory.parse(serialized),
                "an oversized entry is skipped without losing later entries that fit");
    }

    @Test
    void serializeDropsOldestEntriesUntilValueFits() {
        String entry = "x".repeat(Preferences.MAX_VALUE_LENGTH / 2);
        String serialized = FetchHistory.serialize(List.of("newest", entry, entry + "2", "oldest"));

        assertTrue(serialized.length() <= Preferences.MAX_VALUE_LENGTH);
        assertEquals("newest", FetchHistory.parse(serialized).get(0));
        assertTrue(FetchHistory.parse(serialized).contains("oldest"),
                "later short entries still fit when an older large entry does not");
    }

    @Test
    void addMovesToFrontAndDedupesCaseInsensitively() {
        List<String> h = FetchHistory.add(List.of("a/b", "c/d"), "c/d");
        assertEquals(List.of("c/d", "a/b"), h, "an existing path moves to the front");

        h = FetchHistory.add(List.of("Gfx/Borka/Male"), "gfx/borka/male");
        assertEquals(List.of("gfx/borka/male"), h, "case-insensitive duplicate replaced, newest casing kept");
    }

    @Test
    void addTrimsAndIgnoresBlank() {
        assertEquals(List.of("x/y"), FetchHistory.add(List.of(), "  x/y  "));
        assertEquals(List.of("x/y"), FetchHistory.add(List.of("x/y"), "   "));
    }

    @Test
    void addCapsAtMax() {
        List<String> h = List.of();
        for(int i = 0; i < FetchHistory.MAX + 10; i++)
            h = FetchHistory.add(h, "p/" + i);
        assertEquals(FetchHistory.MAX, h.size());
        assertEquals("p/" + (FetchHistory.MAX + 9), h.get(0), "most-recent first");
    }

    @Test
    void filterMatchesAnyCaseInsensitiveSubstringPreservingOrder() {
        List<String> hist = List.of("gfx/borka/male", "gfx/borka/female", "sfx/wave");
        assertEquals(List.of("gfx/borka/male", "gfx/borka/female"),
                FetchHistory.filter(hist, "BORKA"));
        assertEquals(List.of("gfx/borka/female"), FetchHistory.filter(hist, "female"));
        assertEquals(hist, FetchHistory.filter(hist, "  "), "a blank query matches everything");
        assertTrue(FetchHistory.filter(hist, "nope").isEmpty());
    }
}
