# Decisions & conventions

Why the project is shaped the way it is. Each heading is one decision so the RAG
can surface them on their own.

## Parts model
Unpacking a `.res` produces a `manifest.json` plus one file per layer. Every
layer can round-trip even if we don't understand it: unknown layers become
`.bin` blobs, so unpack->pack is byte-identical by construction. Understanding a
layer just means giving it a friendlier on-disk form (`.png`, `.txt`, `.json`).

## Lossless-or-raw principle
A typed editor is only exposed when decode -> typed form -> encode reproduces the
original bytes exactly. If a layer doesn't pass that self-check, it falls back to
raw passthrough. This guarantees we never silently corrupt data the user didn't
intend to change. It's why float16-bearing layers (e.g. `obst`) stay raw, while
`neg` — which turned out to be all int16, exactly reversible — is editable JSON.

## Immutable layers + snapshot undo
Editing replaces a layer object rather than mutating it. That makes GUI undo/redo
cheap: snapshot the layer list before each edit and restore on undo.

## Dual build (Gradle + Ant)
Gradle (`./gradlew`, output `build-gradle/`) and Ant (`ant`, output `build-ant/`)
build the same sources. Ant's default target is `jar` (no tests); `ant build`
runs tests too (needs Ant 1.10+ for `junitlauncher`); `ant gui` launches the app.
Both produce a fat jar that bundles JOrbis.

## JOrbis dependency
The only runtime dependency, used solely by the GUI's Ogg player. Maven coords
are `org.jcraft:jorbis:0.0.17` (LGPL, ~97KB) — note `org.jcraft`, not
`com.jcraft`. The one jar contains both the `jogg` and `jorbis` packages. The CLI
never touches it.

## Copyrighted samples stay out of git
Real game `.res` files are copyrighted. They live in a gitignored `samples/`
folder and are never committed. Validation runs locally; CI stays green without
the assets.

## Environment gotcha: JAVA_HOME
The machine's `JAVA_HOME` wrongly includes `\bin`. Gradle/Ant need the JDK *root*:
`C:\Program Files\Java\graalvm-jdk-21.0.9+7.1`. Set it at the top of each build
command. (`python` on this box is the Microsoft Store stub, not a real Python.)

## transform write path is the one unverified feature
`transform <file> <sx> <sy> <sz>` scales vbuf2 positions. The encoder is proven
byte-identical on a decode->encode round-trip of all real vbuf2 layers, but the
*scaled* output still needs an in-game visual check (a uniform scale like `2 2 2`
should render correct but bigger). Normals are left untouched (correct for
uniform scale).
