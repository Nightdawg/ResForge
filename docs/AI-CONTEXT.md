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
  all `resforge-1.2.1.jar`. Use the jar (not `gradlew/ant run`) for paths with
  spaces — `--args`/`-Dargs` mangle them.
- **Runtime dependency components (three; four jars):** `org.jcraft:jorbis` 0.0.17
  (LGPL-2.0-or-later, ~97 KB, bundles `jogg`+`jorbis`) — the GUI's Ogg player; **JNA**
  `net.java.dev.jna:jna` +
  `:jna-platform` 5.15.0 (dual LGPL-2.1+/Apache-2.0) — Windows-only, drives the modern
  Explorer file dialog (`gui/WinFileDialogs`), with a `FileDialog` fallback elsewhere;
  and **FlatLaf** `com.formdev:flatlaf` 3.7.2 (Apache-2.0) — Swing light/dark themes,
  with application defaults under `resources/resforge/gui/themes/` providing the
  layered IntelliJ-inspired dark palette and theme-specific table grids.
  JOrbis is vendored for Gradle/Ant and pulled from Central by Maven; JNA and FlatLaf
  are pulled from Central by Gradle/Maven and vendored for Ant. All are folded into
  the jar (Gradle fat-jar via runtimeClasspath; Ant merged runtime staging; Maven
  Shade). All three omit dependency root `module-info.class` and Maven-only
  project metadata, yielding one canonical fat-JAR entry-name set. The CLI never uses
  either.
- **License:** project is **MIT** (`LICENSE`, Copyright Nightdawg). Bundled JOrbis
  stays **LGPL-2.0-or-later**, bundled JNA stays **LGPL-2.1+/Apache-2.0**, FlatLaf stays
  **Apache-2.0**, and the
  `docs/reference/*.java` client files stay **LGPL-3** — all documented in
  `THIRD-PARTY-NOTICES.md`; fat JARs carry canonical copies under
  `META-INF/licenses/` (keep those notices on redistribution).
  Only the core JOrbis decoder is used, never the GPL JOrbisPlayer.
- Verifying the GUI: launch the jar, screenshot the screen, view the PNG. GUI
  mouse/keys automation is flaky — prefer screenshotting + trusting shared code
  paths. Always `Stop-Process -Id <PID>` test windows (never kill IntelliJ).

## 3. CLI commands (`resforge.Main`)
`gui [file]` · `fetch <path> [out.res]` · `info <file>` · `refs <file>` ·
`unpack <file> [dir]` · `pack <dir> [out]` · `replace <file> <selector> <newfile> [out]` ·
`gltf <file> [out.glb]` ·
`gltf-skan <animation.res> <skeleton.res> <model.res> [out.glb]` ·
`rebuild-gltf <orig.res> <edited.glb> [out.res]` ·
`rebuild-skan <orig.res> <edited.glb> [out.res]` ·
`catalog <file|dir>` · `cache-list [cacheDir]` · `verify <file|dir>`.
No args (with a display) → launches the GUI. (`refs` lists every resource a
`.res` references, aggregated across `deps`/`rlink`/`codeentry`/`mat2`. `gltf`
exports the 3D model as a Blender-ready binary glTF, and `rebuild-gltf`
regenerates geometry from an edited `.glb` to allow reshaped/added/removed vertices.
`gltf-skan` combines a standalone animation, bind skeleton and preview model, emits
editable per-layer actions plus a composed preview action for compatible disjoint
layers (ordered first so Blender activates it), and writes explicit loop-closing keys
through the declared clip duration; zero-duration static poses get a synthetic
one-second edit window that import collapses back to one zero-time frame; duplicate
ids are qualified by SKAN layer occurrence so each action rebuilds independently;
`rebuild-skan` imports edited translation/rotation actions while preserving unchanged
animation bytes and raw control tracks. An edited `skan_combined` action is split back
into its original disjoint layers by bone ownership; an untouched combined action
still allows individual edits, while conflicting combined+individual edits are rejected.
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
version; persists and reuses the name index until the cache directory changes, avoiding
repeated reads of map-entry headers); layer table with **thumbnails** for
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
built-in **3D viewer** (whole-model, software-rendered — see below; with a
**per-material texture picker** to swap each part's local `tex`, e.g. seasonal
leaves, plus CPU-skinned `skan` playback with composed/individual clip selection,
play/pause/stop, speed and timeline scrub; selected `boneoff` layers expose
**Preview equipped…**, selecting a player model + bind skeleton + animation and
rendering the open resource rigidly attached to the animated pose), 3D →
**Export/Rebuild glTF**. Layer
ops: **Add / Delete / Move up·down** (layer type/name is read-only).
For standalone `skan` export, one companion-resource dialog shows both required
sources (bind skeleton and visible preview model), with **Browse** and **Fetch from
server…** per row, validation, session memory, and sibling `body.res`/`male.res`
defaults. Server companions are parsed and retained in memory (not saved to disk)
and feed both View 3D and glTF export. One global skeleton/model pair persists in
Preferences across restarts: local files must still exist, while server path/base
presets re-fetch lazily and cache bytes for the current frame session.
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
  `\u`/dangling escapes, duplicate object keys, non-RFC numbers, unescaped control
  characters, non-finite output, and unpaired Unicode surrogates.
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
- `vbuf/` — `Vbuf2Format` (shared fixed attribute element counts) and
  `Vbuf2Codec` (structure-preserving vbuf2 encode with general per-attribute
  `decodeAttr`/`setAttr` re-quantisation). This neutral package is shared by
  `res` verification and `model` editing without creating a package cycle.
- `layers/` — read/locate decoders: `ImageInfo`, `TexInfo`, `AudioInfo`, `FontInfo`,
  `ImageMagic`, `Vbuf2Info`, `MeshInfo`, `TtoSkip`, `CodeInfo`, `CodeEntryInfo`,
  `DepsInfo`, `RLinkInfo`, `SrcInfo`, `LightInfo`, `SkelInfo`, `SkanInfo`,
  `BoneOffInfo`, `MeshAnimInfo`, `TileInfo` (terrain-tile header + image),
  `TilesetInfo`/`FlavObjInfo` (tileset tiler/tags/flavors + flavor-object refs) (read-only);
  typed JSON codecs `PropsCodec`,
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
  with a per-triangle **material index** + the full local-texture **palette** and each
  textured material's default texture + a `localBase` flag (local `tex` base vs
  non-local: external-static/varmat/`otex`-overlay) — from `vbuf2`+`mesh` for the in-app 3D viewer;
  reuses the same decoders as the glTF export), `LocalTextures` (resolve the
  `matid → mat2 → local tex` chain to raw image bytes, mirroring the export; the
  `tex`/`otex` command's index is the **tex layer's id** — `flayer(TexR.class, id)` —
  not its position; `isLocalBaseTex(matid)` distinguishes a local base `tex` from a
  non-local one (external-static `mlink`/external string, runtime varmat, or `otex`-overlay
  only)), `ExternalTextures` (resolve an *external static* base — an `mlink`/external
  `tex` string → one fixed resource — by fetching that resource via an injectable
  `Fetcher` and following its own `matid→mat2→tex` chain; per-path cache, depth cap +
  cycle guard; offline/no-fetcher resolves nothing. `ModelGeometry.from(res, fetcher)`
  appends the resolved images to the palette so the viewer's "Resolve external textures"
  toggle can texture those parts; `hasExternalStatic(res)` is an offline (no-fetch) check
  the viewer uses to show that toggle only when a model has such materials; runtime
  varmat / `Dyntex` stay shaded),
  `M4` (column-major 4×4 maths), `SkanPlayback` (client-equivalent skeletal pose +
  CPU skinning), `BoneOffPlayback` (combines player/item viewer geometry and evaluates
  all `boneoff` transforms against each animated bone pose), `GltfExport` (geometry → Blender-ready binary
  glTF `.glb`, with both UV sets,
  embedded textures **and skinning** — skel→skin, bone weights→`JOINTS_0`/
  `WEIGHTS_0` — plus per-layer/composed skeletal actions with explicit loop-closing
  keys; dependency-free),
  `GltfImport.rebuild` (regenerate `vbuf2`+`mesh`(+`bones2`/`bones`/`manim`) from an
  edited `.glb` at a new vertex count → re-quantises pos/nrm/both UVs into their original
  on-wire formats, Y-up→Z-up, rebuilds skinning weights via
  `Vbuf2Codec.setBones2` and morph shapes via `MeshAnimInfo.encodeWith`, recomputes
  tangents and re-poses the `skel` skeleton, keeping all other layers; modern mesh
  tto metadata (`mat`/`ref` plus unknown fields verbatim) is retained through a
  per-layer `rfmat_<matid>_mesh_<ordinal>` glTF identity, with unchanged-triangle
  recovery for older exports that collapsed those layers; allows
  reshaped/added/removed geometry, not byte-lossless). Rebuild **bakes un-applied
  glTF node transforms** (a Blender object moved/scaled/rotated without "Apply
  Transform") into positions, normals via the inverse-transpose, and morph deltas
  via rotation/scale without translation instead of dropping them; **renormalizes**
  skin weights after dropping joints that don't map;
  and **validates** its glТF input (GLB/JSON/BIN chunk bounds, accessor in-range,
  triangle-mode + index range, `setAttr` length, and **non-finite vertex values** —
  a NaN/Inf coordinate is rejected because it would otherwise poison a quantised
  attribute's shared max factor and decode every vertex back to NaN) so a malformed
  `.glb` fails cleanly rather than corrupting a layer.
- `audio/` — `OggVorbis` (Ogg → interleaved signed 16-bit little-endian PCM via
  JOrbis). `OggVorbisTest` exercises a contributor-supplied CC0 stereo fixture
  and pins its sample rate, frame count, duration, and decoded PCM SHA-256.
- `net/` — `ResourceFetcher` (`<base>/<path>.res` GET, one shared lazily-created JDK
  HttpClient — holder idiom, so the pure `urlFor`/`baseName` helpers start no threads;
  resource names are strict UTF-8 URI path segments (spaces/non-ASCII encoded;
  query/fragment/percent/control/dot-segment ambiguity rejected);
  response bodies cap at 64 MiB via a bounded streaming subscriber, rejecting an
  excessive `Content-Length` before allocation and cancelling chunked/unknown-length
  responses at the same boundary),
  `CacheIndex`
  (reads the local Haven `HashDirCache` at `%APPDATA%\Haven and Hearth\data`: each
  `%016x.%d` file's header is `byte(1)`+`writeUTF(cid)`+`writeUTF(name)`, decoded with
  `DataInputStream.readUTF`; `res/`-prefixed names are the fetchable resource paths —
  scanned in parallel, sorted/deduped; the GUI persists the resulting list under the
  user's application-cache directory and reuses it while the Haven cache directory
  modification time is unchanged — so the GUI/CLI can list what the player has and
  re-fetch it fresh. Implemented from the client format; no third-party code).
- `gui/` — `ResForgeFrame` (the window: layer table, toolbar/menus, undo/redo,
  file/fetch/save orchestration + background threading), `LayerEditors` (builds the
  right-hand per-layer detail/editor panel, one `build*Panel` per kind; calls back
  through the small `EditorHost` interface so it owns no document/dialog/threading
  state), `FetchDialog` / `CachePickerDialog` (the "Fetch from server" and "Open from
  game cache" modal dialogs, extracted as self-contained pickers that return the chosen
  path+base — the frame still does the actual fetch via `fetchFromServer`),
  `WinFileDialogs` (Windows-only JNA helper that shows the modern Explorer file
  open/save dialog with the address bar via COM `IFileOpenDialog`/`IFileSaveDialog`;
  `ResForgeFrame` prefers it and falls back to `FileDialog` off-Windows or on failure),
  `GuiSupport` (per-layer preview/text/export, reuses
  decoders), `PreviewBudget` (encoded-byte + metadata-first bounded image decode and
  aggregate animation/palette/render limits), `ImageView`, `AudioPlayerPanel`, `AnimView`
  (offset-aware sprite playback; sibling images are indexed once and unique requested
  frames decode on a generation-gated daemon worker),
  `FetchHistory` (remembered fetch-path suggestions — pure logic, unit-tested),
  `ThumbnailCache` + `ThumbnailLoader` (EDT-confined 256-entry LRU for image/texture
  table thumbnails; generation-gated worker decode coalesces pending layers, and
  obsolete layer keys are removed on replace/delete and pruned on undo/redo),
  `Model3DView` (the **View 3D** software renderer: a hand-written z-buffered triangle
  rasteriser into a `BufferedImage`, two-sided Lambert head-light shading,
  **perspective-correct texture mapping and reciprocal z-buffer depth** (local textures,
  alpha-mask cutout) + optional
  wireframe, mouse orbit/zoom/pan; no native libs/OpenGL, fed by `model/ModelGeometry`
  + `model/LocalTextures`. Carries the full local-texture **palette** + a per-material
  default + a `localBase` flag; `setMaterialTexture(matIndex, paletteOrd)` re-points a
  material, and the dialog shows one combo only per **locally-textured** material
  (non-local bases — external-static `mlink`/varmat/`otex`-only — get none) — split over **two
  balanced rows** (testable `ResForgeFrame.buildTexturePickerRows`) so many-material
  models stay compact; so a model's alternate `tex` layers, e.g. mulberry's seasonal
  leaves, can be selected live, while knarr shows one picker not ten). Texture/mask
  palettes decode on the model workers; each view rasterises immutable render-state
  snapshots on its own daemon worker, completing the active frame while coalescing
  rapid input to one latest pending state, while Swing paint only scales the latest
  cached frame. Triangle, internal-framebuffer, and cumulative raster-work budgets
  bound that work; disposal cancellation is checked inside the raster loops, and
  budget failures become visible preview errors instead of partial frames.
  `model/SkanPlayback` retains soup-aligned top-four influences only for
  animation views, evaluates client-equivalent bind+delta poses and wrap modes, and
  CPU-skins positions/normals on a generation-gated daemon worker. Multi-layer player
  poses default to an **All clips** composite (the game applies those body-part mods
  together), while each numbered `skan` remains inspectable.
  Closing/replacing a view invalidates late renders and external-resolution callbacks.
  Heavy work (open/parse, glTF export, glTF rebuild, 3D-geometry build) runs on a
  background thread and marshals the result back via `invokeLater`, so large files
  don't freeze the EDT. Document-replacing open/fetch/rebuild workers capture one
  shared operation generation plus the active document identity/revision; a completion
  is discarded if a newer operation, document load, edit, undo, or redo made its
  snapshot stale. glTF rebuild additionally uses an application-modal indeterminate
  progress dialog, so the user cannot start a conflicting document action while it
  runs. The Ogg player generation-gates decode/playback callbacks and atomically owns
  one `SourceDataLine`; pause, stop, selection changes and frame disposal invalidate
  stale work and close the active line.

## 6. Per-layer status
| Layer | Status |
|-------|--------|
| `image` | edit: swap embedded PNG/JPEG; **edit header** (id/z/sub-z/offset/nooff) for old-style; new image layers get a wrapped header |
| `tex` | edit: swap 3D texture + **alpha mask** (`tex`/`TexMaskCodec` recompute the int32 length); **edit header** (id/offset/declared size) |
| `audio2` | edit: swap Ogg; **edit clip id + volume** (ver-2 header); GUI plays it |
| `font` | edit: swap TTF/OTF (sfnt). 2-byte header + font tail |
| `midi` | edit: swap `.mid` (whole payload) |
| `props` | edit as JSON (tagged `tto` values: str/int/float/color/coord/bytes/…, lossless-or-raw) |
| `action` | edit as JSON (deterministic AButton record) |
| `mat2` | edit as JSON (id + per-command tto value lists; tagged-value form, lossless-or-raw) |
| `anim` | edit as JSON (sprite animation: id + delay + frame image-ids; deterministic) |
| `neg` | edit as JSON (click hotspot + bounds + endpoint groups; all int16, lossless) |
| `obst` | edit as JSON (collision polygons; float16 coords, lossless-or-raw) |
| `boneoff` | edit as JSON (equip-point opcode program: translate/rotate/eqpoint/bonealign/scale; cpfloat exact, quantised rotation axis kept as raw octahedral ints and angle range-checked, lossless-or-raw) |
| `light` | edit as JSON (light source: id, ambient/diffuse/specular colours 0..1, optional attenuation/direction/exponent; cpfloat ver0 / float32 ver1, lossless-or-raw) |
| `tooltip`/`pagina` | edit as strict UTF-8 text only when decode→encode preserves the original bytes; malformed payloads stay raw |
| `vbuf2`/`mesh` | **editable via glTF round-trip**: decoded; GUI shows vertex/attribute + tri/vbuf/material detail; Export/Rebuild glTF |
| `code`/`codeentry` | **read-only**: class name + `.class` export; entrypoint→class + classpath manifest shown |
| `deps`/`rlink`/`src` | **read-only reference view**: explicit dependency list (`deps`: name@ver), resource links + decoded specs (`rlink`), embedded source files (`src`, `.java` export) |
| `skel`/`skan` | structural view is read-only; **editable via glTF round-trip**: model rebuild can re-pose `skel`, while standalone `skan` actions export with a selected skeleton/model and import translation/rotation keyframes; clip metadata/effect tracks are preserved |
| `manim` | layer view is **read-only**; glTF rebuild can replace each frame's morph shape when the original frame count is preserved, but does not add/remove/retime frames |
| `tile` | edit: swap the terrain tile image (PNG/JPEG; runs to EOM like `image`); shows kind (ground/border/centre-transition), id, weight |
| `tileset2`/`flavobj` | **read-only**: tileset tiler name + tags + flavor objects (`tileset2`); the sprite/sound a flavor spawns (`flavobj`) — both feed the `refs` report |
| everything else (`clamb`,`foodev`,`overlay`,`slink`,`plparts`,`rdesc`,…) | **raw passthrough** (lossless) |

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
  `http://game.havenandhearth.com/res/` (the official server; same as the client);
  each resource-name segment is URI-encoded rather than concatenated raw.
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
  bounds, length validation, strict UTF-8, no magic-scanning, and recursion-depth
  caps (`Json` object/array nesting plus `PropsCodec`, `TtoSkip`, `RLinkInfo`,
  `CodeEntryInfo`, and `Mat2Codec` tto list/map nesting cap at 256), so a deeply
  nested document fails through normal malformed-input handling rather than a
  `StackOverflowError` that would escape `catch(RuntimeException)` guards.
  Network-fetched resource bodies are capped at 64 MiB for both declared and
  unknown/chunked lengths.
  Typed-codec *edits*
  are range-checked (`Nums`) so a bad value is rejected, not silently wrapped.
- **Atomic writes**: all `.res`/`.glb` output and final unpack `manifest.txt`
  publication goes through `io/SafeFiles` (temp + atomic rename), so interruption
  never leaves the original/only copy or manifest truncated.
- **Background results are revision-gated**: any worker that can replace the active
  document must capture `DocumentRevision.Token` and complete it before changing GUI
  state. Every content edit and undo/redo advances the revision; every load advances
  document identity.
- `Layer` is immutable; edits *replace* it (enables cheap snapshot undo).
- Edits route through `Replacer` where possible (tested, format-checked).
- Commit per feature. Every AI-assisted commit must credit the assistant used:
  include the `Co-authored-by: Copilot …` trailer and a `Powered by <model/tool>`
  paragraph naming the actual AI model or tool. Keep all three builds green
  (Gradle/Maven/Ant); verify on real `samples/` before claiming done.
- **Keep docs in lockstep with code.** Every add/change updates the relevant docs in
  the *same* commit: this primer for architecture/features/current work,
  `DESIGN-notes.md` for detailed design and reverse-engineering history, `README.md`
  and the per-layer table for user-visible behavior, and `kb/notes/` for durable
  format findings and decisions. A change isn't done until its docs match.

## 10. Project status
No open or deferred work is currently tracked. Known unsupported cases and
deliberate non-goals are documented where the affected feature is described;
they are not roadmap items. Completed feature history belongs in `CHANGELOG.md`
and `DESIGN-notes.md`.

## 11. The other tool (context)
CarryGun's **HafenResourceTool** (GitLab, Qt/C++): broader typed coverage +
working OBJ/Ogre-XML import/export and per-type editors, but **re-encodes typed
layers** (round-trip fidelity depends on each encoder; mitigated by an edited
flag) — less lossless-safe than our parts model. Useful as a format reference.
