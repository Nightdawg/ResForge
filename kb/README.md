# kb/ — local knowledge base + retrieval (RAG)

A zero-dependency, local retrieval system over this project's notes. It's the
"retrieval" half of **R**etrieval-**A**ugmented **G**eneration: it finds the most
relevant chunks of your own Markdown notes for a question, which you (or an AI)
then read to answer grounded in project knowledge instead of guesswork.

## What's here
- `Rag.java` — the whole tool, one file, no build, no dependencies.
- `notes/` — the growing prose corpus. Add Markdown files here; they're indexed on
  the fly. Seeded with `format-notes.md` and `decisions.md`.
- By default it also indexes `docs/` (`AI-CONTEXT.md`, `DESIGN-notes.md`, ...), the
  root `README.md`, and the **Java source** under `src/main/java` + `src/test/java`
  (so code comments are searchable too).

## Run it
No compile step — Java's single-file launcher runs the source directly (JDK 11+;
we use 21). From the repo root:

```sh
java kb/Rag.java query "how does the tex codec recompute the length"
java kb/Rag.java query "what is the lossless-or-raw rule" -k 3
java kb/Rag.java query "server fetch url" -d kb/notes
java kb/Rag.java list
```

On Windows you can use the wrappers:

```powershell
kb\rag.ps1 query "vbuf2 attribute formats"
```

```cmd
kb\rag query "neg layer 12 bytes"
```

Flags: `-k N` (top-N, default 5), `-d <dir-or-file>` (override the search sources;
repeat for several). Defaults: `kb/notes`, `docs`, `README.md`, `src/main/java`,
`src/test/java`.

## How it works (briefly)
- **Markdown** (`.md`) is split into chunks at heading lines (`#`, `##`, ...).
- **Java** (`.java`) is split into "documented declarations": each comment block
  plus the class/method/field it precedes, tagged with the enclosing class.
- The tokenizer also splits camelCase / digit boundaries, so an identifier like
  `recomputeLength` matches the words "recompute" and "length".
- Ranks chunks against the query with **BM25** — classic lexical/keyword scoring
  (the same family as full-text search), so it's exact-term, not semantic. No
  embeddings, no model download, no network.
- Prints the top chunks with score, source file, heading, and a snippet.

It's lexical by design: trivial to run, nothing to install, and the corpus is
small enough that on-the-fly indexing every run is instant. If we ever outgrow
that, the upgrade path is embeddings + a vector index — but we don't need it yet.

## Maintaining the knowledge base
Two documents serve different roles:

- **`docs/AI-CONTEXT.md`** — the canonical, *curated* resume primer (architecture,
  builds, gotchas, per-layer status, conventions, open steps). Update it when the
  shape of the project changes: a new feature/command, a new build fact, a
  changed convention, or a finished/added next-step. Roughly **per meaningful
  commit**, not every trivial one. Keep it tight and current.

- **`kb/notes/*.md`** — the *append-only* lab notebook. Drop new facts, format
  discoveries, experiment results, and links here as you learn them. No reindex
  step — `Rag.java` reads the files each run, so new notes are searchable
  immediately. Use one heading per atomic fact so retrieval can isolate it.

Rule of thumb: if it's a durable, curated statement of "how the project is," edit
`AI-CONTEXT.md`; if it's a new raw finding you might want to look up later, add it
to `kb/notes/`. You don't need to touch either after *every* commit — only when
you actually learned or changed something worth remembering.
