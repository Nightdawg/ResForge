import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A tiny, dependency-free local RAG (retrieval) over the project's Markdown
 * notes AND Java source. Run directly with Java's single-file launcher (no build):
 *
 *   java kb/Rag.java query "how does the tex codec recompute length" [-k 5] [-d docs -d kb/notes]
 *   java kb/Rag.java list                       # show every indexed chunk
 *
 * Markdown (.md) is chunked at headings; Java (.java) is chunked into "documented
 * declarations" — each comment block plus the class/method/field it annotates,
 * tagged with the enclosing class. Chunks are ranked against the query with BM25
 * (classic lexical/keyword retrieval — the same family as FTS search), and the
 * most relevant are printed with their source and a snippet. This is the
 * "retrieval" half of retrieval-augmented generation: feed the results to an AI
 * (or read them yourself) to ground an answer in your own project.
 *
 * It is lexical, not semantic (no embeddings/vector DB) — by design: zero deps,
 * no model download, no network, and it indexes on the fly each run (the corpus
 * is small). Default sources: kb/notes, docs, README.md, src/main/java,
 * src/test/java. Grow it by adding Markdown under kb/notes/ (or just write code
 * comments — they're indexed too).
 */
public class Rag {
    // BM25 parameters (standard defaults).
    static final double K1 = 1.5, B = 0.75;

    record Chunk(Path file, String heading, String text, List<String> terms) {}

    public static void main(String[] args) throws IOException {
        if(args.length == 0) {
            usage();
            return;
        }
        String cmd = args[0];
        List<Path> dirs = new ArrayList<>();
        int k = 5;
        List<String> rest = new ArrayList<>();
        for(int i = 1; i < args.length; i++) {
            switch(args[i]) {
                case "-d": dirs.add(Path.of(args[++i])); break;
                case "-k": k = Integer.parseInt(args[++i]); break;
                default:   rest.add(args[i]);
            }
        }
        if(dirs.isEmpty()) {
            dirs.add(Path.of("kb/notes"));
            dirs.add(Path.of("docs"));
            dirs.add(Path.of("README.md"));
            dirs.add(Path.of("src/main/java"));
            dirs.add(Path.of("src/test/java"));
        }

        List<Chunk> chunks = index(dirs);
        switch(cmd) {
            case "query": {
                if(rest.isEmpty()) {
                    System.err.println("query needs a question");
                    return;
                }
                query(chunks, String.join(" ", rest), k);
                break;
            }
            case "list":
                for(Chunk c : chunks)
                    System.out.printf("%-52s  %s  (%d terms)%n", c.file(), c.heading(), c.terms().size());
                System.out.printf("%n%d chunks across %d file(s)%n", chunks.size(),
                        chunks.stream().map(Chunk::file).distinct().count());
                break;
            default:
                usage();
        }
    }

    /* --------------------------------------------------------------- indexing */

    static List<Chunk> index(List<Path> sources) throws IOException {
        // Collect target files: each source may be a single file or a directory to walk.
        List<Path> files = new ArrayList<>();
        for(Path src : sources) {
            if(Files.isRegularFile(src)) {
                files.add(src);
            } else if(Files.isDirectory(src)) {
                try(Stream<Path> s = Files.walk(src)) {
                    s.filter(Files::isRegularFile).forEach(files::add);
                }
            }
        }
        List<Path> targets = files.stream()
                .filter(Rag::indexable)
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        List<Chunk> chunks = new ArrayList<>();
        for(Path f : targets) {
            try {
                if(f.getFileName().toString().toLowerCase().endsWith(".java"))
                    chunkJava(f, chunks);
                else
                    chunkMarkdown(f, chunks);
            } catch(IOException e) {
                System.err.println("skip " + f + ": " + e.getMessage());
            }
        }
        return chunks;
    }

    static boolean indexable(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".md") || n.endsWith(".java");
    }

    /** Split a Markdown file into chunks at heading lines (#, ##, ...). */
    static void chunkMarkdown(Path md, List<Chunk> out) throws IOException {
        String content = Files.readString(md, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        String heading = "(intro)";
        StringBuilder body = new StringBuilder();
        for(String line : lines) {
            if(line.startsWith("#")) {
                flush(md, heading, body, out);
                heading = line.replaceFirst("^#+\\s*", "").strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(md, heading, body, out);
    }

    static void flush(Path md, String heading, StringBuilder body, List<Chunk> out) {
        String text = body.toString().strip();
        if(text.isEmpty() && heading.equals("(intro)"))
            return;
        String full = heading + "\n" + text;
        out.add(new Chunk(md, heading, text, terms(full)));
    }

    /**
     * Chunk a .java file into "documented declarations": each comment block plus
     * the class/method/field declaration it precedes, tagged with the enclosing
     * class name. Annotations and blank lines between the comment and the
     * declaration are tolerated; an unattached trailing comment is kept on its own.
     */
    static void chunkJava(Path f, List<Chunk> out) throws IOException {
        String content = Files.readString(f, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        String cls = classNameOf(lines, f);

        List<String> comment = new ArrayList<>();
        boolean inBlock = false;
        for(String raw : lines) {
            String line = raw.strip();
            if(inBlock) {
                comment.add(stripComment(line));
                if(line.endsWith("*/"))
                    inBlock = false;
                continue;
            }
            if(line.startsWith("/*")) {
                comment.add(stripComment(line));
                inBlock = !line.endsWith("*/");
                continue;
            }
            if(line.startsWith("//")) {
                comment.add(stripComment(line));
                continue;
            }
            if(line.isEmpty() || line.startsWith("@"))
                continue;                       // tolerate blanks/annotations before the declaration
            if(!comment.isEmpty()) {
                emitJava(f, cls, comment, line, out);
                comment.clear();
            }
        }
        if(!comment.isEmpty())
            emitJava(f, cls, comment, "", out);
    }

    static void emitJava(Path f, String cls, List<String> comment, String decl, List<Chunk> out) {
        String body = String.join(" ", comment).replaceAll("\\s+", " ").strip();
        String sig = decl.replaceAll("[{;]\\s*$", "").strip();
        if(body.isEmpty() && sig.isEmpty())
            return;
        if(sig.length() > 100)
            sig = sig.substring(0, 100) + "\u2026";
        String heading = sig.isEmpty() ? cls + " (comment)" : cls + " :: " + sig;
        String text = (sig.isEmpty() ? "" : sig + "\n") + body;
        out.add(new Chunk(f, heading, text, terms(heading + " " + text)));
    }

    static String classNameOf(String[] lines, Path f) {
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
        for(String line : lines) {
            var m = p.matcher(line);
            if(m.find())
                return m.group(1);
        }
        String n = f.getFileName().toString();
        return n.endsWith(".java") ? n.substring(0, n.length() - 5) : n;
    }

    static String stripComment(String line) {
        return line.replaceFirst("^/\\*\\*?", "")
                   .replaceFirst("^//+", "")
                   .replaceFirst("^\\*+", "")
                   .replaceFirst("\\*/$", "")
                   .strip();
    }

    /** Tokenize, also splitting camelCase / digit boundaries so `recomputeLength`
     *  matches the words "recompute" and "length". */
    static List<String> terms(String s) {
        List<String> t = new ArrayList<>();
        for(String raw : s.split("[^A-Za-z0-9]+")) {
            if(raw.isEmpty())
                continue;
            String whole = raw.toLowerCase(Locale.ROOT);
            if(whole.length() >= 2)
                t.add(whole);
            for(String part : raw.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")) {
                String pl = part.toLowerCase(Locale.ROOT);
                if(pl.length() >= 2 && !pl.equals(whole))
                    t.add(pl);
            }
        }
        return t;
    }

    /* -------------------------------------------------------------- retrieval */

    static void query(List<Chunk> chunks, String q, int k) {
        if(chunks.isEmpty()) {
            System.out.println("Nothing indexed. Add Markdown under kb/notes/ or pass -d <dir-or-file>.");
            return;
        }
        List<String> qterms = terms(q);

        // Document frequencies + average length for BM25.
        Map<String, Integer> df = new HashMap<>();
        double avgLen = 0;
        for(Chunk c : chunks) {
            avgLen += c.terms().size();
            for(String term : new java.util.HashSet<>(c.terms()))
                df.merge(term, 1, Integer::sum);
        }
        avgLen /= chunks.size();
        int N = chunks.size();

        List<double[]> scored = new ArrayList<>();   // [index, score]
        for(int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for(String term : c.terms())
                tf.merge(term, 1, Integer::sum);
            double score = 0;
            int len = c.terms().size();
            for(String qt : qterms) {
                int f = tf.getOrDefault(qt, 0);
                if(f == 0)
                    continue;
                int n = df.getOrDefault(qt, 0);
                double idf = Math.log(1 + (N - n + 0.5) / (n + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len / avgLen));
            }
            if(score > 0)
                scored.add(new double[]{i, score});
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));

        if(scored.isEmpty()) {
            System.out.println("No matching notes for: " + q);
            return;
        }
        System.out.printf("Top %d result(s) for: %s%n%n", Math.min(k, scored.size()), q);
        for(int i = 0; i < Math.min(k, scored.size()); i++) {
            Chunk c = chunks.get((int) scored.get(i)[0]);
            System.out.printf("[%.2f] %s  --  %s%n", scored.get(i)[1], c.file(), c.heading());
            System.out.println("    " + snippet(c.text(), qterms));
            System.out.println();
        }
    }

    /** A short snippet around the first query-term hit (or the chunk start). */
    static String snippet(String text, List<String> qterms) {
        String flat = text.replaceAll("\\s+", " ").strip();
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = -1;
        for(String qt : qterms) {
            int p = lower.indexOf(qt);
            if(p >= 0 && (at < 0 || p < at))
                at = p;
        }
        int start = Math.max(0, (at < 0 ? 0 : at) - 60);
        int end = Math.min(flat.length(), start + 240);
        String s = flat.substring(start, end);
        return (start > 0 ? "..." : "") + s + (end < flat.length() ? "..." : "");
    }

    static void usage() {
        System.out.println("hafen-resedit knowledge-base retrieval (lexical BM25)\n");
        System.out.println("  java kb/Rag.java query \"your question\" [-k N] [-d dir-or-file]...");
        System.out.println("  java kb/Rag.java list");
        System.out.println("\nIndexes Markdown (.md, by heading) and Java (.java, by documented declaration).");
        System.out.println("Default sources: kb/notes, docs, README.md, src/main/java, src/test/java.");
        System.out.println("Add Markdown under kb/notes/ (or write code comments) to grow it.");
    }
}
