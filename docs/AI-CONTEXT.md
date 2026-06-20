# AI-CONTEXT — start here

A dense, single-file primer so a fresh session (human or AI) can resume work on
`ResForge` quickly. For the deep reverse-engineering log and format details
see [`DESIGN-notes.md`](DESIGN-notes.md); for source provenance see
[`reference/README.md`](reference/README.md). Update this file when the
architecture, feature set, or "next steps" materially change.

> Quick lookups: a tiny local retriever lives in [`../kb/`](../kb/README.md) —
> `java kb/Rag.java "your question"` runs BM25 over `kb/notes/` + `docs/` + the
> Java source (no build, no deps). Add `-f` to print whole chunks (best for AI:
> full grounded context in one call), e.g.
> `java kb/Rag.java -f "how does the tex codec recompute length"`. Append new
> findings as Markdown under `kb/notes/`; keep *this* file as the curated primer.

## 1. What this is
A standalone **Java 21** tool to decompile, edit, and recompile Haven & Hearth
`.res` resource files for modding — both a **CLI** and a **Swing GUI**. Core
guarantee: **lossless by construction** — unchanged layers are preserved
byte-for-byte; typed editors are only offered when they re-encode exactly
("lossless-or-raw"). Reverse-engineered from the game client (`hafen-client`,
a sibling project at `../hafen-client`).

## 2. Build & run (IMPORTANT gotchas)
- **JAVA_HOME must be the JDK *root*** `C:\Program Files\Java\graalvm-jdk-21.0.9+7.1`
  (the machine's env var wrongly includes `\bin` — both builds reject that).
- **Two equivalent builds, separate output dirs (both gitignored):**
  - Gradle → `build-gradle/`: `./gradlew build` (compile+test), `./gradlew jar`.
  - Ant → `build-ant/`: `ant` or `ant jar` (no tests, default target), `ant build`
    (jar+tests), `ant gui` (launch GUI detached), `ant test`, `ant clean`.
    JUnit jars are vendored in `lib/`; Ant needs 1.10+ (native `junitlauncher`).
- **Runnable jar:** `<build>/libs/resforge-0.1.0.jar`. Use the jar (not
  `gradlew/ant run`) for paths with spaces — `--args`/`-Dargs` mangle them.
- **One runtime dependency:** `org.jcraft:jorbis` 0.0.17 (LGPL, ~97 KB, bundles
  `jogg`+`jorbis`) — the GUI's Ogg player. Vendored in `lib/`, folded into the
  jar by both builds (Gradle fat-jar via runtimeClasspath; Ant `zipgroupfileset`).
  The CLI never uses it.
- Verifying the GUI: launch the jar, screenshot the screen, view the PNG. GUI
  mouse/keys automation is flaky — prefer screenshotting + trusting shared code
  paths. Always `Stop-Process -Id <PID>` test windows (never kill IntelliJ).

## 3. CLI commands (`resforge.Main`)
`gui [file]` · `fetch <path> [out.res]` · `info <file>` · `unpack <file> [dir]` ·
`pack <dir> [out]` · `replace <file> <selector> <newfile> [out]` ·
`obj <file> [out.obj]` · `transform <file> <sx> <sy> <sz> [out]` ·
`catalog <file|dir>` · `verify <file|dir>`.
No args (with a display) → launches the GUI.
`replace` selector: layer name (`image`), name+occurrence (`tex#2`), or index (`#5`).

## 4. GUI (`resforge.gui.ResForgeFrame`)
Open / drag-drop / **Fetch from server…**; layer table with **thumbnails** for
image/tex; per-layer editors: image/tex **preview**+**metadata** (id, z/sub-z,
offset — **editable** for old-style image headers)+replace+export, **Ogg player**
(play/stop/draggable seek), **live animation playback** (anim frames resolved to
sibling image layers, composited at their true relative size + per-frame offset),
tooltip/pagina **text**, props/action/**mat2**/**anim**/**neg**
**JSON** (lossless-or-raw), `code`/`codeentry` **read-only** view (+ `.class`
export), font/midi replace+export, raw replace+export, 3D → **Export OBJ**. Layer
ops: **Add / Rename (button + inline cell edit) / Delete / Move up·down**.
Toolbar: Open, Fetch, Save As, Export OBJ, **resource-version spinner** (uint16).
**Edit → Undo/Redo** (Ctrl+Z/Y, snapshot-based). Full **file-path bar** under the toolbar.

## 5. Architecture (packages under `src/main/java/resforge/`)
- `Main` — CLI dispatch + GUI launch.
- `io/` — `MessageReader`/`MessageWriter` (LE primitives mirroring `haven.Message`),
  `Json` (dependency-free JSON).
- `res/` — `ResContainer` (parse/serialize the container), `Layer` (name+bytes,
  immutable), `Manifest` (manifest.txt + per-layer codec), `Unpacker`/`Packer`
  (the "parts" model; codecs `raw|tex|props|action|anim|neg|mat2`), `Replacer`
  (one-shot swap), `Verifier` (batch round-trip + histograms), `Catalog` (folder listing).
- `layers/` — read/locate decoders: `ImageInfo`, `TexInfo`, `AudioInfo`, `FontInfo`,
  `ImageMagic`, `Vbuf2Info`, `MeshInfo`, `TtoSkip`, `CodeInfo`, `CodeEntryInfo`
  (read-only); typed JSON codecs `PropsCodec`, `ActionCodec`, `Mat2Codec`,
  `AnimCodec`, `NegCodec` (tto/record ↔ JSON, lossless-or-raw); header-field
  codecs `ImageHeaderCodec` (id/z/subz/offset/nooff + build new image layers),
  `TexHeaderCodec` (id/offset/size), `AudioHeaderCodec` (clip id + volume) — all
  lossless-or-raw, image/audio bytes kept verbatim.
- `model/` — `Vbuf2Data` (de-quantise vertices for export), `Vbuf2Codec`
  (structure-preserving vbuf2 encode), `ObjExport` (geometry → Wavefront OBJ).
- `audio/` — `OggVorbis` (Ogg → PCM via JOrbis).
- `net/` — `ResourceFetcher` (`<base>/<path>.res` GET, JDK HttpClient).
- `gui/` — `ResForgeFrame`, `GuiSupport` (per-layer preview/text/export, reuses
  decoders), `ImageView`, `AudioPlayerPanel`, `AnimView` (offset-aware sprite playback).

## 6. Per-layer status
| Layer | Status |
|-------|--------|
| `image` | edit: swap embedded PNG/JPEG; **edit header** (id/z/sub-z/offset/nooff) for old-style; new image layers get a wrapped header |
| `tex` | edit: swap 3D texture (`tex` codec recomputes int32 length); **edit header** (id/offset/declared size) |
| `audio2` | edit: swap Ogg; **edit clip id + volume** (ver-2 header); GUI plays it |
| `font` | edit: swap TTF/OTF (sfnt). 2-byte header + font tail |
| `midi` | edit: swap `.mid` (whole payload) |
| `props` | edit as JSON (tto list, lossless-or-raw) |
| `action` | edit as JSON (deterministic AButton record) |
| `mat2` | edit as JSON (id + per-command tto value lists; tagged-value form, lossless-or-raw) |
| `anim` | edit as JSON (sprite animation: id + delay + frame image-ids; deterministic) |
| `neg` | edit as JSON (click hotspot + bounds + endpoint groups; all int16, lossless) |
| `tooltip`/`pagina` | edit as UTF-8 text |
| `vbuf2`/`mesh` | **read-only**: fully decoded; GUI shows vertex/attribute + tri/vbuf/material detail; OBJ export; `transform` write path |
| `code`/`codeentry` | **read-only**: class name + `.class` export; entrypoint→class + classpath manifest shown |
| everything else (`obst`,`skel`,`skan`,`boneoff`,`rlink`,`tileset2`,`clamb`,`foodev`,`src`,…) | **raw passthrough** (lossless) |

## 7. Key format facts (see DESIGN-notes §2–8 for detail)
- Container: `"Haven Resource 1"`(16) + `uint16` ver + repeated [NUL-string name,
  `int32` len, bytes]. All LE. Unknown layers pass through.
- `vbuf2`: `uint8 fl`(ver=fl&0xf), `int16 id`(ver≥1), `uint16 num`, then per attr
  a name + (ver≥1: `int32`-len blob | ver0: inline). Bare names = `float32`×eln;
  `…2` names = `uint8(1)`+fmt+data (fmt ∈ f4/f2/f1/snN/unN/rnN/sf9995/uvecN).
  Bones (`bones`/`bones2`) = per-bone RLE weight spans. mkres defaults: pos→sn2,
  nrm/tan/bit→uvec1, tex/otex→un2|sn2.
- `mesh`: `uint8 fl`; old form (`fl&0x80`==0) num/matid/…/indices; indices raw
  `uint16×3` or **delta-stripped** (`unstrip`/`decdelta`).
- Server fetch: `<base>/<path>.res`, base default
  `http://game.havenandhearth.com/res/` (the official server; same as the client).
- From CarryGun's tool (knowledge only, no code taken): `neg`'s 12 "skipped" bytes
  are tl/br/oc offsets (coord16); `mat2` = id + map of key→tto-value-list.

## 8. Reference & samples
- `docs/reference/`: verbatim client sources `Resource.java`, `Message.java`,
  `NormNumber.java`, `TexR.java`, `VertexBuf.java`, and the dev's
  `mkres-fragment.py` (Ogre-XML→binary encoder). LGPL — keep notices.
- `samples/` (gitignored, copyrighted game assets): real `.res` + raw png/wav.
  `verify samples` → **all pass** (a mislabeled non-resource — zero bytes + a raw
  PNG — was removed; the tool correctly rejects such files). Histograms confirm all
  image/tex/audio/font/props/action/**mat2**/**anim**/**neg** decode/round-trip
  exactly, all `vbuf2` re-encode byte-exact, all `mesh` decode, and all
  `code`/`codeentry` decode. (Counts grow as more samples are added; `verify` is
  the live oracle — keep it all-pass.)

## 9. Conventions
- Lossless-or-raw: never expose a typed editor unless decode→encode is byte-exact
  (verified). Untouched layers always pass through unchanged.
- `Layer` is immutable; edits *replace* it (enables cheap snapshot undo).
- Edits route through `Replacer` where possible (tested, format-checked).
- Commit per feature with a `Co-authored-by: Copilot …` trailer; keep both builds
  green; verify on real `samples/` before claiming done.
- **Keep docs in lockstep with code.** Every add/change updates the docs in the
  *same* commit: this primer (`AI-CONTEXT.md`), the per-layer table, the `README`
  where relevant, and a `kb/notes/` entry for new format findings. A change isn't
  done until its docs match — treat stale docs as a bug.

## 10. Open / next steps
- **In-game test of the `transform` write path** (user-side; the one thing not
  auto-verifiable). Uniform scale e.g. `2 2 2` should render correct-but-bigger.
- **3D import** (edit in Blender → back to `.res`): port `mkres` (Ogre-XML or OBJ
  → vbuf2/mesh, re-strip + re-quantise) behind lossless-or-raw — needs the in-game
  loop. `Vbuf2Codec` is the structure-preserving foundation.
- Typed editor for `obst` (movement-collision polygons; uses lossy `float16`, so
  needs a half-precision codec under the lossless-or-raw guard). (`mat2`, `anim`
  and `neg` are now done — editable JSON; `code`/`codeentry` are read-only.)
- GUI niceties: fetch path history/autocomplete, batch
  re-skin a folder. (No layer search/filter — explicitly declined.)

## 11. The other tool (context)
CarryGun's **HafenResourceTool** (GitLab, Qt/C++): broader typed coverage +
working OBJ/Ogre-XML import/export and per-type editors, but **re-encodes typed
layers** (round-trip fidelity depends on each encoder; mitigated by an edited
flag) — less lossless-safe than our parts model. Useful as a format reference.
