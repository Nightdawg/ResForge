# Reference sources

These files are **copied verbatim for reference** while developing this tool.
They are not compiled as part of `ResForge`; they document the authoritative
behaviour of the `.res` format.

| File | Origin | Purpose |
|------|--------|---------|
| `Resource.java` | `hafen-client/src/haven/Resource.java` | Container `load()` + every layer decoder |
| `Message.java` | `hafen-client/src/haven/Message.java` | Primitive encodings (the binary "alphabet") |
| `NormNumber.java` | `hafen-client/src/haven/NormNumber.java` | snorm/unorm/mnorm fixed-point primitives |
| `TexR.java` | `hafen-client/src/haven/TexR.java` | `tex` layer decoder (3D-model textures) |
| `VertexBuf.java` | `hafen-client/src/haven/VertexBuf.java` | `vbuf2` layer decoder (3D vertex data) |
| `Skeleton.java` | `hafen-client/src/haven/Skeleton.java` | `skel`/`skan`/`boneoff` layer decoders (bones, animation, equip points) |
| `Light.java` | `hafen-client/src/haven/Light.java` | `light` layer decoder (light sources) |
| `mkres-fragment.py` | shared by the game developer (`hjTpfMwy.py`) | Encoder side of `mkres` (mostly 3D meshes) |

## Licensing

`Resource.java`, `Message.java`, `NormNumber.java`, `TexR.java`,
`VertexBuf.java`, `Skeleton.java`, and `Light.java` are part of the Haven & Hearth
client and are distributed under the **GNU Lesser General Public License, version
3** (see the header comment in each file). They are reproduced here under those
terms for interoperability/reference. If you redistribute this project, keep these
notices intact; consult the upstream `COPYING`/`doc/LPGL-3` in `hafen-client` for
the full license text.

`mkres-fragment.py` was provided by the game's developer as an example of how
`.res` files are built. Treat its licensing as belonging to the upstream author;
it is included here only as documentation of the encoder side.

## Note

These are point-in-time snapshots. If the upstream client changes the format,
re-copy the current versions and update `../DESIGN-notes.md` accordingly.

See also the top-level [`../../THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md)
for how these reference files (and the bundled LGPL JOrbis decoder) relate to
ResForge's own MIT license.
