# Format notes (atomic, searchable)

Granular `.res` format facts, one heading per topic so the RAG can retrieve them
individually. The deep narrative lives in `docs/DESIGN-notes.md`; this file is
for quick lookups and grows as we learn more.

## Container
`"Haven Resource 1"` (16 ASCII bytes) + `uint16` little-endian version, then
repeated layers until EOF: a NUL-terminated UTF-8 name, an `int32` LE payload
length, then that many payload bytes. Unknown layer names are skipped by the
game client, so a tool can pass them through untouched.

## image layer
`uint8 ver`. If ver < 128: `int8`*256+ver = z, `int16` subz, `uint8` flags
(bit2 nooff, bit3 has-info), `int16` id, offset coord, optional info entries,
then the encoded image (a normal PNG/JPEG) to the end. If ver-128 == 1: a typed
(tto) header. We swap the embedded image and keep the header verbatim. The
`off` coord is the frame's draw offset — the GUI's `AnimView` uses each frame's
size + offset to composite an `anim` preview at one shared scale, so
differently-sized frames keep their true relative size and position (e.g.
cleave's 8 frames range 44x27→23x25 with offsets sweeping (14,27)→(35,16)).
`ImageHeaderCodec` re-encodes the old-style header so the GUI can edit id / z /
sub-z / offset / nooff (lossless-or-raw: image bytes kept verbatim, offered only
when a straight re-encode reproduces the original). Note the first header byte is
both the `ver` gate and `z`'s low byte, so editing z keeps that byte < 128. The
codec's `build(...)` wraps a raw PNG/JPEG into a fresh image layer (used when
adding a frame — the GUI assigns the next free id), enabling "expand an
animation" by adding image layers + extending the `anim` frame list.

## tex layer (3D model texture)
From `haven.TexR.Encoded`: `int16 id`, `uint16` off x/y, `uint16` sz x/y, then
parts; the color image part is `int32 len` + encoded image (JPEG/PNG). Because
the length is stored *inside* the payload, our `tex` codec recomputes that int32
when the texture is swapped, so a replacement of any size repacks correctly.

## audio2 layer
`uint8 ver` (1..3), `string id`, `uint16 vol` (ver 2, bvol = vol*0.001), optional
tto metadata (ver 3), then an Ogg Vorbis stream to the end. We split into header
+ `.ogg`; the GUI decodes it with JOrbis and plays it. `AudioHeaderCodec`
re-encodes the ver 1–2 header so the GUI can edit the clip **id** and (ver 2)
**volume** (lossless-or-raw: Ogg kept verbatim, offered only when re-encode is
byte-exact). Ver 3 (typed metadata) stays read-only.

## font layer
`uint8 ver` (==1), `uint8 type` (==0 TrueType), then the sfnt font program to the
end (`00 01 00 00` / `OTTO` / `true` / `ttcf`). Split into header + `.ttf`/`.otf`.

## props / action layers
`props`: `uint8 ver` (==1) + a tto list of alternating key/value. `action`
(AButton): `string parent; uint16 parentVer; string name; string prereq;
uint16 hotkey; uint16 adCount; string[] ad`. Both exposed as JSON only when
decode->encode is byte-exact (lossless-or-raw).

## vbuf2 layer (vertex buffer)
`uint8 fl` (ver = fl & 0xf), `int16 id` (ver>=1), `uint16 num`, then per attribute
a name and either an `int32`-length blob (ver>=1) or inline data (ver 0). Bare
names = `float32` * eln; names ending in `2` = `uint8(1)` + format + data, where
format is f4/f2/f1, snN/unN/rnN (N in 4/2/1, with a float32 scale), sf9995, or
uvecN (octahedral normals). eln: pos/nrm/tan/bit=3, tex/otex=2, col=4. Bone data
(`bones`/`bones2`) is a per-bone run-length-coded weight table. mkres default
formats: pos->sn2, nrm/tan/bit->uvec1, tex/otex->un2 or sn2.

## mesh layer (triangle indices)
From `haven.FastMesh`: `uint8 fl`; old form (fl & 0x80 == 0): `uint16` numTris,
`int16` matid, optional id/ref/rdat/vbufid, then indices — raw `uint16`*3*num or
**delta-stripped** (`unstrip`/`decdelta`). References a vbuf2 by id.

## obst layer (movement collision)
From `haven.Resource.Obstacle`: `uint8 ver` (1 or 2); if ver 2 a `string id`;
then `uint8 n` (polygon count), `n` per-polygon point counts (all counts first),
then per polygon per point a `(float16 x, float16 y)` coordinate (tile units; the
client scales by tile size). This is the shape that physically blocks walking.
**Editable as JSON** `{version, id?, polygons:[[[x,y],…],…]}` (`ObstCodec`, codec
`obst`) under the lossless-or-raw guard. Coordinates are float16, so editing
stores at the game's own half-precision; `MessageReader.float16`/`float16` was
added (round-to-nearest, exhaustively round-trip-tested over all non-NaN halves).
Samples (knarr, mulberry) are ver-1, one 4-point polygon (a footprint rectangle).

## neg layer (click hitbox / interaction geometry)
From `haven.Resource.Neg`: `coord cc` (the click hotspot center), then 12 bytes
the client skips (CarryGun reads them as 3 coords — tl/br/oc: top-left,
bottom-right, object-center), then `uint8` endpoint-group count and per group
`[uint8 id, uint16 n, n coords]` (connection/attachment points). `cdec` is
`(int16 x, int16 y)`, so **every field is int16/uint8 — exactly reversible, NOT
lossy** (unlike `obst`'s float16). **Editable as JSON**
`{center, bounds, endpoints}` (`NegCodec`, codec `neg`, lossless-or-raw). Samples
(cleave/flex/jump) all have 0 endpoint groups; the endpoint path is covered by a
synthetic unit test (a placeable/linkable object would exercise it in the wild).

## mat2 layer (material)
`uint16 id`, then until EOF a sequence of material commands — each a
NUL-terminated string key followed by a `tto` value list terminated by `T_END`
(0x00). Confirmed on all 38 sample layers (each parses to the exact end); the
value types seen are `str`, `u8`, `color` (RGBA) and `f32`; keys seen: `bump,
cel, col, light, maskcol, masknorm, mlink, nofacecull, order, otex, tex,
texrot`. **Now editable as JSON** (`Mat2Codec`, codec `mat2`) using an explicit
tagged-value form (a string stays a plain JSON string; everything else is a
single-tag object like `{"color":[204,204,204,255]}`, `{"f32":0.5}`, `{"u8":0}`)
under the lossless-or-raw guard. Matches CarryGun's "id + map of key→tto-value-list".

## material → texture mapping (for OBJ export)
A `mesh` references a material by `matid`; the `mat2` with that `id` describes the
look. A material points at its texture via a `tex`/`otex` command: a **local**
texture is `[{u8:k}]` (k indexes this resource's own `tex` layers), while an
**external** texture is `[<string respath>, …]` (e.g. `mlink` →
`gfx/terobjs/trees/peartree-tex` — lives in *another* `.res`). `ObjExport` writes
`.mtl` + the local `tex` images only: single-tex models map cleanly to everything;
multi-tex use the `matid→mat2→local tex` chain best-effort; external (mlink)
textures aren't fetched. (knarr: 6 local tex but most parts use external mlinks.)

## anim layer (sprite animation)
From `haven.Resource.Anim`: `int16 id`, `uint16 delay` (frame duration in ms),
`uint16 n`, then `int16[n]` frame image-ids. Each frame references `image`
layers in the same resource by id, so the pixels live in (separately editable)
image layers. Fully deterministic, so **editable as JSON**
`{"id":…,"delay":…,"frames":[…]}` (`AnimCodec`, codec `anim`, lossless-or-raw).
Real samples: `prog` (25 frames @120ms), `cleave`/`jump` (8 @100ms),
`flex` (8 @75ms) — all `id=-1`, frame ids contiguous from 128.

## rig / lighting layers (light, skel, skan, boneoff)
Read-only viewers ported from the client's `Light.java` and `Skeleton.java`
(LGPL-3, in `docs/reference/`). These stay raw/lossless; we only surface structure.
They use number encodings beyond the basic primitives, now in `MessageReader`:
- **`cpfloat`** — custom-packed float: `int8` exponent + LE `uint32` (top bit sign,
  low 31 bits mantissa); value = `2^e · (1 + m/2^31)`, with `e=-128,m=0` meaning 0.
  Mirrors `haven.Utils.floatd`. (Validated bit-exact on real `light`/`skel` samples.)
- **`mnorm16`** = `uint16/2^16` (modular [0,1)), **snorm16** = `int16/0x7fff`
  (signed [-1,1]), **unorm16** = `uint16/0xffff` ([0,1]).
- **`oct2uvec`** — decodes an octahedral-encoded unit vector from two snorm16s.

Formats:
- **`light`** (`LightInfo`): `u8 ver`. ver0: `int8 id`, 3 colours (each 4×`cpfloat`
  ×255 = RGBA), then tagged opts to EOM — `t1` attenuation (3 cpfloat), `t2`
  direction (3 cpfloat), `t3` spot exponent (cpfloat). ver1: `int16 id`, colours as
  4×`float32`, same tags with float32. Att ⇒ point/spot light; else directional.
- **`skel`** (`SkelInfo`): the bone tree. Decoding starts in "ver 0" and a 1-char
  string whose code is <32 switches sub-version. ver0 bone: `string name`,
  3 `cpfloat` pos, 3 `cpfloat` axis (normalized), `cpfloat` angle, `string parent`.
  ver1 bone: `string name`, `string parent`, 3 `float32` pos, `mnorm16`×2π angle,
  `snorm16`,`snorm16`→`oct2uvec` axis. Parent "" = root.
- **`skan`** (`SkanInfo`): skeletal animation. `int16 id`, `u8 fl` (fmt=(fl&6)>>1),
  `u8 mode` (0..3 once/loop/pong/pongloop), `len` (fmt0 cpfloat else float32), if
  `fl&1` an nspeed; then to EOM `string bnm` — `"{ctl}"` = an fx-event track, else a
  bone keyframe track. Track: `u16 count` frames (fmt0 = cpfloat time+trans+ang+axis;
  fmt1 = unorm16 time, 3×half-float trans, mnorm16 ang, 2×snorm16 axis). FX events:
  `u16 count`, each time + `u8 t` (`t&0x80` ⇒ sub-message of `u16` length); t 0/2
  spawn-sprite (resname+ver+sdt[+eqp]), 1 trigger, 3 mkoverlay, 4 rmoverlay.
- **`boneoff`** (`BoneOffInfo`): equip-point opcode program — `string name` then
  opcodes to EOM: 0/16 translate (cpfloat/float32), 1/17 rotate (cpfloat / mnorm16
  ang + oct axis), 2 equip-point at named bone, 3/19 bone-align, 4 null-rotation,
  5 scale (float32).
Confirmed decoding every sample to EOM: light 2, skel 3, skan 5, boneoff 28. e.g.
knarr's skel = 11 bones (Main root + sails/oar), skan = 8 s loop, 11 tracks.

## dependency / reference layers (deps, rlink, src)
These three are **read-only reference views** — they show which other resources a
`.res` references; the layers stay raw/lossless.

- **`deps`** (`DepsInfo`): `uint8 ver`, then `[string name, uint16 ver]` records to
  EOM — the explicit list of resources this one needs at load time (the `ver` is
  the minimum dependency version). Names repeat freely (one per actual use site).
  Confirmed on knarr (28 records) and villageidol (17).
- **`src`** (`SrcInfo`): `uint8 ver`, `string fileName` (e.g. `Tree.java`), then the
  file's bytes to EOM. One source file per layer; resources that ship `code`
  bytecode also carry the pre-processed Java source here. Exports as `.java`.
  Confirmed on globfx/tree/vmat/reeling (16 layers).
- **`rlink`** (`RLinkInfo`): `uint8 ver`, then entries to EOM, each `uint16 id`,
  `uint8 type`; for `type==3`: `string res`, `uint16 ver` (the linked resource),
  then a `tto` value list (the link's spec/args) read until a `0` tag or EOM. The
  spec frequently nests `res(name@ver)` references (tto tag 34), which are
  collected too. A redirect/parameterize mechanism: "serve <res> with this spec."
  Parser is tolerant (unknown entry type stops cleanly, keeps prior links).
  Confirmed on mulberry (1 link, EOM-terminated spec) and villageidol (4 links,
  map specs with nested `res()` to candle-flare64/flight). The reusable `tto`
  reader mirrors the one in `CodeEntryInfo`.

### aggregated references (`res/References`, CLI `refs`, GUI References… dialog)
One deduped report of every *external* resource a `.res` references, gathered from
the layers that name others: `deps` (name@ver), `rlink` (`references()` = link
target + nested `res()`), `codeentry` (classpath deps), and `mat2` material links.
The `mat2` rule: collect string command-values that contain `/` (real resource
paths). Observed string-bearing mat2 keys: `mlink` (always a resource path — the
material include), external `tex`/`otex` (path), vs. mode/order names that have no
slash and are ignored (`light="def"`, `order="eye"/"premap"/…`, local `tex="a"/"c"`).
`anim` frames reference sibling `image` layers by local id, not other resources, so
they add nothing. e.g. knarr → 9 distinct, villageidol → 10 (deps overlaps rlink +
mat2; `codeentry` adds `spr`).

## code / codeentry layers
`code` (from `haven.Resource.Code`): `string name` (a fully-qualified Java class
name) + the rest of the payload = a compiled `.class` file (magic `CAFEBABE`).
The game ships server-authored JVM bytecode and loads it via a custom class
loader. `codeentry` (`haven.Resource.CodeEntry`): tagged sections until EOM —
`t==1` pairs `[entryName, className]` (terminated by an empty pair), `t==2`
classpath `[resName, uint16 ver]` (terminated by an empty name), `t==3` like
`t==1` but each pair is followed by a `tto` argument list, `t==4` a `tto` list of
`["ent",…]`/`["use",…]` records. Both are **read-only** here (`CodeInfo`,
`CodeEntryInfo`): we surface the class name (+ `.class` export) and the
entrypoint→class + classpath manifest, but neither runs nor edits the bytecode;
the layers stay raw/lossless. Confirmed on all sample layers (18 code, 7
codeentry; tags 1/2/3 seen, each parses to the exact end).

## Server fetch
The game client (and our `fetch`) downloads `<base>/<path>.res`. Base default is
`http://game.havenandhearth.com/res/` (the official server). Example path:
`gfx/borka/male` -> `http://game.havenandhearth.com/res/gfx/borka/male.res`.
