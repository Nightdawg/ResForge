# Format notes (atomic, searchable)

Granular `.res` format facts, one heading per topic so the RAG can retrieve them
individually. The deep narrative lives in `docs/DESIGN-notes.md`; this file is
for quick lookups and grows as we learn more.

## Container
`"Haven Resource 1"` (16 ASCII bytes) + `uint16` little-endian version, then
repeated layers until EOF: a NUL-terminated UTF-8 name, an `int32` LE payload
length, then that many payload bytes. Unknown layer names are skipped by the
game client, so a tool can pass them through untouched. `ResContainer.parse`
rejects a payload length that is negative or larger than the bytes remaining (a
crafted huge length would otherwise try to allocate ~2 GB → OOM), and layer names
are decoded as strict UTF-8 (malformed bytes are rejected, not turned into U+FFFD,
so a name round-trips byte-exact). The reader's bounds check is overflow-safe
(`n<0 || n>end-pos`), so a near-`Integer.MAX_VALUE` or negative length can't slip
past it or rewind the cursor into an infinite loop.

## image layer
`uint8 ver`. If ver < 128: `int8`*256+ver = z, `int16` subz, `uint8` flags
(fl&2 nooff, fl&4 has-info), `int16` id, offset coord, optional info entries,
then the encoded image (a normal PNG/JPEG) to the end. If ver-128 == 1: a typed
(tto) header — `int16` id then tto key/value pairs until an empty key, then the
image. `ImageInfo` finds the image start by parsing the header **exactly** (the
new-style tto block stepped over with `TtoSkip`), and only offers the split when
that offset lands on a real image magic — it never magic-byte-scans, because a
"BM"/JPEG signature inside header metadata would be a false start (harmless for the
lossless concat repack, but it would corrupt a `replace`/export that slices the
header at the wrong point). We swap the embedded image and keep the header verbatim.
The `off` coord is the frame's draw offset — the GUI's `AnimView` uses each frame's
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

A tex layer can also carry an **alpha mask** as a separate part — part tag `4`
(`int32 len` + encoded image, usually a PNG silhouette), often alongside a filter
part (tag 1, e.g. the "Mipmapper" value) — because the color image is frequently an
opaque JPEG (no alpha), so the cutout shape (e.g. a tree's foliage outline) lives in
the mask. It is editable via `TexMaskCodec`, which format-checks replacements and
recomputes the embedded int32 length under the lossless-or-raw guard. `TexInfo`
locates **both** the color image (tag 0) and the mask (tag 4):
it scans the whole part stream, but once the color image is found any later trouble
just stops the scan with the color result intact, so the (byte-exact) color split is
never weakened. The 3D viewer uses the mask as the alpha channel for cutout (mask
luminance `< 128` ⇒ transparent), which is why foliage renders as proper leaf shapes
rather than black cards. Real example: every mulberry tex is `tag0 JPEG color +
tag1 filter(4) + tag4 PNG mask`.

## tileset2 / tile / flavobj layers (terrain)
The terrain tileset system (from `haven.Tileset`), three related layers:
- **`tileset2`** — a sequence of parts to EOM, each a `uint8` tag: **0** = `string`
  tiler name (e.g. `gnd`, `trn-r`, `cave`, `water`) + a `tto` arg list (the tiler's
  parameters, e.g. base/var weights); **1** = `uint16` flavour count, `uint16` flavprob,
  then per flavour `[string res, uint16 ver, uint8 weight]` (scattered decoration
  resources); **2** = `int8 n` + `n` × `string` tag. `TilesetInfo` surfaces the tiler
  name, tags and flavour refs (read-only). In the ~8.8k corpus all 199 reach EOM; tiler
  names seen: `trn`/`trn-r`/`cave`/`water`. (Flavours mostly live in separate `flavobj`
  layers rather than part 1.)
- **`tile`** — a single terrain tile: `uint8 t` (kind: `'g'` ground / `'b'` border-
  transition / `'c'` centre-transition), `uint8 id` (1..15 transition id), `uint16 w`
  (weight), then the tile **image** (PNG/JPEG) to EOM. `TileInfo` locates the image; the
  image is **editable** (swap → terrain re-skin) via the `image`-style replace path (runs
  to EOM, length implicit). 64×64 in the samples.
- **`flavobj`** — `uint8 ver`(==1), `string res`, `uint16 ver`, then a `tto` arg list
  (the flavour factory args, e.g. spawn probability). References one resource (a sprite or
  ambient sound). `FlavObjInfo` surfaces it; 439 in the corpus, all reach EOM.
These three feed the aggregated `refs` report (`flavobj` + `tileset2` flavours), which had
been missing the 439 flavour references. The `tto` arg lists are stepped over with the
shared `TtoSkip` (full tag set), so we never need to interpret them to stay aligned.

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
`props`: `uint8 ver` (==1) + a tto list of alternating key/value. **Now editable as
JSON** (`PropsCodec`) using the same explicit tagged-value form as `mat2`: a string
stays a plain JSON string; every other value is a single-tag object naming its exact
tto type (`{"u8":50}`, `{"f32":0.5}`, `{"color":[r,g,b,a]}`, `{"coord":[x,y]}`,
`{"bytes":"<base64>"}`, `{"list":[…]}`, `{"map":{…}}`). Supported: str, nil, int
widths, f32/f64, color, fcolor, coord, fcoord32/64, bytes, uid, resid, resspec, nested
list/map; float8/float16 and snorm/unorm/mnorm stay raw (round-trip not byte-exact).
Tagging everything (vs leaving ints/lists/maps bare) keeps a JSON object unambiguously
a tag and records the exact wire width. Real sample with a non-native type:
`belltower.res` → `{ "use-point": {"fcoord64":[0.0,-0.59]} }` (previously raw). `action`
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

Modern form (`fl & 0x80`, version 1): `int16 id`, `int16 vbufid`, a string-keyed
tto metadata map, format string (`""` raw or `"strips"`), `uint16 numTris`, then
indices. The client obtains the material and part reference from integer `mat` and
`ref` metadata values. Rebuild must preserve the whole metadata map verbatim; writing
the legacy form with `matid=-1` leaves the mesh without its specified material.

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
texture is `[{u8:k}, …]` where **k is the `tex` layer's own id** (the client's
`flayer(TexR.class, k)` lookup — an id, **not** a positional index into the `tex`
layers), while an **external** texture is `[<string respath>, …]` (e.g. `mlink` →
`gfx/terobjs/trees/peartree-tex` — lives in *another* `.res`). The id-vs-position
distinction matters whenever a model's `tex` ids aren't `0,1,2,…` in order — e.g.
the **mulberry tree** has `tex` ids `1,3,4,5` (four seasonal leaf variants) and its
leaf material (`matid 9`) does `tex {u8:1}` → tex **id 1** (the green foliage), which
is the *first* `tex` layer (ordinal 0); resolving k as an ordinal would wrongly pick
the second layer (id 3). Both the 3D viewer (`LocalTextures`) and the glTF export
(`GltfExport`) resolve through a tex-id→ordinal map. The export uses local `tex`
images only and requires an explicit mapping even when there is just one texture;
unmapped, `matid=-1`, and external-only materials omit `baseColorTexture` rather
than receiving an unrelated first image. External (mlink/`@res`) textures aren't
fetched for export, so those parts remain untextured in glTF (e.g. the mulberry
**trunk**, via `mlink gfx/terobjs/trees/mulberry-tex`).
(knarr: 6 local tex but most parts use external mlinks.)

A material's texture source falls into one of four kinds, classifiable from its
`mat2` commands alone. **Keep these distinct** — they were previously lumped as
"variable/external", which conflated three unrelated mechanisms:

1. **Local texture** — a `tex` command whose value is a numeric id resolving into
   this resource's own `tex` layers (the client's `flayer(TexR.class, id)` lookup).
   Fully resolved today (3D viewer + glTF export).
2. **External static material** — an `mlink`, or an external `tex`/`otex` *string*,
   naming exactly **one** other resource (e.g. mulberry's bark via
   `mlink gfx/terobjs/trees/mulberry-tex`). The texture is **fixed and deterministic**:
   it can be resolved by **fetching that resource** and following *its* own
   `matid→mat2→tex` chain. (An `mlink` may instead carry a local `{u8:id}` — a link to
   another *local* material — which stays in-resource.) This is **resolved** by the
   View-3D **"Resolve external textures (network)"** toggle (`model/ExternalTextures`).
   Confirmed on mulberry: every non-leaf material is either this kind or kind 1;
   `mulberry-tex` is a self-contained 2-`tex` resource that references nothing further,
   so the bark always looks the same.
3. **Variable material (varmat)** — a material whose texture is chosen at **runtime**
   (e.g. a built object's wood-type plank/block textures, picked from what it was made
   of). The final image is **not stored in the model `.res`**, so it can't be statically
   derived. Genuinely deferred, and **separate from kind 2**.
4. **Dyntex** (`spr` → `haven.res.lib.dynspr.Dyntex`) — **not** a variable material:
   it's a dynamic-sprite *addition* declared via `code`/`spr`, layered on rather than a
   base-texture source. **Out of scope** for texture resolution; noted only.

A local `otex` is an **overlay**, not a base (`haven.resutil.OverTex`), so a material
carrying only a local `otex` over a non-local base is not itself locally based. The
client looks textures up by id via `flayer(TexR.class, id)` for `tex`/`otex`/`pal`/`bump`,
and `mlink` via `layer(Res.class, id)` / a respath.
`LocalTextures.isLocalBaseTex(matid)` / `ModelGeometry.Material.localBase` implement the
**local-base** test (kind 1 only); the View-3D picker uses it, so only local-base
materials are swappable (knarr shows 1 picker not 10, mulberry 1). Resolving kind 2 by
**fetching** the referenced resource is **implemented** (`model/ExternalTextures`, behind
the View-3D toggle); kinds 3 and 4 are left shaded/ignored (see decisions.md "3D viewer
per-material texture picker").

## glTF (.glb) model export
Modern alternative to OBJ for the 3D model, and the basis of the current edit
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
sampler input (with the required min/max). `SkanInfo` captures per-frame
time/translation/rotation (fmt0 cpfloat, fmt1 quantised). A normal model export uses
its local `skel`; `gltf-skan` instead accepts separate model, skeleton and animation
resources, matching the game's runtime composition. If compatible `skan` layers
target disjoint bones, export adds a preview-only `skan_combined` action while
retaining each editable `skan_<id>` action. A track ending before `len` implicitly
interpolates back to frame 0 in the client, so glTF export writes frame 0 explicitly
at `len`; import removes that generated closing key when unchanged. A zero-duration
static pose is valid (`gfx/borka/arms-2hsword` has three): export uses a synthetic
one-second endpoint for Blender, and import collapses a static edit back to one frame
at time zero. SKAN ids are not unique per resource (`gfx/terobjs/arch/sawmill` has
two `id=0` layers), so glTF actions also carry their SKAN layer ordinal. Quaternion
equivalence checks must normalize both values: fmt-0 decoded axes can leave norms a
few parts per million from one, enough to look like >0.1 degrees if raw dot products
are passed to `acos`. `rebuild-skan` inverts the bind
composition, supports LINEAR translation/rotation keyframe edits, and writes the
original skan wire format. If the edited latest key differs from
the original track range, it becomes the new effect-free clip duration; an unchanged
range preserves the original `len` (including trailing time) and bytes. A <=20 ms
difference is ignored as Blender frame-grid rounding (`wave` 1.2667 s -> 1.25 s).
Duration
changes are rejected when `{ctl}` tracks exist because fmt-1 event times scale with
`len` while fmt-0 event times are absolute. Raw `{ctl}` payloads remain exact when
other tracks change.
Blender re-export can add constant two-key `STEP` T/R channels and identity-scale
channels for otherwise static bones (observed on an edited `gfx/borka/wave` GLB:
up to 98 STEP channels/action). These are safe to accept only when every T/R sample
is equal (quaternion sign-equivalence included) and every scale component is 1.
Blender multi-action GLBs list the active action first but bake inactive actions against
the active pose. A snapped `gfx/borka/spear-throw` export kept `skan_0`, flattened
`skan_1` through `skan_5`, and made `skan_combined` a hybrid; rebuild must use only
the first SKAN action when `asset.generator` identifies Blender.
Snapping sub-frame keys can perturb otherwise constant translation samples by about
`1.1e-6`; a `1e-5` component tolerance accepts that Blender float noise without
treating meaningful stepped motion as linear.
Nonconstant STEP cannot map faithfully to skan's interpolation and stays rejected.
The software 3D viewer also plays `skan`: bind-local transforms are composed as
`bindPos + frameTrans` and `bindRot * frameRot`, world matrices follow the `skel`
parents, and each soup vertex uses the same normalized top-four linear blend as the
client. Multi-layer resources default to all compatible clips combined in resource
order (`Skeleton.CombinedMod.apply` semantics: translations add, rotations multiply).
`gfx/borka/wave` confirms six parts / 38 disjoint tracks animate together.
`manim` (mesh/morph) layers also export as glTF **morph targets**: each
frame's per-vertex position deltas become a morph target (deltas are *added* to the
base and *linearly* interpolated — exactly glTF morph semantics, matching the
client's `add(in, poff)` + `mix`), and a `weights` animation drives them (e_i per
frame over the frame times, looping back to frame 0 at `len`). `MeshAnimInfo` now
captures per-frame idx/deltas (fmt 1/3/4). So knarr exports both skeletal *and*
morph animation; wisp's flicker and algaeblob's sway export as morphs.

### glTF model rebuild (bring edits back from Blender)
`GltfImport.rebuild` brings an edited `.glb` back into a model. Rather than patching
the original structure (which couldn't change vertex/triangle counts), it
**regenerates** the model's
geometry from the glTF, so you can reshape, re-UV, add, remove or re-topologize
vertices and faces in
Blender. It rebuilds `vbuf2` at the glTF's vertex count — positions/normals/UVs
re-quantised into the original attribute formats (via `Vbuf2Codec.setAttr` after
setting `num`). The editable encoder covers every format the reader accepts:
`f4/f2/f1`, `sn/un/rn` in 4/2/1-byte widths, `sf9995`, and
`uvech/uvec1/uvec2`. Skinning rebuilds `bones2`/legacy `bones` from
`JOINTS_0`/`WEIGHTS_0` (joints mapped
to bone names via the skin, then `Vbuf2Codec.setBones2`, which re-encodes either the
modern `bones2` header or the legacy `bones` header with `f4` weights) — and writes a
fresh raw-index `mesh`
(`fl=16` form: num, matid, [id], vbufid, then `num*3` uint16 indices) reusing the
original mesh's matid/vbufid. All other layers (textures, materials, code) are kept,
**and the skeleton is re-posed** if a bone moved in Blender (change-gated, so plain
reshaping leaves `skel` byte-identical, while a moved bone is re-encoded as `skel`
ver1 — each bone's new local transform read from its glTF joint node by name, then
`SkelInfo.encodeVer1`: mnorm16 angle + snorm16 octahedral axis + float32 pos; knarr is
ver-1, lilypadlotus/stallion are ver-0, all re-encode to ver-1). The axis convert is
inverted (glTF Y-up→Haven Z-up, `(gx,gy,gz)→(gx,-gz,gy)` for positions, normals and
morph deltas). No per-vertex id is needed (vertex order = glTF order) and it gives up
byte-exactness, so it leans on in-game testing — exposed as CLI
`rebuild-gltf` and a GUI "Rebuild from glTF" (with a not-lossless warning).

**Multi-submesh** is supported: each glTF primitive becomes a submesh. Legacy meshes
use `rfmat_<matid>`. Modern meshes use one stable material per original layer,
`rfmat_<matid>_mesh_<ordinal>`, so duplicate matids cannot merge away distinct
`mat`/`ref` metadata; rebuild restores that layer's original modern metadata bytes.
Older exports that collapsed modern parts to `rfmat_-1` are split using the original
triangle counts when the total topology is unchanged, and rejected otherwise.
Primitives are concatenated into the one shared `vbuf2`, **de-duplicated by POSITION
accessor** so our own (shared-buffer) export isn't copied per primitive while
Blender's per-material split blocks are concatenated with their indices offset. The original mesh layers are all replaced by
the rebuilt submeshes at the first mesh position, every other layer kept. It handles
positions/normals/UVs/bone-weights **and morph (`manim`) models**: each frame's
shape is rebuilt from the glTF morph targets (concatenated per part like the
geometry, axis-inverted) and re-encoded via `MeshAnimInfo.encodeWith` at the new
vertex count, keeping the original frame times; the shape-key count must equal the
frame count (adding/removing morph frames isn't supported yet). Blender writes shape
keys as **sparse accessors** (an all-zero base with no `bufferView` + an indices/values
pair), so `readAccessor` handles sparse (and integer/normalized) accessors, not just
dense float ones. **Normal-mapped
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
tangent recompute rebuild correctly. **Open limitation:** `skan` edits are not
imported; `manim` shapes rebuild only with the original frame count/timing.

Rebuild **bakes un-applied glTF node transforms**: if a Blender object was moved,
rotated or scaled without "Apply Transform", that transform lives on the glTF node
(translation/rotation/scale or a `matrix`), not in the vertex data. `rebuild` walks
the scene node tree, accumulates each mesh node's world matrix, and applies it to
that primitive's positions (and to normals via the inverse-transpose 3×3, then
renormalised). Morph position deltas receive the same matrix's linear 3×3
(rotation/scale, no translation) before axis inversion, since deltas are vectors
rather than points. This keeps transformed shape keys in the same frame as their
base mesh. Identity
nodes (and our own exporter's output, which has none) change nothing. Skinned meshes
are skipped (glTF ignores a skinned mesh's node transform; vertices live in skin
space). Skinning weights are also **renormalised per vertex** after any glTF joint
that doesn't map to a Haven bone is dropped, so the surviving influences still sum to
1. The rebuild **validates its glTF input** before trusting it — GLB JSON/BIN chunk
lengths within bounds, accessor indices in range, accessor reads inside the BIN
chunk, sparse indices in range, triangle-list mode only, index count a multiple of 3
with every index `< vertexCount`, and each `setAttr` array the expected
`num*eln` length — so a malformed/hostile `.glb` fails with a clear error instead of
producing a corrupt `vbuf2`/`mesh`.

## anim layer (sprite animation)
From `haven.Resource.Anim`: `int16 id`, `uint16 delay` (frame duration in ms),
`uint16 n`, then `int16[n]` frame image-ids. Each frame references `image`
layers in the same resource by id, so the pixels live in (separately editable)
image layers. Fully deterministic, so **editable as JSON**
`{"id":…,"delay":…,"frames":[…]}` (`AnimCodec`, codec `anim`, lossless-or-raw).
Real samples: `prog` (25 frames @120ms), `cleave`/`jump` (8 @100ms),
`flex` (8 @75ms) — all `id=-1`, frame ids contiguous from 128.

## rig / lighting layers (light, skel, skan, boneoff)
Viewers ported from the client's `Light.java` and `Skeleton.java`
(LGPL-3, in `docs/reference/`). `skel`/`skan` have read-only structural layer
views; glTF rebuild can re-pose `skel`, and the composite animation workflow imports
edited `skan` translation/rotation tracks. **`boneoff` and `light` are editable JSON**.
They use number encodings beyond the basic primitives, now in `MessageReader`:
- **`cpfloat`** — custom-packed float: `int8` exponent + LE `uint32` (top bit sign,
  low 31 bits mantissa); value = `2^e · (1 + m/2^31)`, with `e=-128,m=0` meaning 0.
  Mirrors `haven.Utils.floatd`. (Validated bit-exact on real `light`/`skel` samples.)
  `MessageWriter.cpfloat` is the exact inverse — `e = getExponent(a)`,
  `m = round((a/2^e − 1)·2^31)` (with a carry when rounding rolls into the next binade),
  so a decode→encode is byte-identical (verified on all 42 sample cpfloat values).
- **`mnorm16`** = `uint16/2^16` (modular [0,1)), **snorm16** = `int16/0x7fff`
  (signed [-1,1]), **unorm16** = `uint16/0xffff` ([0,1]).
- **`oct2uvec`** — decodes an octahedral-encoded unit vector from two snorm16s.

Formats:
- **`light`** (`LightInfo` for the read-only summary; `LightCodec` for editing): `u8 ver`.
  ver0: `int8 id`, 3 colours (each 4×`cpfloat`
  ×255 = RGBA), then tagged opts to EOM — `t1` attenuation (3 cpfloat), `t2`
  direction (3 cpfloat), `t3` spot exponent (cpfloat). ver1: `int16 id`, colours as
  4×`float32`, same tags with float32. Att ⇒ point/spot light; else directional.
  **Editable as JSON** `{version, id, ambient, diffuse, specular, attenuation?,
  direction?, exponent?}` (`LightCodec`, codec name `light`, lossless-or-raw): colours
  are the **raw 0..1 fractions** (not ×255), cpfloat (ver0) / float32 (ver1) both
  byte-exact; the optional tags are re-emitted in tag order (1/2/3), so a layer whose
  tags are stored in that conventional order round-trips, else it stays raw. Both sample
  lights (wisp ver0, villageidol ver1, both point lights) round-trip byte-exact.
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
  `SkanInfo.encode` retains fmt 0 or 1 (including optional nspeed), re-encodes edited
  bone tracks, and copies each control-track payload verbatim. Format retention is
  required because fmt 0 control times are cpfloat while fmt 1 times are unorm16.
- **`boneoff`** (`BoneOffInfo` for the read-only summary; `BoneOffCodec` for editing):
  equip-point opcode program — `string name` then
  opcodes to EOM: 0/16 translate (cpfloat/float32), 1/17 rotate (cpfloat / mnorm16
  ang + oct axis), 2 equip-point at named bone, 3/19 bone-align, 4 null-rotation,
  5 scale (float32). **Editable as JSON** `{name, ops:[{op,…},…]}` (`BoneOffCodec`,
  codec name `boneoff`, lossless-or-raw). The cpfloat/float32 translations decode to
  plain numbers; the quantised rotation (opcode 17/19) keeps its axis as the **raw
  octahedral `snorm16` ints** (`axisOct`/`refOct`) rather than a decoded unit vector,
  because oct2uvec→uvec2oct is **not** byte-exact (drifts ±1 on 4/20 sample instances) —
  storing the raw components keeps all 28 sample boneoffs losslessly editable, while the
  mnorm16 angle (exact) is exposed as a friendly turn-fraction `angleTurns`.
  Preview evaluation mirrors the render-state composition: opcodes are applied in
  payload order and each ordinary transform is post-multiplied onto the accumulated
  matrix. `eqpoint` contributes the named bone's current animated world transform;
  `bonealign` contributes `translate(origin) * rotate(ref onto target-origin)`;
  `nullrot` replaces the accumulated upper 3x3 with identity while preserving its
  translation, exactly like the client's `Transform.nullrot`.
Confirmed decoding every sample to EOM: light 2, skel 3, skan 5, boneoff 28 (all 28
boneoffs + both lights round-trip byte-exact, `verify` BoneOff histogram = `json 28`,
Light histogram = `json 2`). e.g.
knarr's skel = 11 bones (Main root + sails/oar), skan = 8 s loop, 11 tracks.

## manim layer (mesh / morph animation)
Read-only layer viewer (`MeshAnimInfo`) ported from the client's
`haven.MeshAnim.Res`; glTF rebuild can replace frame morph shapes when the
original frame count/timing is preserved.
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
- **`rlink`** (`RLinkInfo`): one render-link per layer (mirrors `haven.RenderLink.Res`).
  `uint8 lver`; if `lver < 3` the version byte **is** the link type (id `-1`); if `lver`
  is 3/4, `int16 id` + `uint8 type`, and for `lver >= 4` a `string`-keyed `tto` metadata
  map (empty key ends). Then a type body: **0 MeshMat** (`string mesh, u16 ver, i16
  meshid, string mat, u16 ver, i16 matid` — two refs), **1 AmbientLink** / **4 ResSprite**
  (`string, u16 ver`), **2 Collect** (`string, u16 ver, i16 meshid, [i16 meshmask]`),
  **3 Parameters** (`string, u16 ver` + a `tto` arg list, which often nests `res()` refs,
  tto tag 34). `references()` collects every resource a link points at (empty name = self,
  no ref). Earlier we only decoded type 3; a real-cache census showed **type 0 is ~80% of
  all rlink layers** (990/1225), so the old `refs` was missing the large majority of link
  references — now all five types decode (1205/1225 recognized). Parser is tolerant
  (an unknown type/version leaves the layer raw, contributing no refs). The reusable
  `tto` reader mirrors the one in `CodeEntryInfo`.

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
`ResourceFetcher` encodes each slash-delimited resource-name segment as strict UTF-8
URI path data. It rejects query/fragment markers, percent escapes, controls, empty
segments and `.`/`..` segments so names cannot be reinterpreted by URI parsing.

## Local resource cache (HashDirCache)
The client caches downloaded resources under `<localdir>/data`, where `localdir`
is `%APPDATA%\Haven and Hearth` on Windows and `~/.haven` elsewhere (from the
client's `Config.localdir()`). Each cache file is named `%016x.%d` — a name-hash
(`h = h*31 + ch` over the cache-id URI then the name) plus a collision-chain index
(`.0`, `.1`, …). Every file starts with a small header written by
`HashDirCache.writehead`: `writeByte(1)` (version), `writeUTF(cid)` (the cache
identity = the base URL), `writeUTF(name)` (the resource name), then the raw `.res`
bytes. `writeUTF` is Java modified-UTF-8 with a 16-bit big-endian length, so the
header decodes with `DataInputStream.readUTF` (the exact routine the client's
`readhead` uses). **Resource** entries have a `name` starting with `res/` (e.g.
`res/gfx/borka/male`, cid `…/render/`); the rest of the cache is map grids
(`map/…`, cid `…/java/`) and other non-resource data. Stripping the `res/` prefix
yields exactly the path our `fetch` expects. `net/CacheIndex` reads this (filename
filter = 16 hex + `.` + digits; parallel scan; sorted/deduped) to list what the
player already has — empirically ~82% map grids, ~18% resources (~8k distinct on a
real ~44k-file cache). We use the cache for **names only** and always re-fetch from
the server (latest version); we never open the cached bytes. (Decoded clean-room
from the client's `HashDirCache.java`; the format was independently confirmed by the
read-only Rust tool `ancientchina/hafen-res`, from which no code was taken.)
