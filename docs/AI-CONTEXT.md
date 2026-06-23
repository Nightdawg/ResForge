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
- **JAVA_HOME must point at the JDK *root*** (the folder that *contains* `bin`),
  **not** the `\bin` sub-directory — Gradle and Maven reject a `\bin` JAVA_HOME.
  This is a common mistake; any vendor's JDK 21 root works.
- **Three equivalent builds, separate output dirs (all gitignored):**
  - Gradle → `build-gradle/`: `./gradlew build` (compile+test), `./gradlew jar`.
  - Maven → `build-maven/`: `mvn package` (compile+test+jar); output redirected
    from the usual `target/` via `<build><directory>`. Fat jar via shade plugin;
    jorbis + JUnit fetched from Maven Central.
  - Ant → `build-ant/`: `ant` or `ant jar` (no tests, default target), `ant build`
    (jar+tests), `ant gui` (launch GUI detached), `ant test`, `ant clean`.
    JUnit jars are vendored in `lib/`; Ant needs 1.10+ (native `junitlauncher`).
- **Runnable jar:** `build-gradle/libs/` or `build-ant/libs/` or `build-maven/`,
  all `resforge-0.1.0.jar`. Use the jar (not `gradlew/ant run`) for paths with
  spaces — `--args`/`-Dargs` mangle them.
- **One runtime dependency:** `org.jcraft:jorbis` 0.0.17 (LGPL, ~97 KB, bundles
  `jogg`+`jorbis`) — the GUI's Ogg player. Vendored in `lib/` (Gradle/Ant) or
  pulled from Central (Maven), folded into the jar by all three (Gradle fat-jar
  via runtimeClasspath; Ant `zipgroupfileset`; Maven shade). The CLI never uses it.
- **License:** project is **MIT** (`LICENSE`, Copyright Nightdawg). Bundled JOrbis
  stays **LGPL** and the `docs/reference/*.java` client files stay **LGPL-3** —
  both documented in `THIRD-PARTY-NOTICES.md` (keep those notices on redistribution).
  Only the core JOrbis decoder is used, never the GPL JOrbisPlayer.
- Verifying the GUI: launch the jar, screenshot the screen, view the PNG. GUI
  mouse/keys automation is flaky — prefer screenshotting + trusting shared code
  paths. Always `Stop-Process -Id <PID>` test windows (never kill IntelliJ).

## 3. CLI commands (`resforge.Main`)
`gui [file]` · `fetch <path> [out.res]` · `info <file>` · `refs <file>` ·
`unpack <file> [dir]` · `pack <dir> [out]` · `replace <file> <selector> <newfile> [out]` ·
`gltf <file> [out.glb]` ·
`rebuild-gltf <orig.res> <edited.glb> [out.res]` ·
`catalog <file|dir>` · `cache-list [cacheDir]` · `verify <file|dir>`.
No args (with a display) → launches the GUI. (`refs` lists every resource a
`.res` references, aggregated across `deps`/`rlink`/`codeentry`/`mat2`. `gltf`
exports the 3D model as a Blender-ready binary glTF, and `rebuild-gltf`
regenerates geometry from an edited `.glb` to allow reshaped/added/removed vertices.
`cache-list` scans the local game cache and prints the resource names found there,
ready to `fetch`.)
`replace` selector: layer name (`image`), name+occurrence (`tex#2`), or index (`#5`).

## 4. GUI (`resforge.gui.ResForgeFrame`)
Open / drag-drop / **Fetch from server…** (remembers successful paths; offers them
as substring-matched, click-to-use suggestions below the input) / **Open from game
cache…** (Ctrl+O; scans the local Haven cache at `%APPDATA%\Haven and Hearth\data`,
opening immediately with a "scanning…" state then filling in a filterable list — `dyn/`
account-attached resources sort last and are greyed — and fetches the chosen one *fresh
from the server*, so the cache supplies names only and you always open the latest
version); layer table with **thumbnails** for
image/tex; per-layer editors: image/tex **preview**+**metadata** (id, z/sub-z,
offset — **editable** for old-style image headers)+replace+export (and a tex layer's
**alpha mask** gets its own preview+replace+export), **Ogg player**
(play/stop/draggable seek), **live animation playback** (anim frames resolved to
sibling image layers, composited at their true relative size + per-frame offset),
tooltip/pagina **text**, props/action/**mat2**/**anim**/**neg**/**obst**/**boneoff**/**light**
**JSON** (lossless-or-raw), `code`/`codeentry` **read-only** view (+ `.class`
export), **dependency/reference view** for `deps`/`rlink`/`src` (read-only;
`src` exports as `.java`), **rig view** for `skel`/`skan`/`manim`
(read-only structural display), font/midi replace+export, raw replace+export, a
built-in **3D viewer** (whole-model, software-rendered — see below), 3D →
**Export/Rebuild glTF**. Layer
ops: **Add / Delete / Move up·down** (layer type/name is read-only).
Toolbar (two rows, with separators): row 1 **Open File · Fetch from Server · Open from
Cache (AppData)**; row 2 **View 3D · Export to glTF · Rebuild from glTF · References…**; the
**resource-version spinner** (uint16) sits on the file-path bar below. Menu accelerators:
Open Ctrl+L, Fetch Ctrl+R, **Open from game cache Ctrl+O**, Save As Ctrl+S.
**Edit → Undo/Redo** (Ctrl+Z/Y, snapshot-based). Full **file-path bar** under the toolbar.

## 5. Architecture (packages under `src/main/java/resforge/`)
- `Main` — CLI dispatch + GUI launch.
- `io/` — `MessageReader`/`MessageWriter` (LE primitives mirroring `haven.Message`,
  incl. `float16` half-precision ↔ float, `cpfloat` custom-packed float, and
  `mnorm16`/`snorm16`/`unorm16`/`oct2uvec` norm helpers), `Json` (dependency-free JSON),
  `SafeFiles` (atomic write: temp file + `Files.move(ATOMIC_MOVE)` so a crash/full-disk
  mid-save can't truncate the original — used by every CLI/GUI `.res`/`.glb` write).
  `MessageReader` is hardened against hostile input: overflow-safe bounds
  (`n<0 || n>end-pos`), strict-UTF-8 `string()` (rejects malformed bytes instead of
  substituting U+FFFD, so decode→encode stays byte-exact). `Json` rejects truncated
  `\u`/dangling escapes and duplicate object keys.
- `res/` — `ResContainer` (parse/serialize the container; rejects implausible layer
  lengths — negative or > remaining bytes — so a crafted file can't trigger an OOM),
  `Layer` (name+bytes, immutable), `Manifest` (manifest.txt + per-layer codec; layer
  names/codecs are backslash-escaped so a name containing a tab/newline still
  round-trips, and `res-version` is range-checked to uint16), `Unpacker`/`Packer`
  (the "parts" model; codecs `raw|tex|props|action|anim|neg|obst|boneoff|light|mat2`;
  `Packer` rejects unknown codec names and part paths that escape the unpack dir —
  no `../`/absolute traversal), `Replacer`
  (one-shot swap), `Verifier` (batch round-trip + histograms), `Catalog` (folder
  listing), `References` (aggregate the external resources a `.res` references).
- `layers/` — read/locate decoders: `ImageInfo`, `TexInfo`, `AudioInfo`, `FontInfo`,
  `ImageMagic`, `Vbuf2Info`, `MeshInfo`, `TtoSkip`, `CodeInfo`, `CodeEntryInfo`,
  `DepsInfo`, `RLinkInfo`, `SrcInfo`, `LightInfo`, `SkelInfo`, `SkanInfo`,
  `BoneOffInfo`, `MeshAnimInfo` (read-only); typed JSON codecs `PropsCodec`,
  `ActionCodec`, `Mat2Codec`,
  `AnimCodec`,   `NegCodec`, `ObstCodec`, `BoneOffCodec`, `LightCodec` (tto/record ↔ JSON, lossless-or-raw); header-field
  codecs `ImageHeaderCodec` (id/z/subz/offset/nooff + build new image layers),
  `TexHeaderCodec` (id/offset/size), `TexMaskCodec` (extract/replace a tex layer's
  alpha mask — part tag 4 — recomputing its int32 length, format-checked),
  `AudioHeaderCodec` (clip id + volume) — all
  lossless-or-raw, image/audio bytes kept verbatim. The typed JSON codecs share a
  `Nums` helper that range-checks integer fields/counts on encode, so an out-of-range
  *edit* (e.g. `anim` `delay:70000`) fails loudly instead of silently wrapping on the
  wire. `ImageInfo` locates the embedded image by parsing the header **exactly** (the
  new-style tto block via `TtoSkip`) — no magic-byte scanning, so a stray "BM"/JPEG
  byte pair in a header can't be mistaken for the image and corrupt a replace/export.
- `model/` — `Vbuf2Data` (de-quantise vertices + decode bone weights for export),
  `ModelGeometry` (assemble a triangle soup — positions+normals+**UVs**, Haven Z-up,
  with a per-triangle texture slot — from `vbuf2`+`mesh` for the in-app 3D viewer;
  reuses the same decoders as the glTF export), `LocalTextures` (resolve the
  `matid → mat2 → local tex` chain to raw image bytes, mirroring the export; external
  `mlink`/variable-material textures are left unresolved),
  `Vbuf2Codec` (structure-preserving vbuf2 encode, with general per-attribute
  `decodeAttr`/`setAttr` re-quantisation), `M4` (column-major 4×4 maths),
  `GltfExport` (geometry → Blender-ready binary glTF `.glb`, with both UV sets,
  embedded textures **and skinning** — skel→skin, bone weights→`JOINTS_0`/
  `WEIGHTS_0` — dependency-free),
  `GltfImport.rebuild` (regenerate `vbuf2`+`mesh`(+`bones2`/`bones`/`manim`) from an
  edited `.glb` at a new vertex count → re-quantises pos/nrm/both UVs into their original
  on-wire formats, Y-up→Z-up, rebuilds skinning weights via
  `Vbuf2Codec.setBones2` and morph shapes via `MeshAnimInfo.encodeWith`, recomputes
  tangents and re-poses the `skel` skeleton, keeping all other layers; allows
  reshaped/added/removed geometry, not byte-lossless). Rebuild **bakes un-applied
  glTF node transforms** (a Blender object moved/scaled/rotated without "Apply
  Transform") into positions (and normals via the inverse-transpose) instead of
  dropping them; **renormalizes** skin weights after dropping joints that don't map;
  and **validates** its glТF input (GLB/JSON/BIN chunk bounds, accessor in-range,
  triangle-mode + index range, `setAttr` length) so a malformed `.glb` fails cleanly
  rather than corrupting a layer.
- `audio/` — `OggVorbis` (Ogg → PCM via JOrbis).
- `net/` — `ResourceFetcher` (`<base>/<path>.res` GET, JDK HttpClient), `CacheIndex`
  (reads the local Haven `HashDirCache` at `%APPDATA%\Haven and Hearth\data`: each
  `%016x.%d` file's header is `byte(1)`+`writeUTF(cid)`+`writeUTF(name)`, decoded with
  `DataInputStream.readUTF`; `res/`-prefixed names are the fetchable resource paths —
  scanned in parallel, sorted/deduped — so the GUI/CLI can list what the player has and
  re-fetch it fresh. Implemented from the client format; no third-party code).
- `gui/` — `ResForgeFrame`, `GuiSupport` (per-layer preview/text/export, reuses
  decoders), `ImageView`, `AudioPlayerPanel`, `AnimView` (offset-aware sprite playback),
  `FetchHistory` (remembered fetch-path suggestions — pure logic, unit-tested),
  `Model3DView` (the **View 3D** software renderer: a hand-written z-buffered triangle
  rasteriser into a `BufferedImage`, two-sided Lambert head-light shading,
  **perspective-correct texture mapping** (local textures, alpha-mask cutout) + optional
  wireframe, mouse orbit/zoom/pan; no native libs/OpenGL, fed by `model/ModelGeometry`
  + `model/LocalTextures`).
  Heavy work (open/parse, glTF export, glTF rebuild, 3D-geometry build) runs on a
  background thread and marshals the result back via `invokeLater`, so large files
  don't freeze the EDT; the Ogg player joins the previous play thread before restarting
  so two threads never share the line.

## 6. Per-layer status
| Layer | Status |
|-------|--------|
| `image` | edit: swap embedded PNG/JPEG; **edit header** (id/z/sub-z/offset/nooff) for old-style; new image layers get a wrapped header |
| `tex` | edit: swap 3D texture + **alpha mask** (`tex`/`TexMaskCodec` recompute the int32 length); **edit header** (id/offset/declared size) |
| `audio2` | edit: swap Ogg; **edit clip id + volume** (ver-2 header); GUI plays it |
| `font` | edit: swap TTF/OTF (sfnt). 2-byte header + font tail |
| `midi` | edit: swap `.mid` (whole payload) |
| `props` | edit as JSON (tto list, lossless-or-raw) |
| `action` | edit as JSON (deterministic AButton record) |
| `mat2` | edit as JSON (id + per-command tto value lists; tagged-value form, lossless-or-raw) |
| `anim` | edit as JSON (sprite animation: id + delay + frame image-ids; deterministic) |
| `neg` | edit as JSON (click hotspot + bounds + endpoint groups; all int16, lossless) |
| `obst` | edit as JSON (collision polygons; float16 coords, lossless-or-raw) |
| `boneoff` | edit as JSON (equip-point opcode program: translate/rotate/eqpoint/bonealign/scale; cpfloat exact, quantised rotation kept as raw octahedral ints, lossless-or-raw) |
| `light` | edit as JSON (light source: id, ambient/diffuse/specular colours 0..1, optional attenuation/direction/exponent; cpfloat ver0 / float32 ver1, lossless-or-raw) |
| `tooltip`/`pagina` | edit as UTF-8 text |
| `vbuf2`/`mesh` | **editable via glTF round-trip**: decoded; GUI shows vertex/attribute + tri/vbuf/material detail; Export/Rebuild glTF |
| `code`/`codeentry` | **read-only**: class name + `.class` export; entrypoint→class + classpath manifest shown |
| `deps`/`rlink`/`src` | **read-only reference view**: explicit dependency list (`deps`: name@ver), resource links + decoded specs (`rlink`), embedded source files (`src`, `.java` export) |
| `skel`/`skan` | **read-only rig view**: bone hierarchy (`skel`: names/parents/positions), skeletal animation (`skan`: length/mode/per-bone tracks + fx events) |
| `manim` | **read-only**: mesh/morph animation — id, length, play order, per-frame vertex-morph format + counts |
| everything else (`tileset2`,`clamb`,`foodev`,`rdesc`,…) | **raw passthrough** (lossless) |

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
  `NormNumber.java`, `TexR.java`, `VertexBuf.java`, `Skeleton.java`, `Light.java`,
  `MeshAnim.java`, and the dev's `mkres-fragment.py` (Ogre-XML→binary encoder).
  LGPL — keep notices. (Full client also at `..\hafen-client` as the format oracle.)
- `samples/` (gitignored, copyrighted game assets): real `.res` + raw png/wav.
  `verify samples` → **all pass** (a mislabeled non-resource — zero bytes + a raw
  PNG — was removed; the tool correctly rejects such files). Histograms confirm all
  image/tex/audio/font/props/action/**mat2**/**anim**/**neg** decode/round-trip
  exactly, all `vbuf2` re-encode byte-exact, all `mesh` decode, and all
  `code`/`codeentry`/`deps`/`src`/`rlink`/`light`/`skel`/`skan`/`boneoff`/`manim`
  decode. **Every layer type present in the samples is now decoded** (no raw
  unknowns left). (Counts grow as more samples are added; `verify` is the live
  oracle — keep it all-pass.)

## 9. Conventions
- Lossless-or-raw: never expose a typed editor unless decode→encode is byte-exact
  (verified). Untouched layers always pass through unchanged.
- **Hostile-input safe**: the parser is hardened to fail cleanly (clear exception),
  never OOM/hang/corrupt, on a crafted or truncated `.res` — overflow-safe reader
  bounds, length validation, strict UTF-8, no magic-scanning. Typed-codec *edits*
  are range-checked (`Nums`) so a bad value is rejected, not silently wrapped.
- **Atomic writes**: all `.res`/`.glb` output goes through `io/SafeFiles` (temp +
  atomic rename), so an interrupted save never destroys the original/only copy.
- `Layer` is immutable; edits *replace* it (enables cheap snapshot undo).
- Edits route through `Replacer` where possible (tested, format-checked).
- Commit per feature with a `Co-authored-by: Copilot …` trailer; keep all three
  builds green (Gradle/Maven/Ant); verify on real `samples/` before claiming done.
- **Keep docs in lockstep with code.** Every add/change updates the docs in the
  *same* commit: this primer (`AI-CONTEXT.md`), the per-layer table, the `README`
  where relevant, and a `kb/notes/` entry for new format findings. A change isn't
  done until its docs match — treat stale docs as a bug.

## 10. Open / next steps
- **3D round-trip via glTF** (decided 2026-06-21 with the game dev): glTF, not Ogre
  XML (no modern Blender importer) and not OBJ (no multi-UV / skeleton). **Phase 1
  (export) is complete** — `GltfExport` writes a static textured `.glb`
  (positions/normals + both UV sets + per-submesh materials/textures, Z-up→Y-up),
  **skinning** (skel → connected glTF skin, bone weights → `JOINTS_0`/`WEIGHTS_0`),
  **skeletal animations** (`skan` → translation/rotation channels), **and mesh-morph
  animations** (`manim` → morph targets + weight animation). All Blender-confirmed
  (knarr: upright, textured, posable, sails ripple). External-skeleton characters
  get identity-placed named joints. **Bringing edits back is done via topology
  rebuild** — `GltfImport.rebuild` (CLI `rebuild-gltf`, GUI **Rebuild from
  glTF**) regenerates `vbuf2`+`mesh`(+`bones2`/`bones`+`manim`) from the edited glTF at
  its own vertex count, so you can reshape, re-UV, add, remove or re-topologize vertices
  and faces (and whole parts). It re-quantises positions/normals/both UV sets into each
  attribute's *original* on-wire format (f4/sn2/un2/uvec1…) and axis-inverts (Y-up→Z-up),
  while keeping every other layer (materials, textures, code). It needs no per-vertex ids
  and isn't byte-lossless (in-game-validated). **Multi-submesh works**: each glTF primitive
  becomes a submesh; the export emits one material per matid (`rfmat_<matid>`) so
  rebuild recovers each part's id from its material name and Blender doesn't merge
  parts sharing a texture. Handles **skinning weights** (both `bones2`
  and the legacy `bones` v0 header — `JOINTS_0`/`WEIGHTS_0` mapped to bone *names* via
  the skin, since Blender reorders joints; top-4 influences re-encoded via
  `Vbuf2Codec.setBones2`) **and morph
  (`manim`) models** (frame shapes rebuilt from glTF morph targets, re-encoded at the
  new vertex count via `MeshAnimInfo.encodeWith`, frame count unchanged). Normal-mapped
  models (`tan`/`bit`) work
  too — the tangent basis is recomputed from the new positions/UVs (Lengyel + Gram-
  Schmidt; Haven stores `bit` identical to `tan`, matched to ~1.3° median). **Rebuild
  also re-poses the skeleton** (change-gated: a plain reshape leaves `skel`
  byte-identical, a moved bone is re-encoded as ver1 via `SkelInfo.encodeVer1` —
  mnorm16 angle + snorm16 octahedral axis + float32 pos).
  Validated
  no-op on male + mulberry/cutblade/fairystone + wisp/algaeblob + stallion/lilypadlotus
  (legacy `bones` v0 + skel) + **knarr** (multi-part
  + morph + skinned + normal-mapped, `oar` bone +7 re-poses skel); cutblade add/remove
  confirmed in-game. **Next:
  animation-keyframe editing.**
  The Haven *encode* toolkit is fully in the client
  (`Utils.hfenc`/`uvec2oct`, `Message.add*`, `NormNumber` encoders) +
  `mkres-fragment.py` for the mesh choices — no dev code needed.
- Typed editor for **`obst` is now done** (collision polygons → JSON via `ObstCodec`,
  using the new `float16` codec under lossless-or-raw). The same `float16` codec can
  broaden `props`/`mat2` to expose float16-bearing values that still stay raw.
- Typed editor for **`boneoff` is now done** (equip-point opcode program → JSON via
  `BoneOffCodec`, lossless-or-raw): translate/rotate/eqpoint/bonealign/scale ops, with
  a new exact `MessageWriter.cpfloat` encoder (inverse of `Utils.floatd`). The quantised
  rotation (opcode 17/19) keeps its axis as raw octahedral `snorm16` ints because the
  octahedral round-trip isn't byte-exact (drifts ±1 on 4/20 sample instances), so storing
  the raw components keeps all 28 sample boneoffs losslessly editable. The friendly
  cpfloat/float32 translations edit as plain numbers. (`BoneOffInfo` still backs the
  one-line table summary + CLI catalog.)
- Typed editor for **`light` is now done** (light source → JSON via `LightCodec`,
  lossless-or-raw): id + ambient/diffuse/specular colours (raw 0..1 fractions, not
  0–255) + optional attenuation/direction/exponent tags; ver0 cpfloat / ver1 float32,
  both byte-exact (reusing the `MessageWriter.cpfloat` encoder); extras re-emitted in
  tag order. Both sample lights — wisp (ver0 purple point light) and villageidol (ver1
  orange point light) — round-trip byte-exact. (`LightInfo` still backs the table
  summary + CLI catalog.)
- **Read-only dependency/reference view is now done** for `deps`/`rlink`/`src`
  (`DepsInfo`/`RLinkInfo`/`SrcInfo`) — shows what other resources a `.res` references.
  The **aggregated cross-layer reference report is also done** (`res/References`,
  CLI `refs`, GUI **References…** toolbar dialog): collects every external resource
  from `deps` + `rlink` + `codeentry` classpath + `mat2` links (string command
  values containing `/`, e.g. `mlink`/external `tex`), deduped with provenance.
  (`anim` frames are local image-ids, so they contribute nothing.)
- **Read-only rig/light viewers are now done** for `light`/`skel`/`skan`/`boneoff`
  (`LightInfo`/`SkelInfo`/`SkanInfo`/`BoneOffInfo`) and `manim`
  (`MeshAnimInfo`, mesh/morph animation), ported from the client's
  `Light.java`/`Skeleton.java`/`MeshAnim.java` (`boneoff` and `light` have since become
  editable JSON, see above). Added the `cpfloat`/`mnorm16`/
  `snorm16`/`unorm16`/`oct2uvec` io primitives they need. **Every layer type in the
  samples is now decoded** (read-only or editable). These decoders + primitives are
  the groundwork for a future skeleton/animation **write** path (would benefit from
  the dev's `mkres` skeleton encoder — ask when starting that).
- **Robustness/hardening pass is now done** (from a cross-model code review — GPT-5.5
  + Claude Opus 4.8). Closed: container OOM bomb + infinite-loop on crafted lengths
  (overflow-safe `MessageReader` bounds + length validation); malformed-UTF-8 /
  tab/newline layer names (strict UTF-8 decode + escaped manifest fields); pack-side
  path traversal (`Packer` containment check); non-atomic in-place saves (`io/SafeFiles`
  temp+rename, CLI + GUI); silent numeric wrap on typed-JSON edits (`Nums` range
  checks); image new-style split via magic-scan false-positive (exact `TtoSkip` parse);
  unknown manifest codec (rejected); glTF rebuild dropping un-applied node transforms
  (now baked, normals via inverse-transpose), un-renormalized skin weights, and missing
  GLB/accessor/index validation; off-EDT open/export/rebuild; Ogg player double-thread +
  cleanup-in-finally; JSON parser hostile-input (EOF-safe escapes, duplicate-key reject).
  Regression tests: `HardeningTest`, `EditValidationTest`, `JsonParserTest`,
  `GltfNodeTransformTest` (152 tests total, all green). Source-only changes — all three
  builds stay in sync.
- **Fetch path history/autocomplete is now done** (`gui/FetchHistory`): the Fetch
  dialog remembers successful resource paths (persisted via `Preferences`, same as the
  base URL) and lists them below the input as substring-matched, click-to-use
  suggestions (double-click fetches); most-recent-first, case-insensitively deduped,
  capped at 50. Pure-logic helper is unit-tested (`FetchHistoryTest`).
- **Open from game cache is now done** (`net/CacheIndex`, CLI `cache-list`, GUI
  **Cache**/File→*Open from game cache…*): scans the local Haven `HashDirCache`
  (`%APPDATA%\Haven and Hearth\data`) to recover the resource *names* the player
  already has, then re-fetches the chosen one **fresh from the server** (cache → names
  only, so you always open the latest version; never opens stale cached bytes). Header
  decoded with `DataInputStream.readUTF` per the client's `HashDirCache`; `res/` names
  are the fetchable paths; parallel scan (~0.7 s over ~44k files), sorted/deduped (~8k
  resources on a real cache). The picker (Ctrl+O) opens immediately showing a
  "scanning…" state and is populated by the background scan (so the UI never looks
  frozen), reuses `FetchHistory.filter` for live substring filtering, and sorts/greys
  the `dyn/` account-attached resources last (`CacheIndex.ORDER`/`isDynamic`; they may
  be removed server-side, so they're set apart). Unit-tested (`CacheIndexTest`). (Idea
  prompted by the read-only Rust tool `ancientchina/hafen-res`; implemented clean-room
  from the client format.)
- **In-app 3D viewer is now done** (`model/ModelGeometry` + `model/LocalTextures` +
  `gui/Model3DView`, GUI **View 3D** toolbar button): a dependency-free **software
  renderer** (hand-written z-buffered triangle rasteriser into a `BufferedImage`,
  two-sided Lambert head-light shading, **perspective-correct texturing** with
  alpha-mask cutout, optional wireframe overlay, mouse orbit/zoom/pan) showing the
  model in its bind/rest pose — no native libs/OpenGL/JavaFX, matching the project's
  pure-Java ethos. `ModelGeometry` assembles a triangle soup (positions/normals/UVs +
  a per-triangle texture slot) from `vbuf2`+`mesh` (reusing the glTF-export decoders);
  `LocalTextures` resolves the `matid→mat2→local tex` chain (mirroring the export).
  Unit-tested (`ModelGeometryTest`, `LocalTexturesTest`); confirmed visually on male
  (textured humanoid), mulberry (alpha-tested foliage) and knarr (21-part ship, local
  parts textured, the rest shaded). **Tier 1** (shaded + wireframe) and **Tier 2 part 1**
  (local textures) are done. **Tier 2 part 2 (TODO): variable materials** — knarr and
  many models get their textures from a *variable material* (`varmat`) declared in
  `code`/`codeentry`, not a local `tex`; those parts currently fall back to flat shading.
  Resolving varmat (decode the varmat spec / external `mlink` textures, possibly fetching
  the referenced resource) would texture them. **Tier 3 (later): animation playback**
  (skeletal/morph).
- GUI niceties are considered complete. **Batch re-skin a folder** is declined
  (won't do — folder-wide modding is already scriptable via the CLI `catalog` +
  `replace`, and a true batch needs a per-file mapping few users would set up), as
  is **layer search/filter**.

## 11. The other tool (context)
CarryGun's **HafenResourceTool** (GitLab, Qt/C++): broader typed coverage +
working OBJ/Ogre-XML import/export and per-type editors, but **re-encodes typed
layers** (round-trip fidelity depends on each encoder; mitigated by an edited
flag) — less lossless-safe than our parts model. Useful as a format reference.
