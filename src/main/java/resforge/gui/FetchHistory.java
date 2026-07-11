package resforge.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/** Persistence + filtering helpers for the "Fetch from server" dialog's
 *  remembered resource paths. Successful fetches are recorded so they can be
 *  offered as substring-matched, click-to-use suggestions next time.
 *
 *  <p>This is deliberately pure logic (no Swing, no I/O) so it can be unit-tested
 *  directly; the GUI persists the {@link #serialize serialized} form via
 *  {@code java.util.prefs.Preferences}, exactly as the base URL already is. */
final class FetchHistory {
    private FetchHistory() {}

    /** How many distinct paths to remember (most-recent-first). */
    static final int MAX = 50;

    /** Parse the newline-separated stored form into a most-recent-first list,
     *  dropping blank lines and exact duplicates. */
    static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if(raw != null && !raw.isEmpty())
            for(String s : raw.split("\n")) {
                String t = s.strip();
                if(!t.isEmpty() && !out.contains(t))
                    out.add(t);
            }
        return out;
    }

    /** Join entries for {@link Preferences} storage, dropping oldest/oversized
     *  values as needed so {@link Preferences#put} cannot reject the result. */
    static String serialize(List<String> history) {
        StringBuilder out = new StringBuilder();
        for(String entry : history) {
            if(entry == null)
                continue;
            String value = entry.strip();
            if(value.isEmpty() || value.length() > Preferences.MAX_VALUE_LENGTH)
                continue;
            int added = value.length() + (out.isEmpty() ? 0 : 1);
            if(out.length() + added > Preferences.MAX_VALUE_LENGTH)
                continue;
            if(!out.isEmpty())
                out.append('\n');
            out.append(value);
        }
        return out.toString();
    }

    /** Return a new list with {@code path} as the most-recent entry:
     *  case-insensitively de-duplicated, moved to the front, capped at {@link #MAX}.
     *  A blank path is ignored (the original order is returned). */
    static List<String> add(List<String> history, String path) {
        List<String> out = new ArrayList<>(history);
        String p = path == null ? "" : path.strip();
        if(!p.isEmpty()) {
            out.removeIf(h -> h.equalsIgnoreCase(p));
            out.add(0, p);
            while(out.size() > MAX)
                out.remove(out.size() - 1);
        }
        return out;
    }

    /** The entries that contain {@code query} as a case-insensitive substring,
     *  preserving the most-recent-first order. A blank query matches everything. */
    static List<String> filter(List<String> history, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for(String h : history)
            if(q.isEmpty() || h.toLowerCase(Locale.ROOT).contains(q))
                out.add(h);
        return out;
    }
}
