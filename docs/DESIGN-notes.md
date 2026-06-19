# hafen-resedit — Design Notes & Session History

This document captures the reverse-engineering findings and design decisions
behind `hafen-resedit`, exported from the chat session in which the tool was
created. Keep it with the code so the context isn't lost.

- **Created:** 2026-06-19
- **Origin:** Built alongside the Haven & Hearth client repo (`hafen-client`),
  by reverse-engineering `src/haven/Resource.java` and `src/haven/Message.java`.
- **Tool location:** `IdeaProjects/hafen-resedit` (standalone Gradle/Java project).
- **Reference sources:** verbatim copies of the authoritative files live in
  [`reference/`](reference/) — `Resource.java`, `Message.java`,
  `NormNumber.java`, and `mkres-fragment.py` (see `reference/README.md` for
  provenance and licensing).

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
| `tooltip`,`pagina` | `*.txt`                 | edit UTF-8 text       |
| anything else      | `*.bin`                 | raw bytes (lossless)  |

For `image`, the split point (where the encoded image begins) is found by
parsing the simple header exactly, then validating against the image's magic
bytes (PNG/JPEG/GIF/BMP); a magic-scan fallback covers the newer typed header.
The header is preserved verbatim in `*.imghdr`, so only the picture changes.
The replacement PNG may be any size — the layer length is recomputed on pack.

`manifest.txt` example:

```
# hafen-resedit manifest
# Layer order is significant — do not reorder entries.
res-version: 7
layer	image	layers/000_image.imghdr,layers/000_image.png
layer	tooltip	layers/001_tooltip.txt
layer	neg	layers/002_neg.bin
```

---

## 4. Project layout

```
hafen-resedit/
  build.gradle, settings.gradle      # Gradle, application plugin, JUnit 5, JDK 21 toolchain
  gradlew, gradlew.bat, gradle/      # wrapper (Gradle 8.10.2)
  src/main/java/hafen/resedit/
    Main.java                        # CLI: info | unpack | pack
    io/MessageReader.java            # LE primitive decoder (mirrors haven.Message)
    io/MessageWriter.java            # LE primitive encoder
    res/ResContainer.java            # parse/serialize container + Layer list
    res/Layer.java                   # (name, byte[] payload)
    res/Manifest.java                # read/write manifest.txt
    res/Unpacker.java                # .res -> folder (parts model)
    res/Packer.java                  # folder -> .res
    layers/ImageInfo.java            # image header parse + PNG split point
  src/test/java/hafen/resedit/
    RoundTripTest.java               # byte-identical round-trip + image-edit tests
  README.md
  docs/DESIGN-notes.md               # this file
```

---

## 5. CLI usage

```sh
# Inspect
./gradlew run --args="info path/to/horse.res"

# Decompile -> horse.resdir/
./gradlew run --args="unpack path/to/horse.res"

# ...edit layers/000_image.png, layers/001_tooltip.txt, etc...

# Recompile -> horse.res
./gradlew run --args="pack horse.resdir"

# Or use the jar
./gradlew jar
java -jar build/libs/hafen-resedit-0.1.0.jar info horse.res
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
- **No third-party runtime deps**: only JUnit for tests; manifest is a tiny
  text format, so no JSON library is required and the tool builds offline.

---

## 7. Verification performed

- `./gradlew build` → BUILD SUCCESSFUL; JUnit round-trip tests pass.
- End-to-end CLI run on a synthesized resource (image + tooltip + neg):
  `info` reported metadata correctly; `unpack` split image into `.imghdr`+`.png`,
  tooltip into `.txt`; `pack` reproduced a **byte-identical** 146-byte file.

---

## 8. Possible next steps

- Validate against real `.res` files from a game install (none were available in
  the dev environment at creation time).
- Add typed editors: `props` (→ JSON-ish), `neg`/`obst` (collision/boundary
  geometry), `action`/`pagina` metadata, and eventually `vbuf2`/`mesh`/`manim`
  (port the relevant parts of the `mkres` Python encoder).
- Optional `repng`/`reimg` convenience command to validate that a replacement
  image re-encodes and that offsets/`tsz` still make sense.
- A small GUI or a `--watch` mode for rapid skin iteration.

---

## 9. Environment gotcha

The dev machine's `JAVA_HOME` was set to `...\graalvm-jdk-21.0.9+7.1\bin`
(includes `\bin`), which Gradle rejects. It must point at the **JDK root**
(`...\graalvm-jdk-21.0.9+7.1`). IntelliJ's own configured JDK sidesteps this,
but fix the env var for terminal `gradlew` use.
