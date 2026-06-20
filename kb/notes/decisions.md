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

## Triple build (Gradle + Maven + Ant)
Gradle (`./gradlew`, output `build-gradle/`), Maven (`mvn package`, output
`build-maven/`) and Ant (`ant`, output `build-ant/`) all build the same sources,
run the same JUnit 5 tests, and produce the same runnable fat jar (JOrbis folded
in). Each writes to its own directory so they never clash. Ant's default target
is `jar` (no tests); `ant build` runs tests too (needs Ant 1.10+ for
`junitlauncher`); `ant gui` launches the app. Gradle and Maven fetch JUnit (and
Maven its plugins) from Maven Central; Ant uses the vendored `lib/` jars and needs
no network. Maven's output is redirected from the usual `target/` to `build-maven/`
via `<build><directory>`, and its fat jar is built by the shade plugin (with
`createDependencyReducedPom=false` to avoid a stray pom). Three builds means a
dep/version change must be applied to all three (`build.gradle`, `pom.xml`,
`build.xml`/`lib/`).

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
Gradle and Maven require `JAVA_HOME` to point at the **JDK root** (the folder that
*contains* `bin`), not the `\bin` sub-directory — a `\bin` JAVA_HOME is rejected.
This is generic JDK-21 guidance (any vendor's JDK works); machine-specific setup
for a particular dev box doesn't belong in the repo.

## transform write path is the one unverified feature
`transform <file> <sx> <sy> <sz>` scales vbuf2 positions. The encoder is proven
byte-identical on a decode->encode round-trip of all real vbuf2 layers, but the
*scaled* output still needs an in-game visual check (a uniform scale like `2 2 2`
should render correct but bigger). Normals are left untouched (correct for
uniform scale).
