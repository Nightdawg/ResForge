# hafen-resedit

A standalone tool to **decompile, edit, and recompile Haven & Hearth `.res`
files** for modding. It unpacks a `.res` into an editable folder and repacks it
back — byte-for-byte identical when nothing is changed.

## Quick start (common mods)

Build the tool once, then use the jar (handles paths with spaces):

```sh
./gradlew jar
# the jar is at build/libs/hafen-resedit-0.1.0.jar
alias resedit='java -jar build/libs/hafen-resedit-0.1.0.jar'
```

```sh
# See what's inside a file, or what's moddable across a whole folder:
resedit info   horse.res
resedit catalog C:\Haven\res

# Swap a single asset in one command (the originals are format-checked):
resedit replace horse.res image  newicon.png   horse.res   # 2D icon / sprite
resedit replace horse.res tex    newskin.jpg    horse.res   # 3D model texture
resedit replace theme.res audio2 newsound.ogg   theme.res   # sound
resedit replace ui.res    font   myfont.ttf     ui.res      # UI font

# Edit text / typed data: unpack, edit the file, pack:
resedit unpack horse.res            # -> horse.resdir/ (edit layers/*.txt or *.json)
resedit pack   horse.resdir         # -> horse.res

# Look at a 3D model (opens in Blender / Windows 3D Viewer):
resedit obj    horse.res horse.obj

# Experimental write path: scale a model's geometry (then load in-game to check):
resedit transform horse.res 2 2 2 horse-big.res
```

`replace` is the easy path for re-skinning; `unpack`/`pack` is for editing text
(`tooltip`/`pagina`) and typed JSON (`props`, `action` keybinds). Editing can
never silently corrupt a file — typed layers are only exposed when they
re-encode byte-for-byte, otherwise they stay raw.


## The `.res` format

A `.res` file is a tiny container (see `haven.Resource.load()` in the client):

```
"Haven Resource 1"   16-byte ASCII signature
uint16  (LE)         resource version
repeat until end of file:
    string           NUL-terminated UTF-8 layer name  (e.g. "image", "tooltip")
    int32   (LE)     layer payload length
    <length bytes>   layer payload
```

Each **layer** has its own internal format. The client's `Resource.java`
contains a decoder for every layer type (`image`, `tooltip`, `pagina`, `neg`,
`props`, `obst`, `anim`, `action`, `audio`, `code`, `vbuf2`, `mesh`, ...). The
developer's `mkres` Python compiler is the encoder side (mostly 3D meshes).

## How this tool works — the "parts" model

`unpack` writes a folder containing a `manifest.txt` plus a `layers/`
sub-folder. Every layer becomes one or more **part files** whose concatenation
equals the original payload, so `pack` can always rebuild the exact bytes.

| Layer type        | Parts written                | Editable as              |
|-------------------|------------------------------|--------------------------|
| `image`           | `*.imghdr` + `*.png`         | swap the PNG texture     |
| `tex`             | `*.pre.bin` + image + `*.post.bin` | swap a 3D model's texture (JPEG/PNG) |
| `audio2`          | `*.audhdr` + `*.ogg`         | swap a sound (Ogg Vorbis) |
| `props`           | `*.json`                     | edit typed properties as JSON |
| `action`          | `*.json`                     | edit button/keybind metadata as JSON |
| `font`            | `*.fonthdr` + `*.ttf`/`*.otf` | swap the embedded font   |
| `midi`            | `*.mid`                      | swap the MIDI music      |
| `tooltip`,`pagina`| `*.txt`                      | edit UTF-8 text          |
| anything else     | `*.bin`                      | raw bytes (lossless)     |

For images, the header (z, sub-z, id, offset, metadata) is preserved verbatim in
the `.imghdr` part and only the embedded image is replaced — the most common
mod (re-skinning). The PNG may be any size; the layer length is recomputed.

`tex` layers hold the textures used by 3D models (the embedded picture is a
JPEG or PNG after a short header). The texture is exposed as a normal image file
between two verbatim `.bin` parts; on repack its length is recomputed, so you can
drop in a replacement of any size.

`props` layers hold typed key/value properties (a `tto` stream). They are
exposed as editable `*.json`, but **only when the round-trip is provably
lossless** — the tool decodes, re-serializes to JSON, re-encodes, and checks it
reproduces the original bytes before offering JSON; otherwise the layer stays a
raw `.bin`. So editing props can never silently corrupt a resource.

`audio2` layers hold sound effects/music as Ogg Vorbis. The audio runs to the
end of the payload (like `image`), so it is split into a verbatim `*.audhdr`
header and a replaceable `*.ogg` — drop in any Ogg Vorbis file to swap a sound.

`action` layers describe action buttons/keybinds (parent resource, name,
hotkey, arguments). The format is fully deterministic, so it is exposed as
editable `*.json` (with the same lossless-or-raw guard as `props`) — handy for
renaming a button or rebinding its hotkey.

`font` layers embed a TrueType/OpenType font after a 2-byte header — split into
`*.fonthdr` + `*.ttf`/`*.otf` so you can swap the typeface. `midi` layers are a
whole MIDI file, exposed as `*.mid`.

## Usage

```sh
# Inspect a resource
./gradlew run --args="info paths/to/horse.res"

# Decompile -> horse.resdir/
./gradlew run --args="unpack paths/to/horse.res"

# ...edit layers/000_image.png, layers/001_tooltip.txt, etc...

# Recompile -> horse.res
./gradlew run --args="pack horse.resdir"
```

Swap a single asset in one shot, without unpacking the whole resource — pick a
layer by name (`image`), name + occurrence (`tex#2`), or absolute index (`#5`):

```sh
# Replace an icon, a 3D texture, a sound, or a font:
./gradlew run --args="replace horse.res image newicon.png horse.res"
./gradlew run --args="replace horse.res tex newskin.jpg horse.res"
./gradlew run --args="replace theme.res audio2 newsound.ogg theme.res"
./gradlew run --args="replace ui.res font myfont.ttf ui.res"

# Replace text or typed JSON (tooltip/pagina, or props/action):
./gradlew run --args="replace horse.res tooltip newtip.txt horse.res"
./gradlew run --args="replace menu.res action newaction.json menu.res"
```

If the output path is omitted, `replace` overwrites the input file in place.
Replacement media is format-checked (PNG/JPEG for images, `OggS` for audio,
sfnt for fonts), so a wrong file type is rejected rather than written.

Export a model's 3D geometry to a Wavefront **OBJ** you can open in Blender,
MeshLab, or the Windows 3D Viewer:

```sh
./gradlew run --args="obj horse.res horse.obj"
```

This de-quantises the `vbuf2` vertex buffers (positions, normals, texture
coords) and turns each `mesh` layer into an OBJ group. It is read-only — a
viewing/inspection aid; editing geometry back into `.res` is future work.

To see what is moddable across a whole folder of resources at a glance:

```sh
./gradlew run --args="catalog path/to/folder"
```

It prints one line per file listing its editable asset kinds (icon, texture,
sound, font, music, keybind, props, text, 3D-model) plus an aggregate summary.

Validate a file (or a whole folder, recursively) without unpacking — checks
that parse/serialize and unpack/pack are byte-identical and that every `image`
layer's embedded picture splits cleanly (decodable on its own):

```sh
./gradlew run --args="verify path/to/horse.res"
./gradlew run --args="verify path/to/folder-of-res"
```

Build a runnable jar with `./gradlew jar` (output under `build/libs/`), then:

```sh
java -jar build/libs/hafen-resedit-0.1.0.jar info horse.res
```

## Building / testing

Requires JDK 21. There are two equivalent builds:

**Gradle** (bundled wrapper, auto-downloads dependencies):

```sh
./gradlew build      # compile + run the tests
./gradlew jar        # -> build/libs/hafen-resedit-0.1.0.jar
```

**Ant** (for those who prefer it; JUnit jars are vendored in `lib/`):

```sh
ant build            # compile + jar + run the tests  -> build-ant/
ant jar              # -> build-ant/libs/hafen-resedit-0.1.0.jar
ant run -Dargs="info samples/apple.res"
```

The Ant build has no internet dependency — the JUnit 5 jars live in `lib/` and
output goes to `build-ant/` so it never clashes with Gradle's `build/`. Requires
Ant 1.10+ (for the native JUnit 5 `junitlauncher` task). Point `JAVA_HOME` at the
JDK *root* (not the `\bin` sub-directory) for either build.

## Extending

Typed decoders live in `hafen.resedit.layers`. To make another layer
human-editable, add a part-splitting rule in `res/Unpacker.java` (and the
inverse is automatic, since `pack` just concatenates parts). The `io`
package mirrors `haven.Message` primitives for decoding payloads.

## Status / scope

v0.1 guarantees lossless unpack/repack for **all** layers and friendly editing
for 2D images (`image`), 3D model textures (`tex`), sounds (`audio2`), fonts
(`font`), typed properties (`props`) and action/keybind metadata (`action`) as
JSON, and text. The 3D vertex buffers (`vbuf2`) are inspected read-only (vertex
count + attribute formats shown by `info`/`verify`). Deeper typed editing
(mesh/skeleton geometry, animations, collision) can be layered on incrementally
using the same parts model.
