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
    fl   = uint8                    # bit1 obsolete, bit2 = nooff, bit3 = has-info
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
| `tooltip`,`pagina` | `*.txt`                 | edit UTF-8 text       |
| anything else      | `*.bin`                 | raw bytes (lossless)  |

For `image`, the split point (where the encoded image begins) is found by
parsing the simple header exactly, then validating against the image's magic
bytes (PNG/JPEG/GIF/BMP); a magic-scan fallback covers the newer typed header.
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

Re-encoding mirrors `Message.addtto`'s canonical rules (smallest integer type,
`float64` for reals, `T_END`-terminated nested lists/maps, no terminator on the
top-level list). Consequently a props layer is offered as JSON only when that is
guaranteed reversible, and editing it can never corrupt a resource. Only
JSON-native `tto` types are handled (string, integer, float64, nested list/map,
nil); coords, colors, byte blobs, `float32`, norm numbers, resource specs, etc.
keep the layer raw. (Real example — `knarr.res`: `{ "place": ["surface", "map"] }`.)

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
  gradlew, gradlew.bat, gradle/      # wrapper (Gradle 8.10.2)
  src/main/java/resforge/
    Main.java                        # CLI: gui|fetch|info|unpack|pack|replace|obj|transform|catalog|verify
    gui/ResForgeFrame.java           # Swing editor window (layer table + preview/edit)
    gui/GuiSupport.java              # per-layer preview/text/export helpers (reuses decoders)
    gui/ImageView.java               # scaled/centred image preview component
    gui/AudioPlayerPanel.java        # in-app Ogg player (play/stop/seek)
    audio/OggVorbis.java             # Ogg Vorbis -> PCM decode (via bundled JOrbis)
    net/ResourceFetcher.java         # download &lt;base&gt;/&lt;path&gt;.res from the game server
    io/MessageReader.java            # LE primitive decoder (mirrors haven.Message)
    io/MessageWriter.java            # LE primitive encoder
    io/Json.java                     # tiny dependency-free JSON reader/writer
    res/ResContainer.java            # parse/serialize container + Layer list
    res/Layer.java                   # (name, byte[] payload)
    res/Manifest.java                # read/write manifest.txt (+ per-layer codec)
    res/Unpacker.java                # .res -> folder (parts model)
    res/Packer.java                  # folder -> .res (raw | tex | props | action codecs)
    res/Replacer.java                # one-shot single-asset swap
    res/Verifier.java                # batch round-trip + image/tex split validation
    res/Catalog.java                 # folder-wide editable-asset listing
    layers/ImageInfo.java            # image header parse + PNG split point
    layers/TexInfo.java              # tex header parse + embedded-image split point
    layers/AudioInfo.java            # audio2 header parse + Ogg split point
    layers/FontInfo.java             # font header parse + TTF/OTF split point
    layers/ImageMagic.java           # encoded-image magic-byte detection
    layers/PropsCodec.java           # props <-> JSON (tto codec, lossless-or-raw)
    layers/ActionCodec.java          # action <-> JSON (deterministic record)
    layers/Vbuf2Info.java            # vbuf2 read-only attribute inspector (incl. bones)
    layers/MeshInfo.java             # mesh index decoder (incl. delta-strip)
    layers/TtoSkip.java              # generic tto value skipper
    model/Vbuf2Data.java             # vbuf2 -> de-quantised vertex arrays (export)
    model/Vbuf2Codec.java            # structure-preserving vbuf2 decode/encode (+edit)
    model/ObjExport.java             # 3D geometry -> Wavefront OBJ (+ .mtl + texture)
  src/test/java/resforge/
    RoundTripTest.java               # byte-identical round-trip + image/tex-edit tests
    PropsJsonTest.java               # JSON + props codec tests
    ReplaceTest.java                 # one-shot replace tests
    VbufInfoTest.java                # vbuf2 inspector tests
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

# One-shot single-asset swap (image/tex/audio2/font/midi/tooltip/pagina/props/action)
./gradlew run --args="replace horse.res image newicon.png horse.res"
./gradlew run --args="replace theme.res audio2 newsound.ogg theme.res"

# Export 3D geometry to OBJ (view in Blender / 3D Viewer)
./gradlew run --args="obj path/to/horse.res"

# Catalogue what is editable across a folder of resources
./gradlew run --args="catalog path/to/folder"

# Validate real files (single file or a folder, recursive)
./gradlew run --args="verify path/to/horse.res"
./gradlew run --args="verify path/to/folder"

# Or use the jar
./gradlew jar
java -jar build-gradle/libs/resforge-0.1.0.jar info horse.res
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
- **Minimal third-party deps**: the only runtime dependency is **JOrbis**
  (`org.jcraft:jorbis`, ~97 KB, LGPL) — a pure-Java Ogg Vorbis decoder used by the
  GUI's in-app sound player; it is vendored in `lib/` and folded into the runnable
  jar (a "fat jar"), so the tool still builds and runs offline with no network
  fetch. JUnit is test-only. The CLI uses none of this (no audio decoding), and
  the manifest/JSON formats need no library.

---

## 7. Verification performed

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
    path is still only magic-scan-validated.
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
  effectively unused, and the magic-scan fallback covers it regardless.
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
  bounding boxes are geometrically plausible.
- **Structure-preserving vbuf2 encoder + `transform` (2026-06-20)**: the
  automated oracle — decode→encode of every real `vbuf2` is **byte-identical**
  (`verify` → `Vbuf2 re-encode histogram: exact 11`). The `transform` command
  scales a model's positions (re-quantising only `pos`); on `male.res` a z×2
  stretch produced a file that `verify`s clean with the z bounding box exactly
  doubled (`[-0.06,12.79]` → `[-0.13,25.58]`) and all other attributes preserved.
  In-game rendering of the result is the user-side verification step.

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

### Remaining for full 3D editing (future)

1. ~~Decode the bone data and the `mesh` index layer~~ (done — see above);
   `skel`/`skan` (skeleton + animations) are still raw.
2. ~~Emit an editable form~~ — a read-only **Wavefront OBJ** export is built
   (`model/Vbuf2Data.java` de-quantises positions/normals/texcoords;
   `model/ObjExport.java` emits OBJ; CLI `obj`). Validated: `male` → 1325 verts /
   2248 tris (humanoid bbox), `knarr` → 12838 verts / 16952 tris / 21 submeshes
   (ship bbox). For a Blender *round-trip* (editing back to `.res`), emit **Ogre
   XML** instead and port `mkres-fragment.py` to Java for the XML → binary path.
3. Gate any write path behind the usual lossless-or-raw guard. Note the float/
   norm formats are lossy, so a faithful round-trip must preserve the exact
   per-attribute format the original used (record it, don't re-derive).

### What is built for the write path (`model/Vbuf2Codec.java`, `transform`)

A **structure-preserving** `vbuf2` codec captures each attribute's exact data
bytes, so an *unchanged* buffer re-encodes **byte-identically** — verified on all
real layers (`verify` → `Vbuf2 re-encode histogram: exact 11`). A single
attribute can then be re-quantised after an edit (into its original format/scale,
i.e. the game's own precision) while every untouched attribute stays exact. The
`transform <file.res> <sx> <sy> <sz>` command exercises this end to end by
scaling a model's vertex positions. Self-checks pass at the byte level (output
verifies, geometry scales correctly — e.g. `male.res` z×2 doubles the z bounding
box, other attributes unchanged), but **a write path's final proof is loading the
result in-game** — which is the one check that can't be automated here. (A
non-uniform scale leaves normals untransformed; uniform scale is fully correct.)
The remaining step to a Blender round-trip is importing *arbitrary* new topology
(re-stripping the index buffer + a fresh `mkres`-style encode), which genuinely
needs the in-game feedback loop.

## 9. Possible next steps

- Eventually edit `vbuf2`/`mesh`/`manim` (port `mkres`; see §8). (`mat2`, `anim`,
  `neg` and `obst` are done — editable JSON via
  `Mat2Codec`/`AnimCodec`/`NegCodec`/`ObstCodec`; `code`/`codeentry` and the
  dependency layers `deps`/`rlink`/`src` are decoded read-only via
  `CodeInfo`/`CodeEntryInfo`/`DepsInfo`/`RLinkInfo`/`SrcInfo`.)
- Broaden the `props` codec to more `tto` types (coord/color/bytes/float32, the
  last now possible via the new `float16`/`MessageWriter.float16` codec) using an
  explicit tagged JSON form (like `Mat2Codec`), to expose props that stay raw.
- Validate the new-style typed (`tto`) `image` header against a real sample that
  uses it (none of the current samples do).
- Optional: expose the `tex` alpha **mask** (part `t==4`) as a second editable
  image; today it is preserved verbatim inside `*.post.bin`.
- A small GUI or a `--watch` mode for rapid skin iteration.

---

## 10. Environment gotcha (generic)

Gradle and Maven require `JAVA_HOME` to point at the **JDK root** (the folder that
*contains* `bin`), **not** the `\bin` sub-directory — a `\bin` JAVA_HOME is
rejected. IntelliJ's own configured "Gradle JVM" sidesteps this; for terminal
builds set `JAVA_HOME` to the JDK root. Any vendor's JDK 21 works. (This bit the
original dev machine early on and has since been corrected — it is generic
guidance, not a project-specific requirement.)
