# Decisions & conventions

Why the project is shaped the way it is. Each heading is one decision so the RAG
can surface them on their own.

## Parts model
Unpacking a `.res` produces a `manifest.txt` plus one file per layer (under
`layers/`). Every layer can round-trip even if we don't understand it: unknown
layers become `.bin` blobs, so unpack->pack is byte-identical by construction.
Understanding a layer just means giving it a friendlier on-disk form (`.png`,
`.txt`, `.json`). The manifest is a small tab-delimited text file; layer names and
codec names are backslash-escaped (`\t`/`\n`/`\r`/`\\`) so a layer name containing a
delimiter still round-trips, and on repack `Packer` rejects part paths that escape
the unpack directory (no `../` or absolute-path traversal) and any unknown codec
name (so a manifest typo fails loudly instead of packing JSON text as raw bytes).

## Lossless-or-raw principle
A typed editor is only exposed when decode -> typed form -> encode reproduces the
original bytes exactly. If a layer doesn't pass that self-check, it falls back to
raw passthrough. This guarantees we never silently corrupt data the user didn't
intend to change. It's why float16-bearing layers (e.g. `obst`) stay raw, while
`neg` — which turned out to be all int16, exactly reversible — is editable JSON.

## Immutable layers + snapshot undo
Editing replaces a layer object rather than mutating it. That makes GUI undo/redo
cheap: snapshot the layer list before each edit and restore on undo.

## Triple build (Gradle + Maven + Ant)
Gradle (`./gradlew`, output `build-gradle/`), Maven (`mvn package`, output
`build-maven/`) and Ant (`ant`, output `build-ant/`) all build the same sources,
run the same JUnit 5 tests, and produce the same runnable fat jar (JOrbis folded
in). Each writes to its own directory so they never clash. Ant's default target
is `jar` (no tests); `ant build` runs tests too (needs Ant 1.10+ for
`junitlauncher`); `ant gui` launches the app. Gradle and Maven fetch JUnit (and
Maven its plugins) from Maven Central; Ant uses the vendored `lib/` jars and needs
no network. Maven's output is redirected from the usual `target/` to `build-maven/`
via `<build><directory>`, and its fat jar is built by the shade plugin (with
`createDependencyReducedPom=false` to avoid a stray pom). Three builds means a
dep/version change must be applied to all three (`build.gradle`, `pom.xml`,
`build.xml`/`lib/`).

## 3D viewer is a pure-Java software renderer (no OpenGL/JavaFX)
The in-app **View 3D** preview (`gui/Model3DView`) is a hand-written z-buffered
triangle rasteriser drawing into a `BufferedImage` via Java2D — no JOGL/LWJGL
(native libs) and no JavaFX (separate module + natives). This keeps the tool
dependency-light and the three builds (Gradle/Maven/Ant) in lockstep, exactly as
`GltfExport` is hand-built rather than pulling a glTF library. All the geometry is
already decoded in-app (`Vbuf2Data` positions/normals, `MeshInfo` indices), so the
viewer only adds rendering: `model/ModelGeometry` assembles a triangle soup
(reusing the glTF-export decoders) and the renderer shades it two-sided from a
head-light with optional wireframe and orbit/zoom/pan. **Texturing** (Tier 2 part 1)
samples the model's *local* textures with perspective-correct UVs (alpha-mask cutout
honoured), resolving the `matid→mat2→local tex` chain via `model/LocalTextures` —
the same chain the glTF export uses. Models shown in bind/rest pose (no
skinning/animation). **Variable materials (`varmat`)** — where a part's texture comes
from a `code`/`codeentry`-declared variable material or an external `mlink` rather
than a local `tex` (e.g. knarr's sail/hull) — are *not* resolved yet; those parts fall
back to flat shading. Resolving varmat is **Tier 2 part 2** (deferred). Animation
playback is a later tier.

## JOrbis dependency
The only runtime dependency, used solely by the GUI's Ogg player. Maven coords
are `org.jcraft:jorbis:0.0.17` (LGPL, ~97KB) — note `org.jcraft`, not
`com.jcraft`. The one jar contains both the `jogg` and `jorbis` packages. The CLI
never touches it.

## Copyrighted samples stay out of git
Real game `.res` files are copyrighted. They live in a gitignored `samples/`
folder and are never committed. Validation runs locally; CI stays green without
the assets.

## Open from game cache: names only, always re-fetch from the server
The local Haven cache (`HashDirCache` at `%APPDATA%\Haven and Hearth\data`) holds
the resources the player already downloaded, with each file's header carrying the
resource *name*. ResForge reads that cache for **names only** (via `net/CacheIndex`)
and then fetches the chosen resource **fresh from the server** — it never opens the
cached bytes. Why: the cache can hold a stale version, and opening-then-saving stale
bytes is a subtle way to ship an out-of-date mod; re-fetching guarantees the latest.
It also keeps the cache reader trivial and robust (we only parse a tiny header, not
every cached payload) and reuses the already-tested `ResourceFetcher` path. The
header decodes with `DataInputStream.readUTF` exactly as the client's `readhead`
does, so it's authoritative; resource entries are the `res/`-prefixed names. The
idea was prompted by the read-only Rust tool `ancientchina/hafen-res`, but the
implementation is clean-room from the client's `HashDirCache.java` (MIT vs. its
LGPL-3 — no code taken).

## Environment gotcha: JAVA_HOME
Gradle and Maven require `JAVA_HOME` to point at the **JDK root** (the folder that
*contains* `bin`), not the `\bin` sub-directory — a `\bin` JAVA_HOME is rejected.
This is generic JDK-21 guidance (any vendor's JDK works); machine-specific setup
for a particular dev box doesn't belong in the repo.

## The transform command was removed (glTF round-trip supersedes it)
An early CLI `transform <file> <sx> <sy> <sz>` scaled a model's `vbuf2` positions —
the first write path, built to prove the structure-preserving `vbuf2` encoder. It
only touched positions (correct for a uniform scale, wrong for a non-uniform one
where normals need the inverse-transpose), and its *output* was the one feature
never confirmed in-game. The glTF round-trip (Export → edit in Blender → Rebuild)
now covers scaling — handling normals/tangents properly and in-game validated — so
the command was dropped to keep the tool's promise sharp: every feature is either
provably lossless or in-game validated. The structure-preserving encoder it was
built on lives on as the foundation of the glTF rebuild.

## Hostile-input hardening (lossless-or-raw isn't enough on its own)
The lossless-or-raw guard protects *editing*, but the *parser* must also survive
crafted/corrupt input — the whole point of the tool is opening untrusted, fetched
`.res` files. So: `MessageReader.ensure` is overflow-safe (`n<0 || n>end-pos`, not
`pos+n>end` which overflows for a huge length), `ResContainer.parse` rejects a layer
length that's negative or larger than the bytes remaining (a 28-byte crafted file
otherwise tried to allocate ~2 GB → OOM), and `string()` decodes strict UTF-8
(reporting, not substituting U+FFFD — a lenient decode would change the bytes on
re-encode and silently break the invariant). A negative internal length used to
rewind the cursor and hang; the same `ensure` fix closes that. `Json` rejects
truncated `\u`/dangling escapes and duplicate object keys. Recursion is depth-capped
(`Json` object/array nesting, `PropsCodec` and `TtoSkip` tto list/map nesting all cap
at 256), so a pathologically deep document fails with a clear exception instead of a
`StackOverflowError` — which, being an `Error`, would otherwise slip past the codecs'
`catch(RuntimeException)` guards. Rule: a corrupt file
must fail with a clear exception, never OOM, hang, or corrupt.

## Atomic writes (never destroy the only copy)
Every `.res`/`.glb` write goes through `io/SafeFiles.write`: data is written to a
sibling temp file, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` renames it
over the target (with a non-atomic fallback where the filesystem can't do an atomic
move). CLI `replace`/`rebuild-gltf` and GUI Save all default to
overwriting their input, so a crash, full disk, or I/O error mid-write would
otherwise leave a truncated/corrupt original with no backup. The temp file is
cleaned up on failure.

## Edit-time range validation (Nums)
A typed codec's `encode` runs twice: at unpack (on values it just decoded — always
in range) and at pack (on the user's edited JSON). Without a check, an edited value
outside a field's width silently truncates/wraps on the wire (e.g. `anim` `delay:
70000` → `4464`). The shared `layers/Nums` helper range-checks every fixed-width
integer field and derived count on encode, so a bad edit throws instead of writing
corrupt bytes. Floats still re-quantise (and the per-layer byte-equality guard keeps
the unpack side honest).

## Image split is parsed exactly, never magic-scanned
`ImageInfo` finds where the embedded image starts by parsing the header precisely —
including the new-style typed (tto) info block, stepped over with `TtoSkip`. It does
**not** scan for an image magic, because a 2-byte BMP ("BM") or 3-byte JPEG signature
can appear inside header metadata and would be picked as a false image start —
harmless for lossless repack (it's a concat) but corrupting for a `replace`/export
that slices `[0, offset)` as the header. If the parsed offset isn't exactly on a
known image magic, the layer simply stays raw.

## ResForgeFrame is an orchestrator; editors and dialogs are their own classes
The Swing window (`gui/ResForgeFrame`) had grown to ~1.7k lines doing everything.
The per-layer detail/editor panels were extracted into `gui/LayerEditors` (one
`build*Panel` per layer kind) behind a small `gui/EditorHost` interface that exposes
only the document/file/edit operations an editor needs — so the editors hold no
undo/threading/dialog state. The two modal pickers became `gui/FetchDialog` and
`gui/CachePickerDialog`: self-contained, they read the remembered base URL / fetch
history from preferences and just **return** the chosen `{path, base}`, while the
frame still performs the actual download via `fetchFromServer`. This is a pure
transcription + delegation — behaviour is unchanged (GUI-smoke-confirmed) — done for
maintainability/testability only: the frame dropped to ~1.1k lines, and the pickers/
editors can now be reasoned about and unit-tested in isolation (as `FetchHistory`/
`CacheIndex` already are). The split needs no build-file change — Maven and Ant glob
`src/main/java`, so new source files are picked up automatically. Rule: keep the frame
the orchestrator (document, undo, table, threading); push per-kind panel construction
and self-contained dialogs out into their own classes.
