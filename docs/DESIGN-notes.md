# ResForge — Design Notes & Session History

This document captures the reverse-engineering findings and design decisions
behind `ResForge`, exported from the chat session in which the tool was
created. Keep it with the code so the context isn't lost.

- **Created:** 2026-06-19
- **Origin:** Built alongside the Haven & Hearth client repo (`hafen-client`),
  by reverse-engineering `src/haven/Resource.java` and `src/haven/Message.java`.
- **Tool location:** `IdeaProjects/resforge` (standalone Gradle/Java project).
- **Reference sources:** verbatim copies of the authoritative files live in
  [`reference/`](reference/) — `Resource.java`, `Message.java`,
  `NormNumber.java`, `TexR.java`, and `mkres-fragment.py` (see
  `reference/README.md` for provenance and licensing).

---

## 1. Problem & goal

Haven & Hearth downloads `.res` resource files on demand from the server. The
game developer compiles them server-side; he also has a Python `mkres` compiler
(a fragment of which was shared as `hjTpfMwy.py`, covering mainly 3D meshes).

**Goal:** a tool to *decompile* a `.res` into editable pieces, let a modder edit
them, and *recompile* back into a working `.res` — without needing the original
build pipeline. Most common modding use-case: re-skinning (swapping the embedded
texture/icon PNG).

---

## 2. The `.res` container format

Reverse-engineered from `haven.Resource.load()` and `haven.Message`:

```
"Haven Resource 1"   16-byte ASCII signature
uint16  (LE)         resource version
repeat until end of file:
    string           NUL-terminated UTF-8 layer name  (e.g. "image", "tooltip")
    int32   (LE)     layer payload length
    <length bytes>   layer payload
```

All multi-byte integers are **little-endian**. Strings are NUL-terminated UTF-8.

The client registers a decoder per **layer** type (`@LayerName(...)`), e.g.
`image`, `tooltip`, `pagina`, `neg`, `props`, `obst`, `anim`, `action`,
`audio`, `code`, `codeentry`, `font`, plus 3D types `vbuf2`, `mesh`, `manim`,
`bones`/`bones2`. Unknown layers are skipped by the client (`load()` ignores
unregistered names), which means a tool only needs to *understand* the layers it
wants to edit and can pass everything else through untouched.

### Message primitive encodings (from `Message.java`)

The payloads use a typed primitive set. The ones reproduced in this tool's
`io.MessageReader` / `io.MessageWriter`:

| Primitive | Encoding |
|-----------|----------|
| int8/uint8 | 1 byte |
| int16/uint16 | 2 bytes LE |
| int32/uint32 | 4 bytes LE |
| int64 | 8 bytes LE |
| float32/float64 | IEEE-754 LE |
| string | bytes… then `0x00` |
| coord | int32 x, int32 y |

The full `Message` type system also includes `float8`/`float16`, `snorm`/
`unorm`/`mnorm` in 8/16/32-bit, `tto`/list/map typed-object streams, resource
specs, etc. (see `Message.tto0` and `NormNumber.java`). These are only needed
for deeper typed editing (props, meshes) and can be added incrementally.

### `image` layer header (from `Resource.Image`)

```
ver = uint8
if ver < 128:                       # common/legacy form
    z    = int8*256 + ver
    subz = int16
    fl   = uint8                    # fl&1 obsolete, fl&2 = nooff, fl&4 = has-info
    id   = int16
    off  = (int16 x, int16 y)
    if (fl & 4): repeat [ string key; uint8 len (or int32 if high bit set); len bytes ] until key==""
    <encoded image bytes: a standard PNG (or other ImageIO format)>
else (ver-128 == 1):                # newer form, typed (tto) metadata
    id   = int16
    repeat [ string key; tto value ] until key==""
    <encoded image bytes>
```

The embedded image is a normal PNG in the vast majority of cases — read by the
client via `ImageIO.read`. **This is why texture-swapping is easy.**

---

## 3. Design: the "parts" model (lossless by construction)

`unpack` writes a folder: `manifest.txt` + a `layers/` sub-folder. Every layer
becomes one or more **part files**; concatenating a layer's parts in order
reproduces its payload exactly. `pack` simply concatenates parts. Therefore an
untouched unpack→pack is **byte-identical**, and edits are localized.

| Layer type         | Parts written           | Editable as           |
|--------------------|-------------------------|-----------------------|
| `image`            | `*.imghdr` + `*.png`    | swap the PNG texture  |
| `tex`              | `*.pre.bin` + image + `*.post.bin` | swap a 3D model texture |
| `audio2`           | `*.audhdr` + `*.ogg`    | swap a sound (Ogg Vorbis) |
| `props`            | `*.json`                | edit typed props as JSON |
| `action`           | `*.json`                | edit button/keybind as JSON |
| `font`             | `*.fonthdr` + `*.ttf`/`*.otf` | swap the embedded font |
| `midi`             | `*.mid`                 | swap the MIDI music   |
| `tooltip`,`pagina` | `*.txt`                 | edit strict UTF-8 text; malformed payloads stay raw |
| anything else      | `*.bin`                 | raw bytes (lossless)  |

For `image`, the split point (where the encoded image begins) is found by
parsing the header **exactly** — including the new-style typed (`tto`) header,
whose key/value block is stepped over with `TtoSkip` — and is accepted only when
it lands on a real image magic (PNG/JPEG/GIF/BMP). There is no magic-byte scan,
so a signature appearing inside header metadata can't be mistaken for the image
start; a header whose end can't be proven simply stays raw (still lossless).
The header is preserved verbatim in `*.imghdr`, so only the picture changes.
The replacement PNG may be any size — the layer length is recomputed on pack.

### The `tex` layer and the "codec" idea

`tex` layers carry the textures used by 3D models. From `haven.TexR.Encoded`:

```
int16  id
uint16 off.x, off.y
uint16 sz.x,  sz.y
parts, repeated until end-of-message:
    uint8 tag      fl = (tag & 0xc0) >> 6 ;  t = tag & 0x3f
      fl==0 inline:  t==0 color image / t==4 alpha mask -> int32 len, len bytes
                     t==1|2|3 filter -> 1 byte ; t==5 -> 0 bytes
      fl==1: uint8 length-prefixed sub-message
      fl==2: uint8 + int32 length-prefixed sub-message
```

The embedded picture (a normal JPEG/PNG) sits *inside* the payload, preceded by
an `int32` length — unlike `image`, where it runs to the end. So a resized
replacement needs that length recomputed. The layer is therefore split into
three parts — `*.pre.bin` (everything up to the length field), the image file,
and `*.post.bin` (everything after the image, including filters/mask verbatim) —
and marked with a **codec** in the manifest (`tex`). On pack, the default `raw`
codec concatenates parts (unchanged), while the `tex` codec emits
`pre + int32(len(image)) + image + post`, recomputing the length. Untouched
files repack byte-identically; a swapped texture of any size repacks correctly.
Only the first inline color image (the common form) is exposed; anything exotic
(length-prefixed parts, no inline image) falls back to a raw `.bin`.

### The `props` layer — typed editing with a lossless safety net

`props` (from `haven.Resource.Props`) is a `uint8` version (must be `1`) followed
by a `tto` list of alternating string keys and values (see `Message.tto0`). It is
exposed as editable `*.json` via the `props` codec, but with a strict guard
against the type ambiguities of `tto` (e.g. the same integer can be encoded as
`u8`/`u16`/`i32`; a float can be `float32`/`float64`). On unpack the tool:

1. decodes the payload into a JSON value model,
2. serializes it to JSON, re-parses, and re-encodes it, and
3. **only** writes the `.json` part (codec `props`) if the re-encoded bytes
   equal the original; otherwise it falls back to a raw `.bin`.

Each value carries an **explicit type tag** so the exact `tto` encoding is
recorded — the same tagged-value form `Mat2Codec` uses. A string is a plain JSON
string; every other value is a single-key object naming its exact type, e.g.
`{"u8":50}`, `{"f32":0.5}`, `{"color":[204,204,204,255]}`, `{"coord":[x,y]}`,
`{"bytes":"<base64>"}`, `{"list":[…]}`, `{"map":{…}}`. Nested lists/maps are
`T_END`-terminated, with no terminator on the top-level list, and the byte-blob
length prefix mirrors `addtto`'s (single `uint8`, or `0x80` + `int32`).

**Design decision — tag everything (don't leave integers bare).** An earlier
form kept integers/lists/maps as bare JSON; this codec tags them instead. Reasons:
(a) a bare `tto`-map is a JSON object, indistinguishable from a single-key tagged
object, so tagging makes a JSON object *unambiguously* a tag and lets strictly more
layers round-trip; (b) it removes reliance on the JSON number parser to tell an
`int` from a whole-valued `f32`/`f64` (`{"int":2}` vs `{"f32":2.0}` vs `{"f64":2.0}`
are explicit); (c) it records the exact wire width rather than re-deriving the
smallest-integer rule; (d) it keeps `props` symmetric with `mat2` — one tag
vocabulary, one mental model. The cost (a verbose common case, and re-unpacking
props `.resdir`s made by older builds) is accepted.

Supported types: string, nil, the integer widths (u8/u16/i8/i16/int/long),
float32/float64, color, fcolor, coord, fcoord32/fcoord64, byte blobs (base64),
uid, resid, resource specs, and nested list/map. The remaining `tto` types
(float8/float16 and the snorm/unorm/mnorm numbers) are not modelled and keep the
layer raw, since their round-trip isn't provably byte-exact. (Real examples —
`knarr.res`: `{ "place": {"list":["surface","map"]} }`; `belltower.res`:
`{ "use-point": {"fcoord64":[0.0,-0.59]} }`, which used to stay raw.)

The `action` layer (button/keybind metadata, `haven.Resource.AButton`) is a
fixed-shape record — `string parent; uint16 parentVer; string name; string
prereq; uint16 hotkey; uint16 adCount; string[] ad` — with no type ambiguity, so
it is exposed as editable JSON via the same decode → JSON → re-encode → compare
guard (codec `action`). Rebinding a hotkey or renaming a button is a one-line
JSON edit.

`manifest.txt` example:

```
# ResForge manifest
# Layer order is significant — do not reorder entries.
res-version: 7
layer	image	layers/000_image.imghdr,layers/000_image.png
layer	tooltip	layers/001_tooltip.txt
layer	neg	layers/002_neg.bin
layer	tex	layers/003_tex.pre.bin,layers/003_tex.jpg,layers/003_tex.post.bin	tex
layer	props	layers/004_props.json	props
```

Each `layer` line is `layer⇥<name>⇥<part1,part2,…>` with an optional fourth
tab-separated **codec** field (omitted means `raw` = concatenate parts). The
`tex` codec recomputes the embedded image length on repack; the `props` codec
re-encodes JSON to a `tto` stream (see §3).

---

## 4. Project layout

```
resforge/
  build.gradle, settings.gradle      # Gradle, application plugin, JUnit 5, JDK 21 toolchain
  pom.xml                            # Maven build (alternative); deps+plugins from Central, output build-maven/
  build.xml                          # Ant build (alternative); JUnit jars vendored in lib/
  lib/                               # vendored JUnit 5 jars for the Ant build
  gradlew, gradlew.bat, gradle/      # wrapper (Gradle 8.14.4; distribution SHA-256 pinned)
  src/main/java/resforge/
    Main.java                        # CLI dispatch + GUI launch
    io/                              # binary/JSON primitives + atomic file writes
    res/                             # container, parts model, replace/verify/catalog/refs
    layers/                          # layer inspectors and lossless-or-raw codecs
    vbuf/                            # shared VBUF2 format metadata + encoder
    model/                           # glTF export/rebuild and 3D-viewer geometry/textures
    gui/                             # Swing editor, previews, dialogs and software renderer
    net/                             # server fetch + local cache-name index
    audio/                           # JOrbis-backed Ogg decode
  src/test/java/resforge/            # focused codec, model, GUI-logic and hardening tests
  README.md
  docs/DESIGN-notes.md               # this file
```

---

## 5. CLI usage

```sh
# Inspect
./gradlew run --args="info path/to/horse.res"

# Download a resource from the game server by its in-game path
#   (scheme from haven.Resource.HttpSource: <base>/<path>.res;
#    base defaults to http://game.havenandhearth.com/res/)
./gradlew run --args="fetch gfx/borka/male"

# Decompile -> horse.resdir/
./gradlew run --args="unpack path/to/horse.res"

# ...edit layers/000_image.png, layers/001_tooltip.txt, etc...

# Recompile -> horse.res
./gradlew run --args="pack horse.resdir"

# One-shot replacement of a supported media/text/JSON layer
./gradlew run --args="replace horse.res image newicon.png horse.res"
./gradlew run --args="replace theme.res audio2 newsound.ogg theme.res"

# Inspect aggregated external resource references
./gradlew run --args="refs path/to/horse.res"

# Export a 3D model to glTF and rebuild edits back into a resource
./gradlew run --args="gltf path/to/horse.res"
./gradlew run --args="rebuild-gltf path/to/horse.res path/to/horse.glb"

# Catalogue what is editable across a folder of resources
./gradlew run --args="catalog path/to/folder"

# List names in the local game cache (the GUI re-fetches a selected name fresh)
./gradlew run --args="cache-list"

# Validate real files (single file or a folder, recursive)
./gradlew run --args="verify path/to/horse.res"
./gradlew run --args="verify path/to/folder"

# Or use the jar
./gradlew jar
java -jar build-gradle/libs/resforge-1.1.0.jar info horse.res
```

---

## 6. Key decisions & rationale

- **Java + Gradle** (not Python like `mkres`): matches the client so decoders can
  be cross-checked against `Resource.java`/`Message.java` for exact fidelity, and
  opens cleanly as its own IntelliJ project.
- **Standalone sibling project** with its own git repo, rather than a module
  inside `hafen-client` — keeps modding tooling separate from the game client.
- **Parts model over full typed re-encoding**: guarantees losslessness for every
  layer immediately (even ones we don't understand), while still exposing the
  high-value layers (images, text) for editing. Typed editors can be added later
  without changing the pack path (it just concatenates parts).
- **Preview work is bounded and disposable, not part of document semantics.** Encoded
  image byte lengths are capped before worker-side range copying, and dimensions are
  read through `ImageReader` before full decode; per-image and aggregate
  animation/texture limits reject only the preview, never editing or export. Animation
  frames are first-wins range-indexed without copying, then requested unique IDs are
  copied and decoded on one daemon worker. Table thumbnails use a separate coalescing,
  generation-gated worker and publish only while their layer remains in the document.
  The 3D viewer receives a worker-decoded immutable palette and publishes only the
  newest generation of a worker-rasterised, framebuffer-capped image; Swing paint
  merely draws that cache. Triangle/pixel loops check cancellation and a cumulative
  raster-work budget; budget failures publish an explicit preview error. Selection/dialog
  disposal invalidates workers and timers.
- **Minimal third-party deps**: three runtime components. **JOrbis**
  (`org.jcraft:jorbis`, ~97 KB, LGPL-2.0-or-later) — a pure-Java Ogg Vorbis decoder used by the
  GUI's in-app sound player; **JNA** (`net.java.dev.jna:jna` + `:jna-platform`,
  LGPL-2.1+/Apache-2.0) — used Windows-only to show the modern Explorer file dialog
  (`gui/WinFileDialogs`), with a `java.awt.FileDialog` fallback everywhere else; and
  **FlatLaf** (`com.formdev:flatlaf`, Apache-2.0) — the Swing light/dark themes.
  Ant uses all four vendored jars in `lib/` and can build offline; Gradle uses the
  vendored JOrbis jar but resolves JNA/FlatLaf from Central, while Maven resolves all
  runtime components from Central. Every build folds them into the runnable fat jar.
  JUnit is test-only. The CLI uses none of these components, and
  the manifest/JSON formats need no library.

---

## 7. Verification performed

- **Strict text editing (2026-07-10):** GUI editing for `tooltip`/`pagina` uses a
  reporting UTF-8 decoder and requires byte-identical re-encoding before showing the
  text box. Overlong, truncated, and lone-continuation payloads stay raw with
  Replace/Export actions; valid non-BMP text remains editable and round-trips exactly.
  `TextEditingTest` covers the malformed sequences, summaries, non-BMP data, and both
  editor action sets.
- **Stale GUI worker protection (2026-07-10):** open, fetch, and glTF rebuild
  completions now pass through one `DocumentRevision` gate containing document
  identity, monotonic revision, and operation generation. A newer operation, document
  load, edit, undo, or redo makes an older result inapplicable; stale errors, fetch
  history updates, and status changes are gated with the document replacement itself.
  Rebuild displays an application-modal indeterminate progress dialog while its worker
  runs. `DocumentRevisionTest` deterministically completes operations out of order and
  covers edits, replacement, duplicate completion, and explicit invalidation.
- **Bounded resource downloads (2026-07-10):** `ResourceFetcher` no longer uses
  `BodyHandlers.ofByteArray()` for an unbounded response. Its streaming subscriber
  rejects a declared body above 64 MiB before allocation and cancels a chunked or
  unknown-length body when it crosses that boundary. Local `HttpServer` tests cover
  fixed/chunked over-limit responses and exact-boundary success; all 8,804 resources
  in the validation corpus fit (largest 4,622,480 bytes).
- **TTO recursion hardening (2026-07-10):** `RLinkInfo`,
  `CodeEntryInfo`, and `Mat2Codec` now carry an explicit nesting depth through
  every recursive list/map value and reject depth above 256. A crafted payload
  with 12,000 nested containers previously raised `StackOverflowError`, bypassing
  the readers' `catch(RuntimeException)` malformed-layer fallback. The failure is
  now bounded and graceful; `TtoDepthTest` covers deep lists/maps and the exact
  256-level boundary through all three public entry points.
- `./gradlew build` → BUILD SUCCESSFUL; JUnit round-trip tests pass.
- End-to-end CLI run on a synthesized resource (image + tooltip + neg):
  `info` reported metadata correctly; `unpack` split image into `.imghdr`+`.png`,
  tooltip into `.txt`; `pack` reproduced a **byte-identical** 146-byte file.
- **Real-file validation (2026-06-19)** via the new `verify` command against 5
  game files (`apple`, `cutblade`, `mulberry`, `male`, `knarr`; res-versions
  3–90, up to 61 layers, 2.7 KB–462 KB): **5/5 PASS** — container parse/serialize
  and unpack/pack both byte-identical, and every `image` layer's split picture
  re-decodes standalone via `ImageIO`. Layer types seen in the wild: `image`,
  `tooltip`, `props`, `obst`, `rlink`, `deps`, `codeentry`, and the 3D set
  `tex`, `mesh`, `vbuf2`, `mat2`, `boneoff`, `skel`, `skan`, `manim`.
- **Findings worth noting:**
  - The new-style typed (`tto`) `image` header (`ver-128 == 1`) did **not**
    occur in these samples — the one image (`apple`) is old-style. That decode
    path was later reworked to parse the `tto` header exactly (via `TtoSkip`, no
    magic-scan), though it remains untested on a real new-style image.
  - **3D model skins live in `tex` layers, not `image`.** A `tex` payload embeds
    an encoded picture after a short header (e.g. `male`'s first `tex` is a JPEG
    at byte +15). **Now handled** by the `tex` codec (see §3).
- **`tex` codec validation (2026-06-19)** against 8 game files (added `bull`,
  `stallion`, `bearcape-head`): **8/8 PASS**, still byte-identical, and all 15
  `tex` layers decode as standalone JPEGs. End-to-end edit demo on `male.res`:
  unpacked the first `tex` to `000_tex.jpg`, replaced it with a different-sized
  JPEG (12368 → 1635 bytes), repacked (`verify` PASS, embedded length recomputed,
  file 69798 → 59065 bytes), and a re-unpack reproduced the new texture
  byte-for-byte. 3D re-skinning confirmed working on real assets.
- **`props` codec validation (2026-06-19)** against the 8 files: **8/8 PASS**,
  byte-identical. `knarr.res`'s `props` decoded to JSON
  `{ "place": ["surface", "map"] }`; adding a key and repacking passed `verify`
  (462841 → 462867 bytes) and survived a re-unpack. The lossless-or-raw guard
  classifies each props layer as `json` or `raw` in the `verify` report.
- **Large-scale validation (2026-06-19)** — the user's full custom-client asset
  set, **661 `.res` files** (~70 MB; plus 21 raw `.png`/`.wav` the client uses
  directly): **660/661 PASS, byte-identical**. The single "failure" is correct —
  `customclient/uiThemes/Trollex Blue/chat-mid.res` is not a resource at all (11
  zero bytes followed by a raw PNG; bad signature). Aggregate layer counts
  included **669 `image`** (all old-style headers, all split + `ImageIO`-decoded),
  269 `tooltip`, 76 `action`, 32 `audio2`, 30 `pagina`, 21 `tex`, plus `font`,
  `light`, `code`, `src`, `anim`, `obst`, `mesh`/`vbuf2`/`mat2`/… The new-style
  (`tto`) `image` header did **not** appear in any of the 669 images — it is
  effectively unused; the split now parses the `tto` header exactly (the earlier
  magic-scan fallback was removed).
- **`audio2` codec (2026-06-19)**: all **32** audio layers split into `*.ogg`
  (`OggS`-validated). End-to-end on `customclient/sfx/alchemistTheme.res`:
  `info` → `id="cl" vol=1.000 ogg @ +6`; unpack produced a 1.16 MB `.ogg`;
  repack was byte-identical.
- **`action` codec (2026-06-19)**: all **76** action layers exposed as editable
  JSON (`action` histogram = `json 76`). End-to-end on
  `customclient/menugrid/Bots.res`: `info` → `"| Bots |" hotkey=66 'B'`; edited
  the JSON to rebind the hotkey to `71 'G'`, repacked (`verify` PASS), and `info`
  on the result confirmed the change.
- **`font` codec (2026-06-19)**: the sample set's one `font` layer split as
  `ttf` (`font` histogram = `ttf 1`). End-to-end on
  `customclient/uiThemes/Trollex Red/font/cambria.res`: `info` → `ttf @ +2`;
  unpack produced a valid 822 KB `.ttf` (magic `00 01 00 00`); repack was
  byte-identical. `midi` layers (none in the sample set) are exposed as `.mid`.
  Note: `gradlew run --args` mishandles paths containing spaces — use the built
  jar (`java -jar …`) for such paths; the tool itself handles them correctly.
- **`replace` command (2026-06-19)**: one-shot single-asset swap. Demos: a
  generated PNG swapped into `apple.res` (`image` 2742 → 414 bytes, tooltip
  untouched, `verify` PASS); and a cross-file sound swap putting
  `berserkerTheme`'s Ogg into `alchemistTheme.res` (`verify` PASS, length
  recomputed). Format checks reject a wrong file type before writing.
- **`vbuf2` read-only inspector (2026-06-19)**: across the sample set's 11
  `vbuf2` layers, **9 walk fully** (length-prefixed ver≥1) and **2** legacy
  ver=0 layers walk their bare attributes up to the bone data (`verify` Vbuf2
  histogram = `walked 9, stopped@bones 2`). `info` reports e.g. `knarr` → 12838
  verts `[pos2(sn2), nrm2(uvec1), tan2(uvec1), bit2(uvec1), tex2(sn2),
  otex2(sn2), bones2(un1)]`. Read-only; the layers stay lossless raw.
- **Full 3D decode (2026-06-20)**: after adding bone-data walking and the `mesh`
  index decoder (incl. delta-strip), all sample 3D layers decode to the exact end
  of payload — `verify` Vbuf2 histogram = `walked 11`, Mesh histogram =
  `decoded 41`.
- **3D OBJ export (2026-06-20)**: `model/Vbuf2Data` de-quantises vertex
  attributes and `model/ObjExport` writes a Wavefront OBJ. Validated on real
  models: `male.res` → 1325 verts / 2248 tris, bbox x[-1.3,1.7] y[-5.0,5.0]
  z[-0.1,12.8] (humanoid); `knarr.res` → 12838 verts / 16952 tris / 21 submeshes,
  bbox x[-81,84] y[-42,42] z[-16,108] (ship). Counts match the decoders and the
  bounding boxes are geometrically plausible. *(OBJ export was later removed once the
  glTF round-trip superseded it — glTF carries both UV sets, the skeleton and
  animations, and round-trips back to `.res`.)*
- **Structure-preserving vbuf2 encoder (2026-06-20)**: the
  automated oracle — decode→encode of every real `vbuf2` is **byte-identical**
  (`verify` → `Vbuf2 re-encode histogram: exact 11`). A single attribute can be
  re-quantised after an edit (into its original format/scale, i.e. the game's own
  precision) while every untouched attribute stays byte-exact — the foundation the
  glTF rebuild stands on. *(An early CLI `transform` command exercised this by
  scaling a model's vertex positions; it was removed once the glTF round-trip —
  which also handles normals/tangents and is in-game validated — superseded it.)*

---

## 8. The 3D geometry pipeline (reverse-engineered)

Context from the game developer: the 3D models are authored in **Blender** and
exported as **Ogre XML** (`.mesh.xml` / `.skeleton.xml`), then compiled by the
dev's `mkres` tool into the binary `vbuf2` / `mesh` / `bones` / `skel` / `skan`
layers. The shared `reference/mkres-fragment.py` *is* that Ogre-XML → binary
encoder.

### `vbuf2` binary format (from `haven.VertexBuf.VertexRes` + `mkres`)

```
uint8  fl              ver = fl & 0xf ; only 0 and 1 are valid; top nibble == 0
if ver >= 1: int16 id
uint16 num             vertex count
attributes, repeated until end-of-message:
    string name
    if ver >= 1:        int32 sublen ; sublen bytes  (each attribute length-prefixed)
    else (ver == 0):    inline, sized by the attribute (see below)
```

Each attribute name maps to an element count `eln` and a reader. `mkres` writes
a **bare** layer as plain `float32 × num*eln` (client `loadbuf`), and a
**formatted** layer — renamed with a trailing `2` (`mkres` `chformat(nm, nm+"2",
fmt)`) — as `uint8(1) + string fmt + fmt-data` (client `loadbuf2`).

| name (bare / formatted) | eln | meaning            |
|-------------------------|-----|--------------------|
| `pos`  / `pos2`         | 3   | position           |
| `nrm`  / `nrm2`         | 3   | normal             |
| `tan`  / `tan2`         | 3   | tangent            |
| `bit`  / `bit2`         | 3   | bitangent          |
| `tex`  / `tex2`         | 2   | texture coord      |
| `otex` / `otex2`        | 2   | overlay tex coord  |
| `col`  / `col2`         | 4   | vertex color       |
| `bones` / `bones2`      | var | bone weights/idx   |

On-wire formats and their byte size for `cap = num*eln` elements (header floats
carry the de-quantisation scale): `f4`=4·cap, `f2`=2·cap, `f1`=cap; `snN`/`unN`
= 4 + N-bytes·cap (N∈{4,2,1}); `rnN` = 8 + N-bytes·cap; `sf9995` = 4·(cap/3);
`uvech` = cap/3, `uvec1` = 2·(cap/3), `uvec2` = 4·(cap/3). `mkres` defaults:
`pos→sn2`, `nrm/tan/bit→uvec1`, `tex/otex→un2|sn2`. Bone data (`bones`/`bones2`)
is variable-length: a per-bone list of name + run-length-coded per-vertex weight
spans (see `PoseMorph` / `mkres.writebones`); the weight format is `f4`/`un2`/`un1`.

### What is built (read-only decoders)

`layers/Vbuf2Info.java` fully walks a `vbuf2` — every float attribute *and* the
variable-length bone data (`bones`/`bones2`, a per-bone run-length-coded weight
table) — and `layers/MeshInfo.java` decodes the `mesh` layer's header and its
triangle indices, including the delta-stripped form (a faithful port of
`haven.FastMesh.unstrip`/`decdelta`). Both are surfaced in `info` and the
`verify` histograms, and both consume the payload to the **exact** end on every
real layer, which validates the structural understanding:

- **Vbuf2 histogram: `walked 11`** (all 11 sample `vbuf2`, ver 0 and ≥1).
- **Mesh histogram: `decoded 41`** (all 41 sample `mesh`, raw and stripped).

e.g. `info` on `knarr`: a 12838-vert buffer `[pos2(sn2) nrm2(uvec1) tan2(uvec1)
bit2(uvec1) tex2(sn2) otex2(sn2) bones2(un1)]` with stripped submeshes like
`2940 tris vbuf=0 mat=1 stripped`. This is the "read-only first" milestone; the
layers remain lossless raw `.bin`.

### Completed 3D editing milestones

1. Bone data, `mesh`, `skel`, `skan`, and `manim` are structurally decoded for
   inspection while their original layer bytes remain lossless.
2. A read-only **Wavefront OBJ** export was built
   (`model/Vbuf2Data.java` de-quantises positions/normals/texcoords;
   `model/ObjExport.java` emitted OBJ; CLI `obj`). Validated: `male` → 1325 verts /
   2248 tris (humanoid bbox), `knarr` → 12838 verts / 16952 tris / 21 submeshes
   (ship bbox). It was superseded and removed: the **glTF round-trip** below is
   the editable form — it carries multi-UV/skeleton/animation and rebuilds back
   to `.res`.
3. The write path preserves each original float/norm attribute format rather
   than re-deriving one; unchanged buffers re-encode byte-identically, while
   intentional glTF edits are re-quantised into that recorded format.

### What is built for the write path (`vbuf/Vbuf2Codec.java`)

A **structure-preserving** `vbuf2` codec captures each attribute's exact data
bytes, so an *unchanged* buffer re-encodes **byte-identically** — verified on all
real layers (`verify` → `Vbuf2 re-encode histogram: exact 11`). A single
attribute can then be re-quantised after an edit (into its original format/scale,
i.e. the game's own precision) while every untouched attribute stays exact. This
encoder is the foundation of the glTF rebuild (`GltfImport`, §9), which brings
Blender edits back by re-quantising each attribute into its original on-wire
format and now imports both *topology-preserving* edits (reshape/sculpt) and
*arbitrary new topology* (add/remove/re-topologize vertices and faces). (An early
CLI `transform <file.res> <sx> <sy> <sz>` command exercised the encoder by scaling
a model's vertex positions, but was removed once the glTF round-trip — which also
handles normals/tangents and is in-game validated — superseded it.)

## 9. Current 3D status and open work

### Completed glTF round-trip

The format was decided 2026-06-21 with the game dev loftar — glTF over Ogre XML,
which has no modern Blender importer, and over OBJ, which can't carry Haven's two
UV sets or skeleton bindings. `GltfExport` writes a static, textured binary glTF
(`.glb`) — positions, normals,
  both UV sets (`tex`→`TEXCOORD_0`, `otex`→`TEXCOORD_1`), per-submesh materials with
  embedded textures, Haven Z-up→glTF Y-up — **plus skinning**: `Vbuf2Data` decodes
  the `bones`/`bones2` weights (`PoseMorph` port: top-4, normalised) →
  `JOINTS_0`/`WEIGHTS_0`, and a local `skel` becomes a glTF skin with per-joint bind
  world matrices + inverse-bind matrices (`G = R·W·R⁻¹`, `IBM = G⁻¹`, `G·IBM = I`
  verified). Characters whose skeleton is in another resource get identity-placed
  named joints (mesh + vertex groups still correct). Dependency-free (our `Json` +
  `model/M4` 4×4 maths). `skan` layers also export as glTF **animations** (per-bone
  translation/rotation channels composed onto the bind pose), and `manim` layers as
  glTF **morph targets** + a weight animation (per-frame vertex deltas, linearly
  interpolated). **Phase 1 (export) is complete.** **Bringing edits back is done via a
  topology rebuild** (`GltfImport.rebuild`, CLI `rebuild-gltf`, GUI **Rebuild from
  glTF**): it regenerates
  `vbuf2`+`mesh`(+`bones2`/`bones`/`manim`) from the edited glTF at its own vertex count,
  so you can reshape, re-UV, add, remove or re-topologize vertices and faces. It
  axis-inverts glTF Y-up→Haven Z-up and re-quantises
  positions/normals/both UV sets back into each attribute's *original* on-wire
  format via `Vbuf2Codec.decodeAttr`/`setAttr` (all reader-supported
  `f4/f2/f1`, `sn/un/rn`, `sf9995`, and `uvech/uvec1/uvec2` formats), writes a
  fresh raw-index `mesh`, and copies every other layer
  (materials, textures, code). It needs no per-vertex ids and gives up byte-exactness
  (in-game-validated). **Multi-submesh works**: each glTF primitive becomes a submesh,
  with each part's matid recovered from its material name — the export emits one
  material per matid (`rfmat_<matid>`) so the id survives and Blender keeps parts that
  share a texture separate; primitives are concatenated into the shared `vbuf2`,
  de-duplicated by POSITION accessor. **Skinning weights** rebuild too: `JOINTS_0`/
  `WEIGHTS_0` are mapped to bone *names* via the skin (Blender reorders joints, so the
  name is the stable key) and the top-4 influences re-encoded via `Vbuf2Codec.setBones2`,
  which handles both the modern `bones2` and the legacy `bones` v0 header (render-
  equivalent, since the client reduces to top-4 normalized). **Morph (`manim`) models**
  work: each frame's shape is rebuilt from the glTF morph targets and re-encoded via
  `MeshAnimInfo.encodeWith` (run-length fmt-3 spans) at the new vertex count, keeping the
  original timeline (frame count unchanged; Blender's shape-key *animation* is never
  read). **Normal-mapped models** (`tan`/`bit`) work too — the tangent
  basis is recomputed from the new positions/UVs (Lengyel + Gram-Schmidt; Haven stores
  `bit` identical to `tan`). **The skeleton is re-posed** if a bone moved: each bone's
  new local transform is read from its glTF joint node by name (a plain round-trip drifts
  only ~0.04°, so the change-gate keeps an unedited skeleton byte-identical), and a moved
  bone re-encodes the whole skeleton as version-1 via `SkelInfo.encodeVer1` (mnorm16
  angle + snorm16 octahedral axis + float32 pos; the client reads both versions, so ver-0
  cpfloat needn't be reproduced).

**Standalone `skan` editing is complete.** `gltf-skan` and the GUI combine three
runtime-composed resources into one Blender file: a preview mesh, its bind `skel`,
and the animation resource. Each `skan` layer becomes a named `skan_<id>` action.
`rebuild-skan` reads LINEAR translation/rotation channels by bone name, unions
independent channel times, inverts the bind composition (`frameRot = bindRot^-1 *
animatedRot`, `frameTrans = animatedTrans - bindTrans`), and writes edited tracks in
their original fmt-0/fmt-1 encoding. Unchanged actions retain the original layer
bytes; `{ctl}` event payloads and every non-`skan` layer are copied exactly. Real
validation used `gfx/borka/animaltease` (six clips), `gfx/borka/body` (41-bone
skeleton), and `gfx/borka/male` (preview mesh): export produced a 221,104-byte GLB,
and a no-edit rebuild kept all six layers and the complete resource SHA-256 identical.
Blender may expand edited actions with two-key constant `STEP` channels for every
bone and sampled identity `scale` channels. Import accepts those only when all values
are constant/identity (interpolation is then mathematically irrelevant); nonconstant
STEP translation/rotation and non-identity scale remain hard errors rather than being
silently approximated.
Clip duration is inferred from the edited action's latest sampler key. It changes
only when that range differs from the original track range, which preserves an
untouched clip's intentional trailing duration and byte-identical no-op behavior.
Differences up to 20 ms are treated as Blender frame-grid rounding (observed when
`wave` 1.2667 s re-exported as 1.25 s), not an intentional duration edit.
Both fmt 0 (absolute cpfloat times) and fmt 1 (times normalized by `len`) re-encode
the new duration. A duration change is rejected when the layer has `{ctl}` tracks:
fmt-1 event times are normalized by `len`, while fmt-0 event times are absolute, so
silently retaining the raw payload would impose inconsistent retiming semantics.

**In-app skeletal playback is implemented.** `View 3D` on a standalone animation
uses the same skeleton/model companions as glTF export. `ModelGeometry` retains
top-four bone influences aligned to its triangle soup only for animation previews;
`SkanPlayback` evaluates the client's bind+delta local pose, slerps rotations,
rebuilds parented world matrices, and applies linear-blend skinning to positions and
normals. `Model3DView` receives immutable posed arrays in its render snapshot.
Play/pause, stop, 0.25-4x speed and timeline scrub run through generation-gated
daemon work. The default **All clips** entry composes every compatible `skan` layer
in resource order, matching `Skeleton.CombinedMod`; `wave` has 38 disjoint tracks
across six parts and was visually validated, while individual parts remain selectable.

The Haven encode toolkit is fully in the client (`Utils.hfenc`/`uvec2oct`,
`Message.add*`, `NormNumber` encoders) plus `mkres-fragment.py` for the mesh
quantization/stripping choices — no dev code needed.

### Open or deferred

- `skan` playback mode, bone scale and control/effect events are not edited through
  glTF; effect-free clip duration follows the latest key, while duration changes on
  clips with effect tracks are rejected. `manim` morph shapes rebuild, but frame
  count and timing remain those of the original resource.
- The 3D viewer plays skeletal animation but not `manim` morph animation.
- Direct in-app editors for `vbuf2`/`mesh`/`skel`/`skan`/`manim` are deferred.
  Geometry, skin weights, skeleton poses, skeletal actions, and fixed-timeline morph
  shapes are edited through glTF instead.
- The exact new-style typed (`tto`) `image` header parser has not been validated
  against a real example. None appeared among 669 recorded images, so validate
  opportunistically if a sample is found.

---

## 10. Environment gotcha (generic)

Gradle and Maven require `JAVA_HOME` to point at the **JDK root** (the folder that
*contains* `bin`), **not** the `\bin` sub-directory — a `\bin` JAVA_HOME is
rejected. IntelliJ's own configured "Gradle JVM" sidesteps this; for terminal
builds set `JAVA_HOME` to the JDK root. Any vendor's JDK 21 works. (This bit the
original dev machine early on and has since been corrected — it is generic
guidance, not a project-specific requirement.)
