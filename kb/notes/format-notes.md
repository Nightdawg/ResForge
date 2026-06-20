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
(tto) header. We swap the embedded image and keep the header verbatim.

## tex layer (3D model texture)
From `haven.TexR.Encoded`: `int16 id`, `uint16` off x/y, `uint16` sz x/y, then
parts; the color image part is `int32 len` + encoded image (JPEG/PNG). Because
the length is stored *inside* the payload, our `tex` codec recomputes that int32
when the texture is swapped, so a replacement of any size repacks correctly.

## audio2 layer
`uint8 ver` (1..3), `string id`, `uint16 vol` (ver 2, bvol = vol*0.001), optional
tto metadata (ver 3), then an Ogg Vorbis stream to the end. We split into header
+ `.ogg`; the GUI decodes it with JOrbis and plays it.

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

## neg layer (collision / boundary)
From the client: `coord cc`, then 12 bytes the client just skips, then `uint8`
endpoint count and per endpoint `[uint8 id, uint16 n, n coords]`. CarryGun's tool
decodes those 12 bytes as tl / br / oc offsets (top-left, bottom-right, object
center) — likely `coord16` (int16 x/y) pairs. Lossy if re-encoded; preserve raw.

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
