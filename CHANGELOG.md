# Changelog

All notable changes to ResForge are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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
- **~190 JUnit 5 tests** and a batch round-trip oracle (`verify`); three equivalent
  builds (Gradle, Maven, Ant) producing the same runnable fat jar.

### Known limitations

See [README → Status / scope → Known limitations](README.md#known-limitations-10).
In short: animation **keyframe** editing (`skan` timing, adding/removing `manim` frames)
is read-only; `code`/`codeentry` are read-only; the glTF rebuild is not byte-lossless
(verify in-game); the 3D viewer shows `varmat`-textured parts shaded; and a few unusual
typed instances stay raw by design (lossless-or-raw).

[Unreleased]: https://github.com/Nightdawg/ResForge/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Nightdawg/ResForge/releases/tag/v1.0.0
