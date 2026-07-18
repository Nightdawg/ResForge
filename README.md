# ResForge

A standalone tool to **decompile, edit, and recompile Haven & Hearth `.res`
files** for modding. It opens a `.res` in a **graphical editor**, lets you swap
textures, sounds, fonts, text and keybinds, and saves it back — byte-for-byte
identical when nothing is changed. Everything is also available on the
[command line](#command-line-interface) for scripting and batch work.

## Graphical editor

The editor is the main way to use ResForge. Double-click the jar, or run it with
no arguments, to open it:

```sh
java -jar build-gradle/libs/resforge-1.2.1.jar          # opens the editor
java -jar build-gradle/libs/resforge-1.2.1.jar gui horse.res   # opens a file
```

**Display scaling (HiDPI).** The editor adapts to the monitor's scale factor, so
it renders at the right size on 4K/HiDPI screens and at fractional Windows scaling
(125%, 150%, …). If you'd like the whole UI a little larger or smaller, adjust the
**scale factor** (1.0 = automatic default) — the simplest way is in the GUI under
**Options → UI scale…**, which remembers your choice and applies it on the next
launch. It scales the layout as well as the text (row height, thumbnails, panels,
the window), so everything stays proportional. For scripted or one-off use you can
also pass an override — the environment variable `RESFORGE_UI_SCALE` or the JVM
property `-Dresforge.uiScale` (both clamped to 0.5–4.0); a launch override wins
over the saved GUI preference:

```sh
RESFORGE_UI_SCALE=1.25 java -jar build-gradle/libs/resforge-1.2.1.jar
java -Dresforge.uiScale=1.25 -jar build-gradle/libs/resforge-1.2.1.jar
```

**Dark mode.** Prefer a darker UI? Toggle **Options → Dark mode** to switch the
whole editor between the light theme and an IntelliJ-inspired dark palette instantly
— no restart needed. Its near-black content surfaces, raised controls, visible
separators, and subdued blue selections make component boundaries easier to follow.
Your choice is remembered and re-applied on the next launch. The transparency
checkerboard behind image/animation previews adapts to the theme too.

Open a `.res` (toolbar button or drag-and-drop), or **Fetch from server…** to
download one straight from the game's resource server by its in-game path (e.g.
`gfx/borka/male`). Downloaded response bodies are capped at 64 MiB; larger
fixed-length or chunked responses are rejected rather than buffered without bound.
On Windows the **Open** and **Save as** file pickers are the
modern Explorer dialog — with the editable **address bar**, so you can paste a
full folder or file path straight into the top bar instead of clicking through
folders (it falls back to the classic picker if the modern one is unavailable).
The Fetch dialog **remembers your successful paths** and lists
them below the input as substring-matched, click-to-use suggestions (type any part
of a path, e.g. `borka`, to filter; double-click to fetch). Or **Open from game
cache…** to browse the resources you already have locally: it scans Haven's cache
(`%APPDATA%\Haven and Hearth\data`), lists every resource name found there, and
fetches the one you pick **fresh from the server** — the cache supplies only the
names, so you always open the latest version. ResForge saves the resulting name
index and reuses it until the game cache changes, avoiding repeated reads of the
much more numerous map-cache headers. Selecting a layer shows the right tool for it: a **picture preview** with
Replace/Export for icons and 3D textures; a built-in **sound player** (Play /
Stop / draggable seek) for audio; a live **animation preview** that plays sprite
animations; an editable **text box** for valid UTF-8 tooltips/pagina (invalid
payloads stay raw);
an editable **JSON box** for properties and keybinds; Replace/Export for sounds
and fonts; a built-in **3D viewer** (**View 3D** — a dependency-free software
renderer that shows the model **textured** and shaded, with an optional wireframe
and mouse orbit/zoom/pan, in its bind pose; a **per-material texture picker** lets
you choose which of the resource's own `tex` layers each locally-textured part is
drawn with — e.g. flip a tree's leaves between its seasonal variants live; a material
whose base texture isn't a local `tex` — it comes from another resource via `mlink`/an
external `tex` string (an *external static material*), a runtime *variable material*, or a
`Dyntex` sprite addition — gets no picker and shows shaded; an optional **Resolve external
textures (network)** toggle fetches the linked resource to texture the *external static*
parts (e.g. a tree's bark) — shown only for models that have such parts — while runtime
varmat/`Dyntex` parts stay shaded); and a full **3D model round-trip** — export to a Blender-ready binary
**glTF** (`.glb`, carrying both of Haven's UV sets, textures, the
**skeleton/skinning**, **skeletal animations** and **mesh-morph animations** in one
file — compatible skeletal layer fragments also get a composed Blender action, and
loop-closing keys preserve the declared clip duration, with an editable synthetic
timeline for zero-duration poses; duplicate SKAN ids remain independently editable
by layer); **Rebuild from glTF** to bring edits back — regenerate geometry so you can
**reshape/sculpt, re-UV, add, remove or re-topologize vertices and faces** (multi-part,
morph, skinned and normal-mapped models supported — positions/normals/UVs are
re-quantised into the original on-wire formats, **skinning weights** rebuilt, tangents
recomputed, the **skeleton** re-posed and **morphs** re-shaped). You can
also add,
delete and reorder layers, edit the resource version, and undo/redo. For
old-style image layers you can also **edit the header** (id, z/sub-z, draw
offset, no-offset flag), edit a **texture** header (id, atlas offset, size) and an
**audio** clip's id + volume, and adding an image wraps it in a fresh layer with
the next free id — so you can extend a sprite **animation** by adding frames and
listing their ids in the `anim` editor. The 3D `vbuf2`/`mesh` layers show their
vertex/attribute and triangle/material detail (read-only in-app), but a whole model
can be edited by the **glTF round-trip** (export → edit in Blender → re-import).
Unchanged layers are preserved byte-for-byte on save, so edits can't corrupt a file.

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

Unpacking a `.res` writes a folder containing a `manifest.txt` plus a `layers/`
sub-folder. Every layer becomes one or more **part files** whose concatenation
equals the original payload, so repacking can always rebuild the exact bytes.

| Layer type        | Parts written                | Editable as              |
|-------------------|------------------------------|--------------------------|
| `image`           | `*.imghdr` + `*.png`         | swap the PNG texture     |
| `tex`             | `*.pre.bin` + image + `*.post.bin` | swap a 3D model's texture (JPEG/PNG) |
| `audio2`          | `*.audhdr` + `*.ogg`         | swap a sound (Ogg Vorbis) |
| `props`           | `*.json`                     | edit typed properties as JSON (tagged `tto` values) |
| `action`          | `*.json`                     | edit button/keybind metadata as JSON |
| `mat2`            | `*.json`                     | edit material commands as JSON |
| `anim`            | `*.json`                     | edit sprite animation (speed + frames) as JSON |
| `neg`             | `*.json`                     | edit click hotspot / hitbox + connection points as JSON |
| `obst`            | `*.json`                     | edit movement-collision polygons as JSON |
| `boneoff`         | `*.json`                     | edit equip-point placement (translate/rotate ops) as JSON |
| `light`           | `*.json`                     | edit a light source (colours, attenuation, direction) as JSON |
| `font`            | `*.fonthdr` + `*.ttf`/`*.otf` | swap the embedded font   |
| `tile`            | `*.tilehdr` + `*.png`        | swap a terrain tile image |
| `midi`            | `*.mid`                      | swap the MIDI music      |
| `tooltip`,`pagina`| `*.txt`                      | edit strictly valid UTF-8 text; otherwise raw |
| anything else     | `*.bin`                      | raw bytes (lossless)     |

For images, the header (z, sub-z, id, offset, metadata) is preserved verbatim in
the `.imghdr` part and only the embedded image is replaced — the most common
mod (re-skinning). The PNG may be any size; the layer length is recomputed.

`tex` layers hold the textures used by 3D models (the embedded picture is a
JPEG or PNG after a short header). The texture is exposed as a normal image file
between two verbatim `.bin` parts; on repack its length is recomputed, so you can
drop in a replacement of any size. Some `tex` layers also carry a separate **alpha
mask** (a PNG silhouette — the cutout shape for foliage and the like, since the
colour image is often an opaque JPEG); in the GUI the mask gets its own
preview with Replace/Export, and the 3D viewer uses it so foliage renders as proper
leaf shapes rather than black cards.

`props` layers hold typed key/value properties (a `tto` stream). They are
exposed as editable `*.json`, but **only when the round-trip is provably
lossless** — the tool decodes, re-serializes to JSON, re-encodes, and checks it
reproduces the original bytes before offering JSON; otherwise the layer stays a
raw `.bin`. So editing props can never silently corrupt a resource. Each value
carries an explicit type tag (the same tagged-value form as `mat2`): a string is
a plain JSON string, everything else is a single-key object naming its exact
`tto` type (`{"u8":50}`, `{"f32":0.5}`, `{"color":[204,204,204,255]}`,
`{"coord":[x,y]}`, `{"bytes":"<base64>"}`, `{"list":[…]}`, `{"map":{…}}`), so
coord/color/byte-blob/float32/resource-spec props are editable too.

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

## Editing 3D models (the glTF round-trip)

Haven's 3D models live in `vbuf2` (vertices) + `mesh` (triangles) layers, with
quantised attributes (positions, two UV sets, octahedral normals/tangents, bone
weights), plus optional `skel` (skeleton), `skan` (skeletal animation) and `manim`
(mesh-morph animation). ResForge round-trips the whole thing through **Blender** via
glTF:

1. **Export glTF** — writes a self-contained `.glb` with geometry, both UV sets,
   embedded textures, the skeleton + skinning, and the animations. Open it in Blender
   (or any glTF tool).
2. Edit in Blender.
3. **Rebuild from glTF** — *regenerates* the geometry from the edited `.glb`, so you
   can **reshape/sculpt, re-UV, add, remove or re-topologize** vertices and faces (and
   whole parts). It re-encodes positions/normals/UVs/weights into the original formats,
   recomputes tangents, rebuilds the submeshes/morphs, and **re-poses the skeleton** if
   you moved a bone. An application-modal progress dialog prevents other document
   actions while this runs. It isn't byte-lossless, so verify in-game. Multi-part,
   skinned, morph-animated and normal-mapped models are all supported. Modern mesh
   material/reference metadata is preserved per part; ResForge also recovers older
   merged exports when their original triangle ranges remain unchanged.

Standalone skeletal animations also round-trip: open the animation resource, choose
its bind-skeleton resource and a compatible skinned preview model in the companion
resource dialog, then edit the named `skan_<id>` actions in Blender.
Translation/rotation keyframes can be added,
removed and re-timed. The latest keyframe becomes the new clip duration, allowing
clips without control/effect tracks to be shortened or extended; unchanged key
ranges preserve any original trailing duration. Playback mode, bone scale and
control/effect events are preserved rather than edited, and duration changes are
rejected when effect timing would become ambiguous. Sub-20 ms end-time drift is
treated as Blender frame-grid rounding rather than an intentional duration edit.
For compatible multi-layer animations, editing only the initially active
`skan_combined` action is also supported: ResForge routes each edited bone track
back to its original layer. Editing both the combined and an individual action is
rejected rather than choosing one ambiguously.
Adding/removing morph (`manim`) frames remains unsupported.

**View 3D** also previews `skan` directly. For standalone player animations it uses
the same skeleton/model companion dialog. Each companion can be browsed from disk or
fetched directly from the resource server and retained in memory, then ResForge
CPU-skins the model with controls for
play/pause, stop, speed and timeline scrubbing. Multi-layer resources default to
**All clips**, matching the game's combined body-part pose; individual numbered
components remain selectable for diagnosis. The selected local files or server
paths are remembered across restarts; remembered server companions download lazily
and remain cached for the current session.

## Building / testing

Requires JDK 21. There are three equivalent builds — use whichever you prefer;
all compile the same sources, run the same JUnit 5 tests, and produce the same
runnable fat jar (JOrbis, JNA and FlatLaf folded in). Each writes to its own output
directory so they never clash.

**Gradle** (bundled wrapper, auto-downloads dependencies):

```sh
./gradlew build      # compile + run the tests
./gradlew jar        # -> build-gradle/libs/resforge-1.2.1.jar
```

**Maven** (auto-downloads dependencies + plugins from Maven Central):

```sh
mvn package          # compile + run the tests + build the jar
                     # -> build-maven/resforge-1.2.1.jar
```

**Ant** (for those who prefer it; no internet needed — JUnit jars are vendored in `lib/`):

```sh
ant build            # compile + jar + run the tests  -> build-ant/
ant jar              # -> build-ant/libs/resforge-1.2.1.jar
```

The Ant build has no internet dependency — the JUnit 5 jars live in `lib/`. Gradle
and Maven fetch JUnit (and Maven its plugins) from Maven Central. Outputs go to
`build-gradle/`, `build-maven/` and `build-ant/` respectively, so the three never
clash. Ant requires 1.10+ (for the native JUnit 5 `junitlauncher` task); Maven
output is redirected to `build-maven/` (not the usual `target/`). Point
`JAVA_HOME` at the JDK *root* (not the `\bin` sub-directory) for any of them.

## Command-line interface

The graphical editor is the recommended way to use ResForge, but every operation
is also available on the command line — handy for scripting or batch-modding a
whole folder. Build the jar once, then (optionally) alias it (the jar handles
paths with spaces):

```sh
./gradlew jar
# the jar is at build-gradle/libs/resforge-1.2.1.jar
alias resforge='java -jar build-gradle/libs/resforge-1.2.1.jar'
```

```sh
# Inspect a file, or see what's moddable across a whole folder:
resforge info    horse.res
resforge catalog C:\Haven\res

# List every resource a file references (deps + rlink + code + material links):
resforge refs    horse.res

# Download a resource straight from the game server (by its in-game path):
resforge fetch   gfx/borka/male            # -> male.res
resforge fetch   gfx/borka/male male.res   # choose the output name

# List the resource names in your local game cache (then fetch any of them):
resforge cache-list                        # default: %APPDATA%\Haven and Hearth\data
resforge cache-list "C:\path\to\data"      # a non-default cache folder

# Swap a single asset in one command (the originals are format-checked):
resforge replace horse.res image  newicon.png    horse.res   # 2D icon / sprite
resforge replace horse.res tex    newskin.jpg     horse.res   # 3D model texture
resforge replace theme.res audio2 newsound.ogg    theme.res   # sound
resforge replace ui.res    font   myfont.ttf      ui.res      # UI font
resforge replace menu.res  action newaction.json  menu.res    # keybind (JSON)

# Edit text / typed data: unpack, edit the files, then pack:
resforge unpack horse.res            # -> horse.resdir/ (edit layers/*.txt or *.json)
resforge pack   horse.resdir         # -> horse.res

# Export a 3D model to a Blender-ready binary glTF (UV sets + textures + skeleton, one file):
resforge gltf   horse.res horse.glb

# Rebuild geometry from an edited glTF — reshape/add/remove vertices, weights, morphs,
# skeleton (regenerated, not byte-lossless, so verify in-game):
resforge rebuild-gltf horse.res horse.glb horse-edited.res

# Export standalone skeletal actions with their bind skeleton and a preview mesh:
resforge gltf-skan animaltease.res body.res male.res animaltease.glb

# Import edited Blender actions into the original animation resource:
resforge rebuild-skan animaltease.res animaltease.glb animaltease-edited.res

# Validate round-trip + image splitting for one file or a whole folder:
resforge verify path/to/folder-of-res
```

`replace` is the easy path for re-skinning; pick the layer by name (`image`),
name + occurrence (`tex#2`), or absolute index (`#5`). If the output path is
omitted it overwrites the input in place — written atomically (to a temp file,
then renamed), so an interrupted save can't truncate your only copy — and the
replacement media is
format-checked (PNG/JPEG for images, `OggS` for audio, sfnt for fonts), so a
wrong file type is rejected rather than written. `unpack`/`pack` is the route for
editing text (`tooltip`/`pagina`) and typed JSON (`props`, `action`); as in the
GUI, typed layers are only exposed when they re-encode byte-for-byte, otherwise
they stay raw. Run with no arguments to open the editor instead.

## Extending

New here (or an AI assistant resuming work)? Read **`docs/AI-CONTEXT.md`** first —
a one-file primer on the architecture, builds, feature set, per-layer status and
scope boundaries. **`docs/DESIGN-notes.md`** has the deep format/reverse-engineering log.
For quick lookups there's a tiny local knowledge-base retriever in **`kb/`**:
`java kb/Rag.java "your question"` (BM25 over `kb/notes/` + `docs/` + the source,
no build, no dependencies). Add **`-f`** to print whole chunks — ideal for an AI
that wants complete grounded context in a single call. See `kb/README.md`.

Typed decoders live in `resforge.layers`. To make another layer
human-editable, add a part-splitting rule in `res/Unpacker.java` (and the
inverse is automatic, since `pack` just concatenates parts). The `io`
package mirrors `haven.Message` primitives for decoding payloads.

## Status / scope

v1.0 guarantees lossless unpack/repack for **all** layers and friendly editing
for 2D images (`image`), 3D model textures (`tex`), sounds (`audio2`), fonts
(`font`), typed properties (`props`), action/keybind metadata (`action`) and
materials (`mat2`) as JSON, sprite animations (`anim`: speed + frame sequence) as
JSON, click hitboxes (`neg`: hotspot + connection points) as JSON, movement
collision (`obst`: polygons) as JSON, equip-point placement (`boneoff`: the
translate/rotate opcode program) as JSON, light sources (`light`: colours,
attenuation, direction) as JSON, and text. **3D models** get a full
edit-and-add/remove round-trip through Blender via glTF (see "Editing 3D models"
above): export carries geometry, both UV sets, textures, skeleton, skinning and
animations; **Rebuild from glTF** brings edits back — regenerating geometry so
vertices/faces/parts can be reshaped, added or removed (positions/UVs/weights
re-quantised into the original formats, tangents recomputed, skeleton re-posed, morphs
re-shaped; not byte-lossless, so verify in-game). The
`code`/`codeentry` layers are decoded read-only (class names and the
entrypoint/classpath manifest shown; the embedded Java `.class` can be exported).
A read-only **dependency / reference view** surfaces what other resources a `.res`
points to: the explicit dependency list (`deps`: name + version), resource links
and their decoded specs (`rlink`), and embedded source files (`src`, exportable as
`.java`). The **References…** toolbar button (and the `refs` CLI command) rolls all
of this up into one deduplicated report of every resource a file references —
gathered across `deps`, `rlink`, `code` classpaths and `mat2` material links.
The rig layers `skel` (bone hierarchy), `skan` (skeletal animation:
length, mode, per-bone tracks) and `manim`
(mesh/morph animation: per-frame vertex offsets) have read-only structural views;
`skel` rest poses, `skan` keyframes and fixed-timeline `manim` shapes edit through
the glTF round-trip, while `skan` can also play in the 3D viewer. `boneoff`
(equip-point transforms) and `light` (a light source: colours, attenuation,
direction) are editable as JSON.

### Known limitations (1.0)

These are deliberately out of scope for 1.0 — nothing here risks corrupting a file
(everything not editable stays lossless raw/read-only):

- **Animation metadata and morph timelines.** `skan` keyframes and effect-free clip
  duration round-trip, but playback mode, bone scale and control/effect events are
  preserved rather than edited. Adding/removing `manim` frames remains unsupported.
- **`code`/`codeentry` are read-only.** Class names and the entrypoint/classpath
  manifest are shown and the embedded `.class` can be exported, but client code
  isn't editable in-tool.
- **glTF rebuild is not byte-lossless.** Reshaping/adding/removing geometry
  regenerates the `vbuf2`/`mesh` (re-quantised into the original formats), so
  always verify rebuilt models in-game. A plain export→rebuild with no edits is
  validated as a no-op on the sample models.
- **3D viewer — non-local-textured parts.** Parts whose base texture isn't one of the
  resource's own `tex` layers render shaded by default. For models that have *external
  static* parts (an `mlink`/external `tex` string → one fixed resource, e.g. a tree's
  bark), a **Resolve external textures (network)** toggle fetches the linked resource to
  texture them. Runtime
  *variable materials* and `Dyntex` sprite additions stay shaded (their image isn't in the
  file); a part with a local `otex` overlay over an external base (knarr's hull/sail) shows
  its overlay only (compositing base+overlay is a follow-on). The glTF export/round-trip is
  unaffected.
- **A few layers stay raw by design.** When a typed layer can't be proven to
  re-encode byte-for-byte it is kept as raw bytes (lossless) rather than offered
  as an editor — e.g. a handful of unusual `mat2`/`props` instances and some
  `rlink` link variants. This is the "lossless-or-raw" guarantee working as
  intended, not a failure.

## How this was built ("vibe coded")

ResForge was written entirely by AI coding assistants — **Claude Opus 4.8** and
**GPT 5.6 Sol**, driven through the **GitHub Copilot CLI** — under human
direction, i.e. "vibe coded." The `.res` format was reverse-engineered mainly
from the Haven & Hearth game client, with additional context from **CarryGun's**
(a.k.a. **Kerrigan**)
[**HafenResourceTool**](https://gitlab.com/CarryGun/HafenResourceTool) (used as a
format reference; no code taken) and the server-side **`mkres` Python scripts**
shared by the game's developer (**loftar**) — kept for reference as
[`docs/reference/mkres-fragment.py`](docs/reference/mkres-fragment.py). All the
code, tests and docs were produced by prompting the assistant and validating the
results against real game files (the round-trip oracle in `verify`). Commits
reflect this with a `Co-authored-by: Copilot` trailer and a `Powered by Claude
Opus 4.8` or `Powered by GPT 5.6 Sol` note identifying the model used.

## License

ResForge is open source under the **MIT License** — use it however you like
(modify, redistribute, embed, commercial or not). See [`LICENSE`](LICENSE).

It bundles and references third-party components under their own licenses —
notably the **LGPL-2.0-or-later** [JOrbis](http://www.jcraft.com/jorbis/) Ogg/Vorbis decoder
(shipped standalone as `lib/jorbis-0.0.17.jar` and folded into the fat jar for
convenience), **[JNA](https://github.com/java-native-access/jna)** (dual-licensed
**LGPL-2.1+ / Apache-2.0**, folded in; used only on Windows to show the modern
Explorer file dialog), **[FlatLaf](https://www.formdev.com/flatlaf/)** (Apache-2.0,
folded in for the light/dark Swing themes), and the **LGPL-3** Haven & Hearth client
sources kept for reference under [`docs/reference/`](docs/reference/) (not compiled
into the tool).
Those remain under their respective licenses; see
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). If you redistribute ResForge,
keep those notices intact.
Runnable fat JARs include these texts under `META-INF/licenses/`.

This is a personal, fan-made modding tool and is not affiliated with or endorsed
by the makers of Haven & Hearth.
