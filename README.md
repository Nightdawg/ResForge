# hafen-resedit

A standalone tool to **decompile, edit, and recompile Haven & Hearth `.res`
files** for modding. It unpacks a `.res` into an editable folder and repacks it
back — byte-for-byte identical when nothing is changed.

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
| `tooltip`,`pagina`| `*.txt`                      | edit UTF-8 text          |
| anything else     | `*.bin`                      | raw bytes (lossless)     |

For images, the header (z, sub-z, id, offset, metadata) is preserved verbatim in
the `.imghdr` part and only the embedded image is replaced — the most common
mod (re-skinning). The PNG may be any size; the layer length is recomputed.

`tex` layers hold the textures used by 3D models (the embedded picture is a
JPEG or PNG after a short header). The texture is exposed as a normal image file
between two verbatim `.bin` parts; on repack its length is recomputed, so you can
drop in a replacement of any size.

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

Requires JDK 21 (toolchain configured). Uses the bundled Gradle wrapper:

```sh
./gradlew build      # compile + run round-trip tests
```

## Extending

Typed decoders live in `hafen.resedit.layers`. To make another layer
human-editable, add a part-splitting rule in `res/Unpacker.java` (and the
inverse is automatic, since `pack` just concatenates parts). The `io`
package mirrors `haven.Message` primitives for decoding payloads.

## Status / scope

v0.1 guarantees lossless unpack/repack for **all** layers and friendly editing
for 2D images (`image`), 3D model textures (`tex`), and text. Deeper typed
editing (props, meshes, animations) can be layered on incrementally using the
same parts model.
