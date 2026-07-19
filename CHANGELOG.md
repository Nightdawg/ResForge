# Changelog

All notable changes to ResForge are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Animation previews now combine every `skan` layer.** **All clips** is the
  default even when layers have different durations or playback modes; mixed layers
  share a repeating viewer timeline while each independently follows its authored
  once, loop, pong, or pong-loop timing.
- **Open equipped-item previews now update live while `boneoff` JSON is edited.**
  Valid drafts are debounced into the modeless preview without changing the resource
  or undo history; incomplete/invalid JSON keeps the last valid rendered transform
  until editing resumes. **Apply JSON** remains the explicit save point.
- **`boneoff` layers can now be previewed on an animated player.** The layer editor's
  **Preview equipped…** action selects a visible player model, bind skeleton, and
  arms animation from disk or the resource server, then renders the open weapon
  attached to the live skeletal pose. All supported translate, rotate, equip-point,
  bone-align, null-rotation, and scale operations are evaluated in client order.

### Fixed

- **The 3D viewer no longer mirrors models horizontally.** Its camera now preserves
  authored left/right orientation, including animated player models in equipped-item
  `boneoff` previews, while horizontal orbit dragging continues to follow the pointer.
- **Combined Blender actions can now rebuild multi-layer skeletal animations.**
  Editing `skan_combined` routes each bone track back to its original disjoint
  `skan` layer, while untouched combined actions continue to allow individual-action
  edits. Conflicting edits to both representations are rejected explicitly.
- **Rebuilding modern mesh resources no longer drops their materials.** Modern
  `mesh` layers retain their typed `mat`/`ref` metadata, and glTF exports give each
  such layer a stable material identity so Blender preserves distinct submeshes.
  Older ResForge exports that merged those layers can be recovered when their
  triangle ranges are unchanged.

## [1.2.1] — 2026-07-13

### Fixed

- **Multi-layer skeletal animations now appear completely in Blender.** Compatible,
  disjoint `skan` layers export both as their editable `skan_<id>` actions and as one
  `skan_combined` preview action, ordered first so Blender activates the complete pose
  after import. Explicit loop-closing keys preserve the resource's declared duration,
  including static one-key poses, without changing no-edit rebuilds. Zero-duration
  poses receive a synthetic one-second Blender edit window that collapses back to
  their original zero-time representation on import.
- **Duplicate-ID and near-unit skeletal animations now round-trip unchanged.** Actions
  carry their SKAN layer occurrence when multiple layers share an id, and quaternion
  equivalence normalizes both operands before measuring angular drift. A sample-wide
  audit now passes all 143 self-contained models and all 117 borka animations.
- **Opening the game-cache resource picker no longer repeatedly scans map entries.**
  ResForge saves the resource-name index and reuses it while the Haven cache directory
  remains unchanged, with automatic full-scan recovery for stale or damaged indexes.
- **The 3D viewer remains responsive while models are rotated continuously.**
  Render requests now retain only the latest pending camera state without repeatedly
  cancelling the active frame, and reuse interpolated reciprocal depth during rasterisation.

## [1.2.0] — 2026-07-12

### Added

- **Skeletal animations can now round-trip through Blender.** Standalone `skan`
  resources export with a separately selected bind skeleton and skinned preview
  model, then import edited translation/rotation actions while preserving unchanged
  layers and control/effect tracks. The latest Blender key can shorten or extend a
  clip when it has no control/effect track. New `gltf-skan` and `rebuild-skan` CLI
  commands mirror the GUI workflow.
- **The 3D viewer can play skeletal animations.** `View 3D` on a standalone
  animation asks for its bind skeleton and preview model, then offers composed
  **All clips** playback plus individual clip inspection, play/pause, stop, speed,
  and timeline scrubbing using generation-gated CPU skinning.
- **Animation companions can be fetched directly from the resource server.** Each
  bind-skeleton/model row now offers **Fetch from server…** alongside **Browse**;
  validated resources stay in memory and work for both View 3D and glTF export
  without requiring local `.res` files. The selected local paths or server path/base
  pair persist across restarts; server resources re-fetch lazily and cache per session.

### Changed

- **Standalone animation export now explains its two companion resources up front.**
  One compact dialog shows the bind-skeleton and preview-model paths together, with
  Browse buttons, validation, remembered choices, and nearby `body.res`/`male.res`
  defaults, replacing two unexplained sequential file prompts. The shared dialog is
  action-aware: **View 3D** uses preview wording and a **View** button, while glTF
  export uses **Export**.
- **Dark mode now uses an IntelliJ-inspired layered palette.** Near-black content
  surfaces, raised controls and toolbars, clearer borders and separators, subdued
  blue selections, and matching preview checkerboards improve visual hierarchy.

### Fixed

- **Blender no longer makes inactive skeletal actions look simultaneously edited.**
  Blender re-exports bake inactive actions against the active pose, which can flatten
  their motion and corrupt several `skan` layers at once. Rebuild now imports only
  Blender's first (active) skeletal action and leaves every inactive layer byte-identical.
- **Blender-exported constant skeletal channels now import correctly.** Constant
  `STEP` translation/rotation channels and sampled identity-scale channels are
  accepted because they do not change motion, including tiny translation drift
  introduced when snapping Blender keyframes; nonconstant STEP motion and real scale
  edits remain rejected because `skan` cannot represent them faithfully.
- **The layer table once again shows clear row and column boundaries.** Grid lines
  use the original Windows-theme grey and inter-cell spacing for clear contrast,
  including on selected rows, instead of depending on look-and-feel defaults.
- **The Gradle wrapper is upgraded to 8.14.4.** This moves the build off versions
  affected by CVE-2026-22816 and pins the official binary distribution SHA-256.
- **Fat JARs now carry project and third-party license metadata.** Every build
  packages ResForge's MIT license, third-party notices, JOrbis license/source
  location, JNA license, and FlatLaf Apache-2.0 text under `META-INF/licenses/`.
- **Large or rapidly changing previews no longer block or exhaust the Swing UI.**
  Encoded image size and dimensions are checked before copy/decode; image, animation,
  texture-palette, triangle, framebuffer, and cumulative raster work have explicit
  budgets. Image/thumbnail/animation decode and 3D texture/raster work now run on
  daemon workers with generation-gated EDT publication, cached 3D frames, cancellable
  rasterization, explicit preview failures, and deterministic disposal.
- **Audio decode/playback callbacks are now generation-safe.** Rapid pause/play,
  selection changes, and frame disposal invalidate stale threads; each playback
  generation owns its `SourceDataLine`, which is closed deterministically.
- **glTF rebuild now preserves every VBUF2 format accepted by the reader.**
  Editing support now includes `f1`, `sf9995`, `rn1/rn2/rn4`, and `uvech` in
  addition to the existing float, normalized, and octahedral formats.
- **Resource fetch paths are now encoded safely as URI path segments.** Spaces and
  non-ASCII names are UTF-8 percent-encoded, while query/fragment markers, existing
  percent escapes, controls, empty segments, and dot segments are rejected.
- **VBUF2 attribute metadata now has one source of truth.** Fixed element counts
  are shared by the inspector, decoder, and editor; the structure-preserving codec
  moved to neutral `resforge.vbuf`, removing the `res → model` verifier dependency.
- **Gradle, Maven, and Ant fat JARs now use the same entry layout.** Classpath
  artifacts explicitly omit dependency root `module-info.class` and Maven-only
  project metadata, producing identical entry-name sets across all three builds.
- **The software 3D renderer now interpolates z-buffer depth correctly.**
  Reciprocal depth is interpolated in screen space, matching perspective projection,
  so overlapping oblique triangles no longer resolve in the wrong order.
- **Unpack manifests are now published atomically.** `manifest.txt` is still
  written after all layer parts, but now uses the same temporary-file-and-rename
  path as resource output so interruption cannot leave a truncated manifest.
- **Windows file-dialog COM errors now trigger the fallback picker.** Only the
  documented `ERROR_CANCELLED` HRESULT is treated as user cancellation; failures
  such as access denied or `E_FAIL` mark the native dialog unavailable.
- **The custom JSON reader now enforces RFC 8259 syntax.** Leading plus signs,
  leading-zero integers, malformed fractions/exponents, unescaped controls, and
  lone Unicode surrogates are rejected; valid surrogate pairs remain supported.
- **Fetch history can no longer block a valid download from opening.** Downloaded
  bytes are validated as a resource before history is recorded, and serialized
  history is bounded to the Preferences value limit; history persistence failure
  occurs only after the valid document is already open.
- **glTF node transforms now apply correctly to imported morph shapes.** Morph
  position deltas receive the mesh node's accumulated rotation and scale, but not
  translation, before conversion back to Haven coordinates.
- **Repeated image edits no longer retain obsolete layer payloads indefinitely.**
  The layer-table thumbnail cache now stores only thumbnail-capable layers, uses a
  256-entry LRU bound, decodes asynchronously without duplicate pending work, and
  evicts replaced/deleted or non-restored undo/redo layers.
- **Failed Save As attempts no longer change the active file path.** ResForge now
  updates the window title/path and clears dirty state only after serialization
  and atomic persistence succeed; write failures leave the current document and
  destination unchanged.
- **glTF export no longer assigns unrelated textures to unmapped materials.**
  Materials without an explicit local `matid → mat2 → tex` mapping—including
  `matid=-1` and external-only materials—now omit `baseColorTexture` instead of
  inheriting the first embedded texture.
- **Empty glTF exports are structurally valid JSON-only GLBs.** Resources with no
  exportable primitives, including skeleton-only resources, no longer emit a
  dangling mesh node, empty required arrays, a zero-length buffer, or an empty BIN
  chunk.
- **Rejected text/JSON replacements no longer report success.** The GUI apply
  path now returns whether a mutation was committed, so malformed JSON,
  unsupported codec values, and out-of-range numbers leave payload, dirty/undo
  state, and status consistent instead of displaying a false “Updated” message.
- **Quantized BoneOff rotation edits no longer wrap modulo one turn.**
  `angleTurns` now rejects non-finite values and values outside the representable
  `0` through `65535/65536` range instead of masking values such as `1.5` into
  an unrelated `0.5`-turn rotation.
- **Malformed UTF-8 text layers can no longer be silently rewritten.** The
  `tooltip`/`pagina` editor now requires strict UTF-8 decoding and byte-identical
  re-encoding. Invalid payloads stay raw and expose only Replace/Export actions
  instead of displaying replacement characters that would change bytes on Apply.
- **Late GUI workers can no longer overwrite a newer document or edit.** Open,
  fetch, and glTF rebuild operations now capture the active document identity,
  revision, and operation generation; stale completions are discarded. Rebuild
  also shows an application-modal progress dialog, preventing conflicting user
  actions while geometry is regenerated.
- **Resource downloads now have a 64 MiB response limit.** Fetching uses a
  bounded streaming subscriber: an excessive `Content-Length` is rejected before
  body allocation, and chunked/unknown-length responses are cancelled as soon as
  they cross the same limit instead of growing until the JVM runs out of memory.
- **Deeply nested typed-object data no longer crashes layer parsing.** The
  `rlink`, `codeentry`, and `mat2` readers now cap nested `tto` lists/maps at
  256 levels. Crafted resources that previously escaped malformed-layer handling
  with a `StackOverflowError` are now rejected through each parser's normal
  failure path.
- **HiDPI/fractional-scaling: the GUI no longer renders too small.** On a monitor
  using fractional Windows display scaling (e.g. a 4K screen at 150%), the Windows
  Look&Feel handed back its core control fonts (`Label`, `Button`, `TextField`,
  `Table`, `List`, …) at roughly the 96-dpi point size *divided* by the scale
  factor — Tahoma 7 instead of ~12 — while menu fonts stayed correct. Since Java
  already applies the display's render transform to everything, those mis-sized
  fonts came out ~1/scale too small and the whole editor looked tiny, even though
  the layout scaled fine. ResForge now normalises the Look&Feel's default fonts
  after selecting it, lifting any that are too small up to a trusted reference size
  while preserving each font's family and style. The correct *logical* size is
  DPI-independent (the transform handles pixel density), so this is a no-op on a
  100%-scaled display and fixes 125%/150%/200% (`gui/UiScaling`).

### Added

- **Deterministic redistributable regression coverage** now exercises CLI dispatch,
  verifier ordering/histograms, font and skeletal-animation inspection, layer moves,
  matrix math, and `OggVorbis.decode` without copyrighted game assets. The decoder
  fixture is CC0 and pins its audio metadata and decoded PCM output.

- **Dark mode.** A new **Options → Dark mode** toggle switches the whole editor
  between a light and a dark theme, applied live (no restart) and remembered
  between launches. The GUI now uses the [FlatLaf](https://www.formdev.com/flatlaf/)
  Look&Feel to provide both themes; the transparency checkerboard and placeholder
  text behind image/animation previews adapt to the active theme, and muted hint
  labels re-tint on a live switch (`gui/Theme`).

- **Modern Windows file dialog with an address bar (paste a folder/file path).**
  On Windows, the **Open** and **Save as** pickers now use the modern Explorer
  "Common Item Dialog" instead of the legacy Win32 one, so you get the editable
  breadcrumb **address bar** — paste a full folder or file path straight into the
  top bar rather than clicking through folders. Implemented by driving the COM
  `IFileOpenDialog`/`IFileSaveDialog` interfaces via JNA
  (new `gui/WinFileDialogs`), run on a dedicated STA thread and made modal to the
  editor window. It degrades gracefully: on any failure — and on every non-Windows
  platform — it falls back to the previous `java.awt.FileDialog`, so nothing can
  crash or regress. Adds **JNA** (`jna` + `jna-platform`, dual-licensed
  LGPL-2.1+/Apache-2.0) as a bundled dependency, folded into the fat jar like
  JOrbis; see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
- **Optional manual UI-scale override — now settable in the GUI.** Choose
  **Options → UI scale…** to pick a scale factor (0.5–4.0; 1.0 = the automatic
  default) that makes the editor uniformly larger or smaller — not just the fonts,
  but the layout too (table row height and thumbnails, panel/preview sizes, the
  window, spinners, paddings), so everything stays proportional at any factor. It's
  saved to your preferences and applied on the next launch (the app tells you a
  restart is needed). For scripted/one-off use the same override is still available
  at launch via `RESFORGE_UI_SCALE` (environment variable) or `-Dresforge.uiScale`
  (JVM property); a launch override takes precedence over the saved GUI preference.

## [1.1.0] — 2026-06-28

### Added

- **`props` layers now expose far more `tto` value types as editable JSON.** The
  `props` codec adopts the same explicit tagged-value JSON form as `mat2`: a string
  stays a plain JSON string, and every other value is a single-key object naming its
  exact `tto` type — `{"u8":50}`, `{"f32":0.5}`, `{"color":[204,204,204,255]}`,
  `{"coord":[x,y]}`, `{"bytes":"<base64>"}`, `{"list":[…]}`, `{"map":{…}}`. It now
  models coord, color, fcolor, fcoord32/fcoord64, byte blobs (base64), float32, uid,
  resid and resource specs in addition to the JSON-native types, so props that used to
  fall back to raw are now editable (validated on `belltower`'s `use-point` `fcoord64`).
  The lossless-or-raw guard is unchanged, and a handful of types still kept raw on
  purpose (float8/float16 and the snorm/unorm/mnorm numbers, whose round-trip isn't
  provably byte-exact).
- **Resolve external static textures in the 3D viewer.** A **Resolve external textures
  (network)** toggle in the **View 3D** window textures parts whose base comes from an
  *external static material* — an `mlink`/external `tex` string naming **one fixed
  resource** (e.g. a tree's bark, which lives in a separate `…-tex` resource). It fetches
  that resource and follows its own `matid→mat2→tex` chain (`model/ExternalTextures`:
  injectable fetcher, per-path cache, depth cap + cycle guard). Off by default, since the
  viewer is otherwise fully offline, and **shown only when the model actually has external
  static materials** (detected offline via `ExternalTextures.hasExternalStatic`, no fetch);
  resolved parts are textured but not added to the per-material picker (the linked palette
  isn't theirs to swap). Runtime *variable materials* (wood-type swaps) and `Dyntex` `spr` sprite additions are out of scope and
  stay shaded — their final image isn't stored in the model `.res`. Validated end-to-end on
  the mulberry tree (bark + berries texture once resolved).
- **Per-material texture picker in the 3D viewer.** The **View 3D** window now shows a
  dropdown for each *locally-textured* material (split across two balanced rows when a
  model has several), listing the resource's own `tex` layers (by id), so you can choose
  which texture a part is drawn with — e.g. flip a tree's leaves between their seasonal
  variants live (mulberry carries four). The full local-texture palette is offered,
  including variants no mesh uses by default. A material whose base is variable/external
  (a `varmat` declared in `code`, an `mlink` to another resource, or only a local `otex`
  overlay) gets no dropdown — the local palette isn't its to swap — so a model like knarr
  shows one picker (its one locally-textured part) rather than ten.

### Changed

- **The `props` `.json` shape is now explicitly tagged.** Values that previously
  appeared bare (an integer `50`, a list `[…]`, a nested map) are now single-key
  tagged objects (`{"u8":50}`, `{"list":[…]}`, `{"map":{…}}`), so the exact `tto`
  width/type is recorded and a JSON object is unambiguously a tag (matching `mat2`).
  Re-unpack any props `.resdir` that was unpacked with an older build before repacking.
- **The "Fetch from server" dialog now focuses the Resource path field on open.**
  Opening the dialog (toolbar **Fetch from Server**, **File ▸ Fetch from server**, or
  **Ctrl+R**) puts the keyboard cursor straight in the *Resource path* box, so you can
  type a path immediately without first clicking the field.

### Fixed

- **Local 3D texture lookup resolved by id, not position.** A material's
  `tex`/`otex` command selects its texture by the `tex` layer's **id** (the game's
  `flayer(TexR.class, id)` lookup), but the **View 3D** viewer and the **glTF export**
  were treating that value as a positional index into the resource's `tex` layers.
  Models whose `tex` ids aren't `0,1,2,…` in order rendered/exported with the wrong
  texture — e.g. the mulberry tree (`tex` ids `1,3,4,5`, four seasonal leaf variants)
  showed its leaves with the wrong foliage variant. Both paths now map the id to the
  matching `tex` layer. (Parts whose texture lives in *another* resource — e.g. the
  mulberry trunk via `mlink` — remain shaded, as documented.)

## [1.0.0] — 2026-06-24

First stable release. ResForge decompiles, edits, and recompiles Haven & Hearth
`.res` files — as a Swing GUI and a scriptable CLI — with a **lossless-by-construction**
guarantee: unchanged layers are preserved byte-for-byte, and a typed editor is only
offered for a layer when its decode→edit→encode round-trip is proven byte-exact
("lossless-or-raw"). Validated against a real ~8.8k-file corpus (`verify`: all pass).

### Editing

- **2D images** (`image`) — swap the embedded PNG/JPEG (any size; length recomputed)
  and edit the header (id, z/sub-z, draw offset, no-offset flag); new image layers are
  wrapped in a fresh header with the next free id.
- **3D model textures** (`tex`) — swap the texture (JPEG/PNG) and its separate **alpha
  mask**, and edit the `tex` header (id, atlas offset, declared size).
- **Sounds** (`audio2`) — swap the Ogg Vorbis clip and edit its id + volume; the GUI
  plays it (play/stop/draggable seek).
- **Fonts** (`font`) and **MIDI** (`midi`) — swap the embedded typeface / music file.
- **Terrain tiles** (`tile`) — swap a ground/transition tile image.
- **Typed JSON editors** (lossless-or-raw): `props`, `action`, `mat2`, `anim`
  (sprite animation: speed + frames), `neg` (click hotspot + connection points),
  `obst` (movement-collision polygons), `boneoff` (equip-point opcode program),
  and `light` (light source: colours, attenuation, direction).
- **Text** (`tooltip`, `pagina`) — edit as UTF-8.
- **3D models** (`vbuf2`/`mesh` + `skel`/`skan`/`manim`) — a full **glTF round-trip**
  through Blender: **Export to glTF** writes a self-contained `.glb` (geometry, both
  UV sets, embedded textures, skeleton + skinning, skeletal and mesh-morph animations);
  **Rebuild from glTF** regenerates geometry so you can reshape/sculpt, re-UV, add,
  remove or re-topologize vertices/faces and whole parts (positions/normals/UVs/weights
  re-quantised into the original on-wire formats, tangents recomputed, skeleton re-posed,
  morph shapes re-shaped). Multi-part, skinned, morph-animated and normal-mapped models
  are supported. Not byte-lossless — verify rebuilt models in-game.

### Viewing & inspection

- **Built-in 3D viewer** (**View 3D**) — a dependency-free pure-Java software renderer:
  z-buffered rasteriser, two-sided Lambert shading, perspective-correct texturing with
  alpha-mask cutout, optional wireframe, mouse orbit/zoom/pan, in the model's bind pose.
- **Live sprite-animation preview** — `anim` frames resolved to sibling image layers and
  composited at their true relative size and per-frame offset.
- **Read-only views** for `code`/`codeentry` (class names + classpath manifest; `.class`
  export), the rig layers `skel`/`skan`/`manim`, and the dependency/reference layers
  `deps`/`rlink`/`src` (`src` exports as `.java`).
- **References report** (toolbar **References…** / CLI `refs`) — one deduplicated list of
  every resource a file references, aggregated across `deps`, `rlink` (all link types),
  `tileset2`/`flavobj`, `code` classpaths and `mat2` material links.

### Fetching

- **Fetch from server…** — download a resource straight from the game's resource server
  by its in-game path; successful paths are remembered as substring-matched, click-to-use
  suggestions.
- **Open from game cache…** — scan the local Haven cache for the resource *names* you
  already have, then fetch the chosen one **fresh from the server** (so you always open
  the latest version; cached bytes are never opened directly).

### Command-line interface

Every operation is scriptable: `gui`, `info`, `catalog`, `refs`, `fetch`,
`cache-list`, `replace`, `unpack`/`pack`, `gltf`, `rebuild-gltf`, and `verify`.

### Reliability & safety

- **Lossless-or-raw** typed editing — never expose a typed editor unless decode→encode
  is byte-exact; untouched layers always pass through unchanged.
- **Atomic writes** — all `.res`/`.glb` output goes through a temp file + atomic rename,
  so an interrupted/full-disk save can't truncate your only copy.
- **Hostile-input hardening** — overflow-safe reader bounds, layer-length validation,
  strict UTF-8, recursion-depth caps, range-checked typed edits, and validated glTF
  input, so a crafted or truncated file fails cleanly rather than hanging, OOM-ing, or
  corrupting output.
- **371 JUnit 5 tests** and a batch round-trip oracle (`verify`); the release corpus
  passes 8,805 / 8,805 resources, and three equivalent builds (Gradle, Maven, Ant)
  produce the same runnable fat-jar entry layout.

### Known limitations

See [README → Status / scope → Known limitations](README.md#known-limitations-10).
In short: `skan` playback mode, bone scale, control/effect events, and adding/removing
`manim` frames remain read-only; `code`/`codeentry` are read-only; the glTF rebuild is
not byte-lossless (verify in-game); the 3D viewer shows `varmat`-textured parts shaded;
and a few unusual typed instances stay raw by design (lossless-or-raw).

[Unreleased]: https://github.com/Nightdawg/ResForge/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/Nightdawg/ResForge/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/Nightdawg/ResForge/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/Nightdawg/ResForge/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Nightdawg/ResForge/releases/tag/v1.0.0
