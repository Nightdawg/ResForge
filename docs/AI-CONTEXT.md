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
`obj <file> [out.obj]` · `gltf <file> [out.glb]` ·
`import-gltf <orig.res> <edited.glb> [out.res]` ·
`rebuild-gltf <orig.res> <edited.glb> [out.res]` ·
`transform <file> <sx> <sy> <sz> [out]` ·
`catalog <file|dir>` · `verify <file|dir>`.
No args (with a display) → launches the GUI. (`refs` lists every resource a
`.res` references, aggregated across `deps`/`rlink`/`codeentry`/`mat2`. `gltf`
exports the 3D model as a Blender-ready binary glTF and `import-gltf` re-imports
an edited `.glb` back into the model (same vertex count, re-quantised); `obj` is
the simpler legacy export.)
`replace` selector: layer name (`image`), name+occurrence (`tex#2`), or index (`#5`).

## 4. GUI (`resforge.gui.ResForgeFrame`)
Open / drag-drop / **Fetch from server…**; layer table with **thumbnails** for
image/tex; per-layer editors: image/tex **preview**+**metadata** (id, z/sub-z,
offset — **editable** for old-style image headers)+replace+export, **Ogg player**
(play/stop/draggable seek), **live animation playback** (anim frames resolved to
sibling image layers, composited at their true relative size + per-frame offset),
tooltip/pagina **text**, props/action/**mat2**/**anim**/**neg**
**JSON** (lossless-or-raw), `code`/`codeentry` **read-only** view (+ `.class`
export), **dependency/reference view** for `deps`/`rlink`/`src` (read-only;
`src` exports as `.java`), **rig/light view** for `light`/`skel`/`skan`/`boneoff`/`manim`
(read-only structural display), font/midi replace+export, raw replace+export, 3D →
**Export OBJ**. Layer
ops: **Add / Delete / Move up·down** (layer type/name is read-only).
Toolbar: Open, Fetch, Save As, Export OBJ, **Export glTF** (Blender-ready .glb),
**Import glTF** (re-import an edited .glb into the model),
References… (aggregated reference report dialog), **resource-version spinner** (uint16).
**Edit → Undo/Redo** (Ctrl+Z/Y, snapshot-based). Full **file-path bar** under the toolbar.

## 5. Architecture (packages under `src/main/java/resforge/`)
- `Main` — CLI dispatch + GUI launch.
- `io/` — `MessageReader`/`MessageWriter` (LE primitives mirroring `haven.Message`,
  incl. `float16` half-precision ↔ float, `cpfloat` custom-packed float, and
  `mnorm16`/`snorm16`/`unorm16`/`oct2uvec` norm helpers), `Json` (dependency-free JSON).
- `res/` — `ResContainer` (parse/serialize the container), `Layer` (name+bytes,
  immutable), `Manifest` (manifest.txt + per-layer codec), `Unpacker`/`Packer`
  (the "parts" model; codecs `raw|tex|props|action|anim|neg|mat2`), `Replacer`
  (one-shot swap), `Verifier` (batch round-trip + histograms), `Catalog` (folder
  listing), `References` (aggregate the external resources a `.res` references).
- `layers/` — read/locate decoders: `ImageInfo`, `TexInfo`, `AudioInfo`, `FontInfo`,
  `ImageMagic`, `Vbuf2Info`, `MeshInfo`, `TtoSkip`, `CodeInfo`, `CodeEntryInfo`,
  `DepsInfo`, `RLinkInfo`, `SrcInfo`, `LightInfo`, `SkelInfo`, `SkanInfo`,
  `BoneOffInfo`, `MeshAnimInfo` (read-only); typed JSON codecs `PropsCodec`,
  `ActionCodec`, `Mat2Codec`,
  `AnimCodec`,   `NegCodec`, `ObstCodec` (tto/record ↔ JSON, lossless-or-raw); header-field
  codecs `ImageHeaderCodec` (id/z/subz/offset/nooff + build new image layers),
  `TexHeaderCodec` (id/offset/size), `AudioHeaderCodec` (clip id + volume) — all
  lossless-or-raw, image/audio bytes kept verbatim.
- `model/` — `Vbuf2Data` (de-quantise vertices + decode bone weights for export),
  `Vbuf2Codec` (structure-preserving vbuf2 encode, with general per-attribute
  `decodeAttr`/`setAttr` re-quantisation), `M4` (column-major 4×4 maths),
  `GltfExport` (geometry → Blender-ready binary glTF `.glb`, with both UV sets,
  embedded textures **and skinning** — skel→skin, bone weights→`JOINTS_0`/
  `WEIGHTS_0`, plus a stable `_VID` per vertex for re-import — dependency-free),
  `GltfImport` (re-import an edited `.glb` → maps each glTF vertex back to its
  original index by `_VID`, re-quantises pos/nrm/both UVs into their original
  on-wire formats, Y-up→Z-up, re-imports skinning weights to `bones2` via
  `Vbuf2Codec.setBones2` and morph shapes to `manim` via `MeshAnimInfo.encodeWith`
  when they changed, keeping all other layers byte-identical),
  `ObjExport` (simpler geometry → Wavefront OBJ + a `.mtl` and the local `tex`
  image(s), so models open textured).
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
| `obst` | edit as JSON (collision polygons; float16 coords, lossless-or-raw) |
| `tooltip`/`pagina` | edit as UTF-8 text |
| `vbuf2`/`mesh` | **read-only**: fully decoded; GUI shows vertex/attribute + tri/vbuf/material detail; OBJ export (+ `.mtl` + local `tex` image, textured); `transform` write path |
| `code`/`codeentry` | **read-only**: class name + `.class` export; entrypoint→class + classpath manifest shown |
| `deps`/`rlink`/`src` | **read-only reference view**: explicit dependency list (`deps`: name@ver), resource links + decoded specs (`rlink`), embedded source files (`src`, `.java` export) |
| `light` | **read-only**: light source — type (point/spot/directional), id, ambient/diffuse/specular colours, attenuation/direction/exponent (cpfloat ver0, float32 ver1) |
| `skel`/`skan`/`boneoff` | **read-only rig view**: bone hierarchy (`skel`: names/parents/positions), skeletal animation (`skan`: length/mode/per-bone tracks + fx events), equip-point opcode program (`boneoff`) |
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
- `Layer` is immutable; edits *replace* it (enables cheap snapshot undo).
- Edits route through `Replacer` where possible (tested, format-checked).
- Commit per feature with a `Co-authored-by: Copilot …` trailer; keep all three
  builds green (Gradle/Maven/Ant); verify on real `samples/` before claiming done.
- **Keep docs in lockstep with code.** Every add/change updates the docs in the
  *same* commit: this primer (`AI-CONTEXT.md`), the per-layer table, the `README`
  where relevant, and a `kb/notes/` entry for new format findings. A change isn't
  done until its docs match — treat stale docs as a bug.

## 10. Open / next steps
- **In-game test of the `transform` write path** (user-side; the one thing not
  auto-verifiable). Uniform scale e.g. `2 2 2` should render correct-but-bigger.
- **3D round-trip via glTF** (decided 2026-06-21 with the game dev): glTF, not Ogre
  XML (no modern Blender importer) and not OBJ (no multi-UV / skeleton). **Phase 1
  (export) is complete** — `GltfExport` writes a static textured `.glb`
  (positions/normals + both UV sets + per-submesh materials/textures, Z-up→Y-up),
  **skinning** (skel → connected glTF skin, bone weights → `JOINTS_0`/`WEIGHTS_0`),
  **skeletal animations** (`skan` → translation/rotation channels), **and mesh-morph
  animations** (`manim` → morph targets + weight animation). All Blender-confirmed
  (knarr: upright, textured, posable, sails ripple). External-skeleton characters
  get identity-placed named joints. **Phase 2a (glTF import) is now done** —
  `GltfImport` re-imports an edited `.glb` (CLI `import-gltf`, GUI **Import glTF**):
  it re-quantises positions/normals/both UV sets into each attribute's *original*
  on-wire format (f4/sn2/un2/uvec1…) and axis-inverts (Y-up→Z-up), while keeping
  every other layer — bone weights, triangles, skeleton, materials, code —
  byte-identical (a *patch*, not a rebuild). Blender re-splits vertices at seams
  (the count changes), so the exporter tags each vertex with a stable id `_VID` and
  the importer scatters each glTF vertex back to its original `vbuf2` slot by id
  (reorder/duplicate/re-split safe); seam dups Blender merged away are filled from a
  coincident matched vertex. `_VID` needs **"Data > Mesh > Attributes" enabled in
  Blender's glTF export** (default off; normals/UVs/skins are default on). An
  unchanged model survives res→glb→res byte-for-byte (verified male/knarr/mulberry/
  bull/stallion, 100% matched). User-confirmed end-to-end (head resize in Blender →
  re-import → correct in-game). **Phase 2b (skinning-weight import) is also done** —
  it scatters `JOINTS_0`/`WEIGHTS_0` by `_VID`, maps glTF joints to bone *names* via
  the skin (Blender reorders joints), and re-encodes the top-4 influences into
  `bones2` (`Vbuf2Codec.setBones2`, original f4/un2/un1 format) — render-equivalent
  since the client reduces to top-4. It's change-gated: a pure mesh edit leaves
  `bones2` byte-identical (only actual weight-paint changes re-encode). User-confirmed
  in-game. **Phase 2c morph-shape import is also done** — edited `manim` morph shapes
  (Blender shape keys) re-import: each glTF morph target is a frame's vertex deltas,
  scattered by `_VID`, axis-inverted, re-encoded into `manim` via
  `MeshAnimInfo.encodeWith`, keeping the original timeline (only shapes change;
  Blender's shape-key *animation* round-trip is deliberately sidestepped).
  Change-gated (unchanged manim stays byte-identical). **Phase 2c skeleton import is
  also done** — an edited `skel` rest pose re-imports: each bone's new local transform
  is read from its glTF joint node by name, change-gated (a plain Blender round-trip
  drifts only ~0.04°, verified by diffing knarr before/after Blender, so unchanged stays
  byte-identical), and on a real edit the skeleton is re-encoded as version-1 via
  `SkelInfo.encodeVer1` (mnorm16 angle + snorm16 octahedral axis + float32 pos).
  Scope: topology-preserving edits (reshape/transform/sculpt + re-weight + re-shape
  morphs + re-pose the skeleton; no new geometry). **A separate "rebuild" mode now adds
  geometry changes** — `GltfImport.rebuild` (CLI `rebuild-gltf`, GUI **Rebuild from
  glTF**) regenerates `vbuf2`+`mesh`(+`bones2`) from the glTF at its vertex count, so
  you can add/remove/re-topologize vertices and faces. It needs no `_VID` and isn't
  byte-lossless (in-game-validated), and currently targets single-submesh
  positions/normals/UVs/bone-weights models. **Next: multi-submesh + morph rebuild**,
  then `skan` animation-keyframe editing.
  The Haven *encode* toolkit is fully in the client
  (`Utils.hfenc`/`uvec2oct`, `Message.add*`, `NormNumber` encoders) +
  `mkres-fragment.py` for the mesh choices — no dev code needed.
- Typed editor for **`obst` is now done** (collision polygons → JSON via `ObstCodec`,
  using the new `float16` codec under lossless-or-raw). The same `float16` codec can
  broaden `props`/`mat2` to expose float16-bearing values that still stay raw.
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
  `Light.java`/`Skeleton.java`/`MeshAnim.java`. Added the `cpfloat`/`mnorm16`/
  `snorm16`/`unorm16`/`oct2uvec` io primitives they need. **Every layer type in the
  samples is now decoded** (read-only or editable). These decoders + primitives are
  the groundwork for a future skeleton/animation **write** path (would benefit from
  the dev's `mkres` skeleton encoder — ask when starting that).
- GUI niceties: fetch path history/autocomplete, batch
  re-skin a folder. (No layer search/filter — explicitly declined.)

## 11. The other tool (context)
CarryGun's **HafenResourceTool** (GitLab, Qt/C++): broader typed coverage +
working OBJ/Ogre-XML import/export and per-type editors, but **re-encodes typed
layers** (round-trip fidelity depends on each encoder; mitigated by an edited
flag) — less lossless-safe than our parts model. Useful as a format reference.
