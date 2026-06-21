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

## material → texture mapping
A `mesh` references a material by `matid`; the `mat2` with that `id` describes the
look. A material points at its texture via a `tex`/`otex` command: a **local**
texture is `[{u8:k}]` (k indexes this resource's own `tex` layers), while an
**external** texture is `[<string respath>, …]` (e.g. `mlink` →
`gfx/terobjs/trees/peartree-tex` — lives in *another* `.res`). The glTF export uses
the local `tex` images only: single-tex models map cleanly to everything;
multi-tex use the `matid→mat2→local tex` chain best-effort; external (mlink)
textures aren't fetched. (knarr: 6 local tex but most parts use external mlinks.)

## glTF (.glb) model export
Modern alternative to OBJ for the 3D model, and the basis for the eventual edit
round-trip. Format chosen 2026-06-21 on the game dev's (loftar) recommendation:
Ogre XML has no modern Blender importer; OBJ can't carry Haven's *two* UV sets
(`tex`+`otex`) nor skeleton bindings — glTF handles both and has native Blender
import/export. `GltfExport` writes a self-contained binary glTF (`.glb` = 12-byte
header + JSON chunk + BIN chunk), dependency-free (our `Json` + a hand-built
little-endian binary buffer). Phase 1a (done) = static textured geometry:
per `vbuf2` a POSITION/NORMAL/TEXCOORD_0(`tex`)/TEXCOORD_1(`otex`) accessor (all
float32); per `mesh` a primitive (its `vbuf`'s attributes + a USHORT index
accessor + a material); local `tex` layers → embedded `image`s + PBR materials
(baseColorTexture, alphaMode MASK, doubleSided) — **one material per distinct submesh
matid, named `rfmat_<matid>`** (so the rebuild importer can recover each part's id and
Blender keeps texture-sharing parts separate), reusing the `matid→mat2→
local tex` mapping. Coordinates convert Haven Z-up → glTF Y-up via
`(x,y,z)→(x,z,-y)`. bufferViews are 4-byte aligned; the BIN chunk holds geometry
then image bytes. Validated structurally (chunk types/alignment, every
accessor/bufferView within the buffer) on male/mulberry/knarr/etc.; knarr exports
both TEXCOORD_0 and TEXCOORD_1. Skeleton/skinning, `skan` animations and `manim`
morphs are covered in the Phase-1b subsection below; the **import** half is the
Phase-2a subsection further down.
The Haven encode toolkit is fully in the client: `Utils.hfenc`/`uvec2oct`,
`Message.add*`, `NormNumber` snorm/mnorm encoders — no dev code needed.

### glTF skinning (Phase 1b)
`Vbuf2Data` now also decodes the `bones`/`bones2` sub-buffers (it used to skip
them), faithfully porting `haven.PoseMorph.read`: collect each vertex's
influences, sort by weight descending, keep the **top 4**, normalise to sum 1 →
exactly glTF's `JOINTS_0` (VEC4) + `WEIGHTS_0` (VEC4). `GltfExport` emits a `skin`:
when a local `skel` is present its bones become a **connected node hierarchy**
(each bone a node with its native local translation+rotation, parented per the
skeleton) under a conversion **ROOT** node (rotation `R` = Haven-Z-up→Y-up), so a
bone's glTF global is `G = R·nativeWorld` and `IBM = G⁻¹` (bind `G·IBM = I`,
verified ≤1.5e-5 via the real node hierarchy on knarr/stallion/lilypadlotus). The
connected hierarchy is what makes the armature display cleanly **and** lets `skan`
animations target it. **Characters with an external skeleton** (male/female/bull —
no local `skel`) fall back to identity-placed named joints (mesh + vertex groups
still correct, armature un-posed). 4×4 maths are in `model/M4` (column-major).

`skan` layers become glTF **animations** (`buildAnimations`): each skan = one
animation, each per-bone track = a translation channel + a rotation channel
targeting that bone's joint node. Values are composed onto the bind pose exactly
as the client does — `translation = bindLocalPos + frameTrans`,
`rotation = bindLocalRot · frameRot` (normalised) — and frame times become the
sampler input (with the required min/max). `SkanInfo` now captures per-frame
time/translation/rotation (fmt0 cpfloat, fmt1 quantised). Only bones in the local
skel animate (so knarr's sails + lilypadlotus animate; external-skel characters
do not). `manim` (mesh/morph) layers also export as glTF **morph targets**: each
frame's per-vertex position deltas become a morph target (deltas are *added* to the
base and *linearly* interpolated — exactly glTF morph semantics, matching the
client's `add(in, poff)` + `mix`), and a `weights` animation drives them (e_i per
frame over the frame times, looping back to frame 0 at `len`). `MeshAnimInfo` now
captures per-frame idx/deltas (fmt 1/3/4). So knarr exports both skeletal *and*
morph animation; wisp's flicker and algaeblob's sway export as morphs.

### glTF geometry import (Phase 2a)
`GltfImport` re-imports an edited `.glb` back into a model (CLI `import-gltf`, GUI
**Import glTF**). It is a **patch, not a rebuild**: only the `vbuf2` vertex data is
re-encoded; every other layer (`mesh` triangles, `skel`, `bones2` weights, `mat2`,
`tex`, code…) is carried over byte-for-byte. For each glTF vertex it reads
POSITION/NORMAL/TEXCOORD_0/TEXCOORD_1 (FLOAT accessors out of the BIN chunk),
inverts the axis convert (glTF Y-up→Haven Z-up, `(gx,gy,gz)→(gx,-gz,gy)` for
positions *and* normals), and re-quantises each attribute into its **original**
on-wire format via `Vbuf2Codec.decodeAttr`/`setAttr` — a general de/re-quantiser
covering f4/f2/sn1-4/un1-4/uvec1-2 (`writeAny`: signed/unsigned NormNumber with a
leading float32 max, and octahedral `uvec2oct` for normals).

**Vertex matching by stable id.** Blender (and most DCC tools) re-split vertices at
UV/normal seams on export — the [glTF manual](https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html)
says "Discontinuous UVs and flat-shaded edges may result in moderately higher
vertex counts", so the count almost never matches (e.g. male 1325→1326; male has
only 1126 *distinct* positions, 199 seam dups). To survive that, `GltfExport` now
tags every vertex with a custom scalar attribute **`_VID`** = its `vbuf2` index, and
`GltfImport` scatters each glTF vertex back to slot `_VID` (so reordered /
duplicated / re-split vertices all land correctly, count-independent). Vertices
whose id Blender *merged* away (a seam dup folded into its twin) are filled from a
coincident matched vertex (same original position → moves together); their
normal/UV stay original. Guards: ids beyond `num` ⇒ "different .res" error; zero
matches ⇒ error. `_VID` requires **"Data > Mesh > Attributes" enabled in Blender's
glTF exporter** (`export_attributes`, default *off*); `export_normals`/`texcoords`/
`skins` are default on. A glTF *without* `_VID` falls back to strict count+order
matching (ResForge's own unedited export, or order-preserving tools).

Because sn/un/uvec round-trip byte-exactly when a component sits at full scale, an
**unchanged** model survives res→glb→res **byte-identically** (verified on
male/knarr/mulberry/bull/stallion — 100% matched, `vbuf2` + all other layers
identical; and the worst-case "Blender merged all 199 seam dups" sim on male
reconstructs every position exactly via the coincident fallback). Scope is
topology-preserving edits (reshape/transform/sculpt; no genuinely new geometry).
The full round-trip is **user-confirmed end-to-end**: exporting male, enlarging the
head in Blender, re-exporting (Attributes on), re-importing and loading in-game
renders correctly.

### glTF skinning-weight import (Phase 2b)
`GltfImport` also re-imports edited **bone weights** (weight painting). It scatters
the glTF's `JOINTS_0`/`WEIGHTS_0` back to each vertex by `_VID`, mapping each glTF
joint index to a **bone name** via the mesh's `skin.joints` → node name — essential
because Blender reorders joints, so the raw index isn't stable; the name is. The
top-4 per-vertex influences are re-encoded into `bones2` via `Vbuf2Codec.setBones2`,
which emits the run-length-per-bone layout `PoseMorph.read` parses (`uint8 ver`,
weight-format string, `uint8 mba`, then per used bone a name + `(run, startVertex,
weights…)` spans, empty name to end), in the original weight format (f4/un2/un1).
Bones are emitted in name order, skipping unused ones; the client maps influences
back to skeleton bones by name, so stream order is free. This is render-equivalent
because the client itself reduces to the top-4 normalized influences
(`sortweights`+`normweights`). Crucially it is **change-gated**: weights are only
re-encoded if they actually differ (per-vertex influence sets, ±0.01) from what the
original would export, so a pure mesh edit leaves `bones2` **byte-identical** (a
skinned no-op round-trips byte-for-byte on male/knarr/bull/stallion). Un-matched
seam-dup vertices inherit a coincident vertex's weights, like positions. Validated:
forcing every male vertex to a single bone re-encodes correctly and all 1325 bind to
their originally dominant bone. New bones can't be added (skeleton is fixed).

### glTF morph-shape import (Phase 2c, part 1)
`GltfImport` re-imports edited **morph (`manim`) shapes** — the per-frame vertex
deltas behind effects like a rippling sail or a flickering wisp, which Blender holds
as **shape keys**. Each glTF morph target is one frame's deltas; on import they are
scattered back to original vertices by `_VID`, axis-inverted (a delta is a vector:
`(gx,gy,gz)→(gx,-gz,gy)`), and re-encoded into the matching `manim` layer via
`MeshAnimInfo.encodeWith` (run-length spans of fmt-3 float16, the only sampled
format). Blender writes shape keys as **sparse accessors** (only the changed
elements, an all-zero base with no `bufferView` + an indices/values pair), so the
accessor reader handles sparse (and integer/normalized) accessors, not just dense
float ones. Targets map to frames by position: the exporter concatenates every
manim's
frames in layer order, and Blender preserves shape-key order, so global target *t* →
(manim, frame) by cumulative counts. **The timeline is kept from the original** —
frame times, counts, len, play-order — only the shapes change; this deliberately
sidesteps the brittle round-trip of Blender's shape-key *animation* (we never read
the glTF weight-animation). Change-gated per manim: an unchanged manim stays
byte-identical (a no-op round-trips byte-for-byte on wisp/knarr/algaeblob/woodheart,
which have **2 manims each** of 8 fmt-3 frames). Un-matched seam-dup vertices inherit
a coincident vertex's delta. If the shape-key count changed (frames added/removed) it
keeps the original. Validated: doubling one manim's frame-0 deltas re-encodes exactly
while the model's other manim stays byte-identical.

### glTF skeleton import (Phase 2c, part 2)
`GltfImport` re-imports an edited **skeleton rest pose** (`skel`). Each bone's new
local transform is read from its glTF joint node *by name* (Blender keeps bone names
and node-local translation/rotation; the skin maps joints→nodes). Empirically a plain
Blender round-trip preserves bone transforms to ~2e-5 units / ~0.04° (verified by
diffing knarr before/after Blender), so a generous change-gate (Δpos>1e-3 or Δrot>0.5°)
cleanly distinguishes a real edit from float noise; an unchanged skeleton is left
**byte-identical** (validated against the user's actual Blender round-trip of knarr).
When a bone moved, the whole skeleton is re-encoded via `SkelInfo.encodeVer1` — always
the **version-1** form (the client reads both, so we needn't reproduce ver-0
`cpfloat`): a `"\u0001"` marker, then per bone name, parent, `float32` position, the
angle as `mnorm16` (`angle/2π` mod 1) and the rotation axis octahedral-encoded into two
`snorm16`s. Rotations convert quaternion↔axis-angle. No axis swap is needed: our export
gives each bone its *native* local transform under a conversion ROOT node, so the node
transform already equals the skel transform. (knarr is ver-1; lilypadlotus/stallion are
ver-0 — all re-encode to ver-1 on edit.)

### glTF topology rebuild (add/remove geometry)
`GltfImport.rebuild` is the *other* import mode: instead of patching the original
structure (which can't change vertex/triangle counts), it **regenerates** the model's
geometry from the glTF, so you can add/remove/re-topologize vertices and faces in
Blender. It rebuilds `vbuf2` at the glTF's vertex count — positions/normals/UVs
re-quantised into the original attribute formats (via `Vbuf2Codec.setAttr` after
setting `num`), and `bones2`/legacy `bones` from `JOINTS_0`/`WEIGHTS_0` (joints mapped
to bone names via the skin, then `Vbuf2Codec.setBones2`, which re-encodes either the
modern `bones2` header or the legacy `bones` header with `f4` weights) — and writes a
fresh raw-index `mesh`
(`fl=16` form: num, matid, [id], vbufid, then `num*3` uint16 indices) reusing the
original mesh's matid/vbufid. All other layers (textures, materials, code) are kept,
**and the skeleton is re-posed** if a bone moved in Blender (same `applySkel` /
change-gate as the lossless import — so plain reshaping leaves `skel` byte-identical,
while a moved bone is re-encoded as `skel` ver1). No `_VID` is needed (vertex order =
glTF order) and it gives up
byte-exactness, so it leans on in-game testing — exposed separately as CLI
`rebuild-gltf` and a GUI "Rebuild from glTF" (with a not-lossless warning) so it's an
explicit choice, leaving the safe lossless `import-gltf` as the default.

**Multi-submesh** is supported: each glTF primitive becomes a submesh. To recover
which part each face belongs to (and to stop Blender merging parts that merely share
a texture), `GltfExport` now emits **one material per distinct matid**, named
`rfmat_<matid>`; on rebuild each primitive's matid is parsed back from its material
name (tolerating Blender's `.001` suffixes). Primitives are concatenated into the one
shared `vbuf2`, **de-duplicated by POSITION accessor** so our own (shared-buffer)
export isn't copied per primitive while Blender's per-material split blocks are
concatenated with their indices offset. The original mesh layers are all replaced by
the rebuilt submeshes at the first mesh position, every other layer kept. It handles
positions/normals/UVs/bone-weights **and morph (`manim`) models**: each frame's
shape is rebuilt from the glTF morph targets (concatenated per part like the
geometry, axis-inverted) and re-encoded via `MeshAnimInfo.encodeWith` at the new
vertex count, keeping the original frame times; the shape-key count must equal the
frame count (adding/removing morph frames isn't supported yet). **Normal-mapped
models** (`tan`/`bit` attributes, e.g. knarr/woodheart) work too: glTF doesn't carry
the tangent basis, so it's **recomputed** from the new positions/UVs/triangles
(Lengyel's method + Gram-Schmidt against the normal; degenerate verts fall back to an
arbitrary perpendicular). Empirically Haven stores `bit` byte-identical to `tan` (all
6 sampled normal-mapped models), so one tangent is computed and written to both; the
recompute matches the original to ~1.3° median (verified on knarr/woodheart). Validated:
no-op rebuilds of male (1 submesh), mulberry/cutblade/fairystone (2–7 submeshes),
wisp/algaeblob (morph), stallion/lilypadlotus (legacy `bones` v0 skinning + skel) and
**knarr** (multi-part + morph + skinned + normal-mapped)
reproduce exactly / faithfully (matid sequence + geometry diff 0 + morph deltas 0 +
tangents within ~1.3°, legacy-`bones` weights round-trip with 100% dominant-bone match
+ 0 weight error, valid containers); a moved bone re-poses `skel` on rebuild (knarr
`oar` +7 verified); cutblade's add/remove edit confirmed in-game;
synthetic glbs with added vertices / separate per-material blocks / a morph target /
tangent recompute rebuild correctly. **Next:** animation-keyframe editing
(`skan`/`manim` add/remove/retime frames).

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

## manim layer (mesh / morph animation)
Read-only viewer (`MeshAnimInfo`) ported from the client's `haven.MeshAnim.Res`.
Unlike `skan` (bones), `manim` animates **vertex positions** directly — a flag
rippling, a plant swaying. Stays raw/lossless. Format: `uint8 ver`(1), `int16 id`,
`uint8 rnd` (play frames random vs sequential), `float32 len`, then frames until a
`0` terminator. Each frame: `uint8 fmt`(1..4), `float32 time`, `uint16 n`
(vertex count); fmt 4 adds 6×`float16` quantisation bounds; then RLE spans
(`uint16 start`, `uint16 run`) of per-vertex data — fmt1 = 6×`float32` (pos+nrm),
fmt2 = `float9995`-packed `int32` (pos), fmt3 = 3×`float16` (pos), fmt4 = 3×
`unorm8` (pos, dequantised by the frame header). Confirmed on all 20 sample
layers (wisp flicker 8 frames float16; algaeblob 7 frames, ~10k morphs). With
this, **every layer type present in the samples is decoded** (read-only or editable).

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
