# Third-party notices

ResForge itself is released under the MIT License (see [`LICENSE`](LICENSE)).
It also includes and references third-party material that is licensed
**separately**, under its own terms. Those components are **not** relicensed by
ResForge's MIT license — they remain under the licenses described below. If you
redistribute ResForge (source or the built jar), keep these notices intact.

---

## JOrbis (bundled — runtime dependency)

- **Component:** JOrbis — a pure-Java Ogg/Vorbis decoder.
- **Author:** JCraft, Inc. (and contributors).
- **Homepage / source:** http://www.jcraft.com/jorbis/
- **License:** GNU Lesser General Public License (**LGPL**).
- **Used for:** decoding the embedded Ogg Vorbis audio in `audio2` layers so the
  GUI can play sounds. Only the core decoder packages are used
  (`com.jcraft.jogg.*`, `com.jcraft.jorbis.*`). The separate, GPL-licensed
  *JOrbisPlayer* example application is **not** used.

**How it is included.** The decoder ships as a standalone jar at
[`lib/jorbis-0.0.17.jar`](lib/jorbis-0.0.17.jar). The Gradle/Maven/Ant builds fold
those classes into the distributed "fat" jar purely for convenience, so
`java -jar resforge-…​.jar` works without a separate classpath entry.

**Replaceability (LGPL).** JOrbis is an unmodified, self-contained library and is
not statically linked into ResForge's own classes. You may replace it with your
own build of JOrbis: drop a different `jorbis` jar in `lib/` (or on the classpath)
and rebuild. ResForge's source under MIT does not depend on any modification to
JOrbis.

If you redistribute the fat jar, you are redistributing JOrbis under the LGPL;
keep this notice and the upstream license available, and point recipients at the
JOrbis source above.

---

## JNA — Java Native Access (bundled — runtime dependency, Windows only)

- **Component:** JNA (`net.java.dev.jna:jna` and `net.java.dev.jna:jna-platform`)
  — a library for calling native code from Java without writing JNI.
- **Author:** Timothy Wall and the JNA contributors.
- **Homepage / source:** https://github.com/java-native-access/jna
- **License:** dual-licensed, **your choice** of the GNU Lesser General Public
  License, version 2.1 or later (**LGPL-2.1+**) **or** the **Apache License, 2.0**.
- **Used for:** showing the *modern* Windows Explorer file open/save dialog (the
  Common Item Dialog, with the editable address bar) by driving the COM
  `IFileOpenDialog`/`IFileSaveDialog` interfaces (`gui/WinFileDialogs`). It is only
  invoked on Windows; on other platforms ResForge falls back to `java.awt.FileDialog`
  and the JNA code path is never entered.

**How it is included.** The two jars ship standalone at
[`lib/jna-5.15.0.jar`](lib/jna-5.15.0.jar) and
[`lib/jna-platform-5.15.0.jar`](lib/jna-platform-5.15.0.jar). The Gradle/Maven/Ant
builds fold their classes (and JNA's own bundled native `jnidispatch` stubs for
every platform) into the distributed "fat" jar for convenience, so
`java -jar resforge-…​.jar` works without a separate classpath entry.

**Replaceability (LGPL option).** JNA is an unmodified, self-contained library and
is not statically linked into ResForge's own classes (ResForge calls only its public
API). You may replace it with your own build: drop different `jna` / `jna-platform`
jars in `lib/` (or on the classpath) and rebuild.

If you redistribute the fat jar you are redistributing JNA; keep this notice and the
upstream license(s) available, and point recipients at the JNA source above.

---

## FlatLaf (bundled — runtime dependency)

- **Component:** FlatLaf (`com.formdev:flatlaf`) — a modern flat Look&Feel for Java
  Swing, providing ResForge's light and dark GUI themes.
- **Author:** FormDev Software GmbH and the FlatLaf contributors.
- **Homepage / source:** https://www.formdev.com/flatlaf/ — https://github.com/JFormDesigner/FlatLaf
- **License:** Apache License, version 2.0 (**Apache-2.0**).
- **Used for:** the GUI's Look&Feel and the **Options → Dark mode** toggle
  (`gui/Theme`). ResForge calls only its public API (`FlatLightLaf` / `FlatDarkLaf`).

**How it is included.** The library ships as a standalone jar at
[`lib/flatlaf-3.7.2.jar`](lib/flatlaf-3.7.2.jar). The Gradle/Maven/Ant builds fold
its classes (and FlatLaf's own bundled native window-decoration stubs) into the
distributed "fat" jar for convenience, so `java -jar resforge-…​.jar` works without
a separate classpath entry.

**Replaceability.** FlatLaf is an unmodified, self-contained library and is not
statically linked into ResForge's own classes. You may replace it with your own
build: drop a different `flatlaf` jar in `lib/` (or on the classpath) and rebuild.

If you redistribute the fat jar you are redistributing FlatLaf; keep this notice and
the upstream Apache-2.0 license available, and point recipients at the FlatLaf source
above.

---

## Haven & Hearth client reference sources (reference only — not compiled)

The files under [`docs/reference/`](docs/reference/) are copied **verbatim for
reference** while developing this tool. They are **not** compiled into ResForge
and are **not** part of the distributed jar; they document the authoritative
behaviour of the `.res` format.

- `Resource.java`, `Message.java`, `NormNumber.java`, `TexR.java`,
  `VertexBuf.java`, `Skeleton.java`, `Light.java`, `MeshAnim.java` — part of the
  Haven & Hearth client, distributed under the **GNU Lesser General Public License,
  version 3 (LGPL-3)** (see the header comment in each file). Reproduced here under
  those terms for interoperability/reference. If you redistribute them, keep the
  notices intact and consult the upstream `COPYING` / `doc/LGPL-3` in the client for
  the full license text.
- `mkres-fragment.py` — provided by the game's developer (**loftar**) as an
  example of how `.res` files are built. Treat its licensing as belonging to the
  upstream author; it is included only as documentation of the encoder side.

See [`docs/reference/README.md`](docs/reference/README.md) for details and origins.

---

## Format knowledge (no code taken)

The `.res` format was reverse-engineered primarily from the Haven & Hearth client
(the LGPL-3 sources above) with additional context from CarryGun's (a.k.a.
Kerrigan's) [HafenResourceTool](https://gitlab.com/CarryGun/HafenResourceTool),
used as a format reference only — **no source code was copied** from it.
