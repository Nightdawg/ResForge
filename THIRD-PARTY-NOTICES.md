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

## Haven & Hearth client reference sources (reference only — not compiled)

The files under [`docs/reference/`](docs/reference/) are copied **verbatim for
reference** while developing this tool. They are **not** compiled into ResForge
and are **not** part of the distributed jar; they document the authoritative
behaviour of the `.res` format.

- `Resource.java`, `Message.java`, `NormNumber.java`, `TexR.java`,
  `VertexBuf.java`, `Skeleton.java`, `Light.java` — part of the Haven & Hearth
  client, distributed under the **GNU Lesser General Public License, version 3
  (LGPL-3)** (see the header comment in each file). Reproduced here under those
  terms for interoperability/reference. If you redistribute them, keep the notices
  intact and consult the upstream `COPYING` / `doc/LGPL-3` in the client for the
  full license text.
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
