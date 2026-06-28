# Changelog

All notable changes to ResForge are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
