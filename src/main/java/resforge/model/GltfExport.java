package resforge.model;

import resforge.io.Json;
import resforge.layers.Mat2Codec;
import resforge.layers.MeshAnimInfo;
import resforge.layers.MeshInfo;
import resforge.layers.SkanInfo;
import resforge.layers.SkelInfo;
import resforge.layers.TexInfo;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a resource's 3D geometry ({@code vbuf2} vertex buffers + {@code mesh}
 * triangle lists) to a binary glTF 2.0 file ({@code .glb}) — a modern, JSON-based
 * format with native import/export support in Blender. Unlike the OBJ export, glTF
 * carries <em>both</em> of Haven's texture-coordinate sets ({@code tex} →
 * {@code TEXCOORD_0}, {@code otex} → {@code TEXCOORD_1}) and is the basis for the
 * eventual round-trip (skeleton, animation and morph support to follow).
 *
 * <p>This first stage exports static, textured geometry: positions, normals and
 * the two UV sets per vertex buffer, one primitive per {@code mesh} submesh, PBR
 * materials referencing the resource's own {@code tex} layers (embedded in the
 * {@code .glb}), and the {@code mesh.matid → mat2 → local tex} mapping reused from
 * the OBJ exporter. Coordinates are converted from Haven's Z-up to glTF's Y-up.
 * The {@code .glb} is fully self-contained (geometry + textures in one file).
 */
public final class GltfExport {
    private static final float STATIC_EDIT_DURATION = 1f;

    public static final class Result {
        public final byte[] glb;
        public final int vertices, triangles, submeshes, textures;

        Result(byte[] glb, int vertices, int triangles, int submeshes, int textures) {
            this.glb = glb;
            this.vertices = vertices;
            this.triangles = triangles;
            this.submeshes = submeshes;
            this.textures = textures;
        }
    }

    private static final int FLOAT = 5126, USHORT = 5123;
    private static final int ARRAY_BUFFER = 34962, ELEMENT_ARRAY_BUFFER = 34963;

    private static final class TexMat {
        final String matName;
        final byte[] image;
        final String mime;

        TexMat(String matName, byte[] image, String mime) {
            this.matName = matName;
            this.image = image;
            this.mime = mime;
        }
    }

    /** A growing little-endian binary buffer with 4-byte alignment helpers. */
    private static final class Buf {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        int size() {
            return o.size();
        }

        void align4() {
            while((o.size() & 3) != 0)
                o.write(0);
        }

        void f32(float v) {
            int b = Float.floatToIntBits(v);
            o.write(b & 0xff);
            o.write((b >>> 8) & 0xff);
            o.write((b >>> 16) & 0xff);
            o.write((b >>> 24) & 0xff);
        }

        void u16(int v) {
            o.write(v & 0xff);
            o.write((v >>> 8) & 0xff);
        }

        void bytes(byte[] b) {
            o.writeBytes(b);
        }

        byte[] toByteArray() {
            return o.toByteArray();
        }
    }

    public static Result toGlb(ResContainer res, String sourceName) {
        return toGlb(res, res, res, sourceName);
    }

    /**
     * Exports a composite assembled the same way as a runtime character: geometry
     * and materials from {@code modelRes}, the bind pose from {@code skeletonRes},
     * and animation clips from {@code animationRes}.
     */
    public static Result toGlb(ResContainer modelRes, ResContainer skeletonRes,
                               ResContainer animationRes, String sourceName) {
        Map<Integer, Vbuf2Data> vbufs = new LinkedHashMap<>();
        for(Layer l : modelRes.layers)
            if(l.name.equals("vbuf2")) {
                Vbuf2Data d = Vbuf2Data.parse(l.data);
                if(d != null)
                    vbufs.putIfAbsent(d.id, d);
            }
        List<MeshInfo> meshes = new ArrayList<>();
        for(Layer l : modelRes.layers)
            if(l.name.equals("mesh")) {
                MeshInfo mi = MeshInfo.parse(l.data);
                if(mi.recognized && mi.indices != null)
                    meshes.add(mi);
            }

        List<TexMat> texMats = collectTextures(modelRes);
        Map<Integer, Integer> texIds = collectTexIds(modelRes);
        Map<Integer, Integer> matToTex = collectMatToTex(modelRes, texIds);

        // Modern mesh metadata can distinguish layers that share a matid (notably
        // alternate "ref" parts). Give each modern layer its own stable material
        // identity so Blender does not merge those headers away.
        List<MeshMaterial> gltfMaterials = new ArrayList<>();
        List<Integer> meshMaterials = new ArrayList<>();
        Map<Integer, Integer> legacyMaterials = new LinkedHashMap<>();
        for(int i = 0; i < meshes.size(); i++) {
            MeshInfo m = meshes.get(i);
            int material;
            if(m.modern) {
                material = gltfMaterials.size();
                gltfMaterials.add(new MeshMaterial(m.matid, "rfmat_" + m.matid + "_mesh_" + i));
            } else {
                Integer existing = legacyMaterials.get(m.matid);
                if(existing == null) {
                    existing = gltfMaterials.size();
                    legacyMaterials.put(m.matid, existing);
                    gltfMaterials.add(new MeshMaterial(m.matid, "rfmat_" + m.matid));
                }
                material = existing;
            }
            meshMaterials.add(material);
        }

        // Unified joint list = the union of every bone-bearing vbuf's influence
        // bones (JOINTS_0 is later remapped from a vbuf's local order to this).
        List<String> joints = new ArrayList<>();
        for(Vbuf2Data d : vbufs.values())
            if(d.boneNames != null)
                for(String n : d.boneNames)
                    if(!joints.contains(n))
                        joints.add(n);
        SkelInfo skel = firstSkel(skeletonRes);               // bind skeleton, or null
        boolean composite = modelRes != animationRes || skeletonRes != animationRes;
        if(composite && (skel == null || !skel.recognized || skel.bones.isEmpty()))
            throw new IllegalArgumentException("the selected skeleton resource has no decoded skel layer");
        Map<String, float[]> boneWorld = skeletonWorld(skel); // bone name -> native bind world matrix

        Buf bin = new Buf();
        List<Object> bufferViews = new ArrayList<>();
        List<Object> accessors = new ArrayList<>();

        // Per vertex-buffer: the attribute accessor indices shared by its submeshes.
        Map<Integer, Map<String, Object>> vbufAttribs = new LinkedHashMap<>();
        int vertices = 0;
        for(Map.Entry<Integer, Vbuf2Data> e : vbufs.entrySet()) {
            Vbuf2Data d = e.getValue();
            float[] pos = d.get("pos");
            if(pos == null)
                continue;
            Map<String, Object> attribs = new LinkedHashMap<>();
            attribs.put("POSITION", addVec3(bin, bufferViews, accessors, pos, d.num, true, true));
            float[] nrm = d.get("nrm");
            if(nrm != null)
                attribs.put("NORMAL", addVec3(bin, bufferViews, accessors, nrm, d.num, true, false));
            float[] tex = d.get("tex");
            if(tex != null)
                attribs.put("TEXCOORD_0", addVec2(bin, bufferViews, accessors, tex, d.num));
            float[] otex = d.get("otex");
            if(otex != null)
                attribs.put("TEXCOORD_1", addVec2(bin, bufferViews, accessors, otex, d.num));
            if(d.boneNames != null) {
                attribs.put("JOINTS_0", addJoints(bin, bufferViews, accessors, d, joints));
                attribs.put("WEIGHTS_0", addWeights(bin, bufferViews, accessors, d));
            }
            vbufAttribs.put(e.getKey(), attribs);
            vertices += d.num;
        }

        // manim layers -> glTF morph targets (per vbuf). A frame's positions are
        // deltas added to the base and interpolated linearly between frames, which
        // is exactly glTF morph-target semantics. (Samples have one vbuf; this is
        // handled for the single-vbuf case, the only one that occurs.)
        Map<Integer, List<Object>> vbufTargets = new LinkedHashMap<>();
        List<float[]> morphTimes = new ArrayList<>();   // per manim: keyframe times (+ loop close)
        List<int[]> morphSlots = new ArrayList<>();      // per manim: {baseSlot, frameCount}
        if(vbufs.size() == 1) {
            int vbufid = vbufs.keySet().iterator().next();
            Vbuf2Data d = vbufs.get(vbufid);
            List<MeshAnimInfo> manims = new ArrayList<>();
            for(Layer l : modelRes.layers)
                if(l.name.equals("manim")) {
                    MeshAnimInfo ma = MeshAnimInfo.parse(l.data);
                    if(ma.recognized)
                        manims.add(ma);
                }
            if(d != null && d.get("pos") != null && !manims.isEmpty()) {
                List<Object> targets = new ArrayList<>();
                for(MeshAnimInfo ma : manims) {
                    int base = targets.size();
                    for(MeshAnimInfo.Frame f : ma.frames) {
                        float[] delta = new float[d.num * 3];
                        if(f.pos != null && f.idx != null)
                            for(int k = 0; k < f.idx.length; k++) {
                                int v = f.idx[k];
                                if(v < 0 || v >= d.num)
                                    continue;
                                delta[v * 3] = f.pos[k * 3];          // Haven Z-up -> glTF Y-up
                                delta[v * 3 + 1] = f.pos[k * 3 + 2];  // (the same linear convert
                                delta[v * 3 + 2] = -f.pos[k * 3 + 1]; //  applied to positions)
                            }
                        targets.add(obj("POSITION", addVec3(bin, bufferViews, accessors, delta, d.num, false, true)));
                    }
                    float[] times = new float[ma.frames.size() + 1];
                    for(int fi = 0; fi < ma.frames.size(); fi++)
                        times[fi] = ma.frames.get(fi).time;
                    times[ma.frames.size()] = ma.len;     // loop close back to frame 0
                    morphTimes.add(times);
                    morphSlots.add(new int[]{base, ma.frames.size()});
                }
                if(!targets.isEmpty())
                    vbufTargets.put(vbufid, targets);
            }
        }

        List<Object> primitives = new ArrayList<>();
        int triangles = 0, submeshes = 0;
        for(int meshIndex = 0; meshIndex < meshes.size(); meshIndex++) {
            MeshInfo m = meshes.get(meshIndex);
            Map<String, Object> attribs = vbufAttribs.get(m.vbufid);
            if(attribs == null)
                continue;
            submeshes++;
            int idxAccessor = addIndices(bin, bufferViews, accessors, m.indices);
            Map<String, Object> prim = new LinkedHashMap<>();
            prim.put("attributes", new LinkedHashMap<>(attribs));
            prim.put("indices", idxAccessor);
            prim.put("material", meshMaterials.get(meshIndex));
            List<Object> targets = vbufTargets.get(m.vbufid);
            if(targets != null && !targets.isEmpty())
                prim.put("targets", targets);
            primitives.add(prim);
            triangles += m.indices.length / 3;
        }
        int morphCount = 0;
        for(List<Object> t : vbufTargets.values())
            morphCount = Math.max(morphCount, t.size());

        if(primitives.isEmpty()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("asset", obj("version", "2.0", "generator", "ResForge"));
            root.put("scene", 0);
            root.put("scenes", List.of(new LinkedHashMap<>()));
            byte[] glb = assembleGlb(Json.write(root), new byte[0]);
            return new Result(glb, vertices, triangles, submeshes, 0);
        }

        String base = baseName(sourceName);
        List<Object> nodes = new ArrayList<>();
        List<Object> sceneNodes = new ArrayList<>();

        // node 0 = the (optionally skinned) mesh
        Map<String, Object> meshNode = new LinkedHashMap<>();
        meshNode.put("mesh", 0);
        meshNode.put("name", base);
        nodes.add(meshNode);
        sceneNodes.add(0);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("asset", obj("version", "2.0", "generator", "ResForge"));
        root.put("scene", 0);

        Map<String, Integer> boneNode = new LinkedHashMap<>();   // bone name -> glTF node index
        if(!joints.isEmpty()) {
            // Connected joint hierarchy: each skel bone is a node with its native
            // local transform, parented per the skeleton, all under a conversion
            // ROOT node that rotates Haven Z-up -> glTF Y-up. A bone's glTF global
            // transform is therefore G = R · nativeWorld, and IBM = G^-1, so at bind
            // pose G·IBM = I (the inline-converted geometry shows undeformed).
            float[] R = M4.fromQuat(0.70710677f, -0.70710677f, 0, 0);
            List<Object> rxyzw = List.of(-0.70710677, 0.0, 0.0, 0.70710677);   // R as [x,y,z,w]

            if(skel != null && skel.recognized) {
                for(SkelInfo.Bone b : skel.bones) {
                    float[] q = M4.quat(b.ax, b.ay, b.az, b.ang);       // [w,x,y,z]
                    Map<String, Object> jn = new LinkedHashMap<>();
                    jn.put("name", b.name);
                    jn.put("translation", List.of((double) b.px, (double) b.py, (double) b.pz));
                    jn.put("rotation", List.of((double) q[1], (double) q[2], (double) q[3], (double) q[0]));
                    boneNode.put(b.name, nodes.size());
                    nodes.add(jn);
                }
                List<Object> rootBones = new ArrayList<>();
                Map<Integer, List<Object>> childrenOf = new LinkedHashMap<>();
                for(SkelInfo.Bone b : skel.bones) {
                    int ni = boneNode.get(b.name);
                    if(b.parent.isEmpty() || !boneNode.containsKey(b.parent))
                        rootBones.add(ni);
                    else
                        childrenOf.computeIfAbsent(boneNode.get(b.parent), k -> new ArrayList<>()).add(ni);
                }
                for(Map.Entry<Integer, List<Object>> e : childrenOf.entrySet())
                    ((Map<String, Object>) nodes.get(e.getKey())).put("children", e.getValue());

                Map<String, Object> rootNode = new LinkedHashMap<>();
                rootNode.put("name", "ROOT");
                rootNode.put("rotation", rxyzw);
                rootNode.put("children", rootBones);
                sceneNodes.add(nodes.size());
                nodes.add(rootNode);
            }

            // skin.joints in unified influence order; external bones (no local skel
            // entry) get a flat identity node so weights/groups still transfer.
            List<Object> jointIndices = new ArrayList<>();
            float[] ibm = new float[joints.size() * 16];
            for(int j = 0; j < joints.size(); j++) {
                String name = joints.get(j);
                Integer ni = boneNode.get(name);
                float[] inv;
                if(ni != null && boneWorld.containsKey(name)) {
                    inv = M4.rigidInverse(M4.mul(R, boneWorld.get(name)));
                    jointIndices.add(ni);
                } else {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", name);
                    int fi = nodes.size();
                    nodes.add(fn);
                    sceneNodes.add(fi);
                    jointIndices.add(fi);
                    inv = M4.identity();
                }
                System.arraycopy(inv, 0, ibm, j * 16, 16);
            }
            int ibmAccessor = addMat4(bin, bufferViews, accessors, ibm, joints.size());
            meshNode.put("skin", 0);
            root.put("skins", List.of(obj(
                    "inverseBindMatrices", ibmAccessor,
                    "joints", jointIndices)));
        }

        // skan layers -> glTF animations (only for bones in the local skeleton).
        List<Object> animations = buildAnimations(animationRes, skel, boneNode,
                bin, bufferViews, accessors, composite);
        if(composite && animations.isEmpty())
            throw new IllegalArgumentException("the animation resource has no usable skan tracks");
        // manim morph weight animations (one per manim) targeting the mesh node's weights.
        for(int mi = 0; mi < morphSlots.size(); mi++) {
            int slotBase = morphSlots.get(mi)[0], cnt = morphSlots.get(mi)[1];
            float[] times = morphTimes.get(mi);
            float[] out = new float[times.length * morphCount];
            for(int ki = 0; ki < times.length; ki++) {
                int active = (ki < cnt) ? slotBase + ki : slotBase;   // last keyframe loops back to frame 0
                out[ki * morphCount + active] = 1f;
            }
            int inAcc = addScalar(bin, bufferViews, accessors, times);
            int outAcc = addScalarN(bin, bufferViews, accessors, out);
            Map<String, Object> sampler = obj("input", inAcc, "output", outAcc, "interpolation", "LINEAR");
            Map<String, Object> channel = obj("sampler", 0,
                    "target", obj("node", 0, "path", "weights"));
            animations.add(obj("name", "morph_" + mi, "samplers", List.of(sampler),
                    "channels", List.of(channel)));
        }
        if(!animations.isEmpty())
            root.put("animations", animations);

        root.put("scenes", List.of(obj("nodes", sceneNodes)));
        root.put("nodes", nodes);
        Map<String, Object> meshObj = obj("name", base, "primitives", primitives);
        if(morphCount > 0) {
            List<Object> weights = new ArrayList<>();
            for(int k = 0; k < morphCount; k++)
                weights.add(0.0);
            meshObj.put("weights", weights);
        }
        root.put("meshes", List.of(meshObj));

        if(!texMats.isEmpty()) {
            List<Object> images = new ArrayList<>();
            List<Object> textures = new ArrayList<>();
            for(int i = 0; i < texMats.size(); i++) {
                TexMat tm = texMats.get(i);
                int bv = addImage(bin, bufferViews, tm.image);
                images.add(obj("bufferView", bv, "mimeType", tm.mime));
                textures.add(obj("source", i, "sampler", 0));
            }
            root.put("textures", textures);
            root.put("images", images);
            root.put("samplers", List.of(obj("wrapS", 10497, "wrapT", 10497)));
        }
        List<Object> materials = new ArrayList<>();
        for(MeshMaterial gm : gltfMaterials) {
            Map<String, Object> pbr = new LinkedHashMap<>();
            Integer texOrd = matToTex.get(gm.matid);
            if(texOrd != null && texOrd >= 0 && texOrd < texMats.size())
                pbr.put("baseColorTexture", obj("index", texOrd, "texCoord", 0));
            pbr.put("metallicFactor", 0.0);
            pbr.put("roughnessFactor", 1.0);
            Map<String, Object> mat = new LinkedHashMap<>();
            mat.put("name", gm.name);
            mat.put("doubleSided", Boolean.TRUE);
            mat.put("alphaMode", "MASK");
            mat.put("alphaCutoff", 0.5);
            mat.put("pbrMetallicRoughness", pbr);
            materials.add(mat);
        }
        root.put("materials", materials);

        root.put("accessors", accessors);
        root.put("bufferViews", bufferViews);
        root.put("buffers", List.of(obj("byteLength", bin.size())));

        byte[] glb = assembleGlb(Json.write(root), bin.toByteArray());
        return new Result(glb, vertices, triangles, submeshes, texMats.size());
    }

    private static final class MeshMaterial {
        final int matid;
        final String name;

        MeshMaterial(int matid, String name) {
            this.matid = matid;
            this.name = name;
        }
    }

    /* ------------------------------------------------------- accessors/buffers */

    private static Integer addVec3(Buf bin, List<Object> bvs, List<Object> accs,
                                   float[] data, int num, boolean convert, boolean minmax) {
        bin.align4();
        int off = bin.size();
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        float[] c = new float[3];
        for(int i = 0; i < num; i++) {
            float x = data[i * 3], y = data[i * 3 + 1], z = data[i * 3 + 2];
            if(convert) {
                c[0] = x; c[1] = z; c[2] = -y;          // Haven Z-up -> glTF Y-up
            } else {
                c[0] = x; c[1] = y; c[2] = z;
            }
            for(int k = 0; k < 3; k++) {
                bin.f32(c[k]);
                if(c[k] < mn[k]) mn[k] = c[k];
                if(c[k] > mx[k]) mx[k] = c[k];
            }
        }
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", num);
        acc.put("type", "VEC3");
        if(minmax) {
            acc.put("min", List.of(mn[0], mn[1], mn[2]));
            acc.put("max", List.of(mx[0], mx[1], mx[2]));
        }
        accs.add(acc);
        return accs.size() - 1;
    }

    private static Integer addVec2(Buf bin, List<Object> bvs, List<Object> accs, float[] data, int num) {
        bin.align4();
        int off = bin.size();
        for(int i = 0; i < num; i++) {
            bin.f32(data[i * 2]);
            bin.f32(data[i * 2 + 1]);
        }
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", num);
        acc.put("type", "VEC2");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static Integer addIndices(Buf bin, List<Object> bvs, List<Object> accs, short[] indices) {
        bin.align4();
        int off = bin.size();
        for(short s : indices)
            bin.u16(s & 0xffff);
        int bv = addBufferView(bvs, off, bin.size() - off, ELEMENT_ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", USHORT);
        acc.put("count", indices.length);
        acc.put("type", "SCALAR");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static int addImage(Buf bin, List<Object> bvs, byte[] image) {
        bin.align4();
        int off = bin.size();
        bin.bytes(image);
        return addBufferView(bvs, off, bin.size() - off, -1);
    }

    private static Integer addJoints(Buf bin, List<Object> bvs, List<Object> accs,
                                     Vbuf2Data d, List<String> joints) {
        int[] local2unified = new int[d.boneNames.length];
        for(int i = 0; i < d.boneNames.length; i++)
            local2unified[i] = Math.max(0, joints.indexOf(d.boneNames[i]));
        bin.align4();
        int off = bin.size();
        for(int v = 0; v < d.num; v++)
            for(int k = 0; k < 4; k++) {
                int local = d.vJoints[v * 4 + k];
                bin.u16(local2unified[local]);
            }
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", USHORT);
        acc.put("count", d.num);
        acc.put("type", "VEC4");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static Integer addWeights(Buf bin, List<Object> bvs, List<Object> accs, Vbuf2Data d) {
        bin.align4();
        int off = bin.size();
        for(int v = 0; v < d.num; v++)
            for(int k = 0; k < 4; k++)
                bin.f32(d.vWeights[v * 4 + k]);
        int bv = addBufferView(bvs, off, bin.size() - off, ARRAY_BUFFER);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", d.num);
        acc.put("type", "VEC4");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static int addMat4(Buf bin, List<Object> bvs, List<Object> accs, float[] data, int count) {
        bin.align4();
        int off = bin.size();
        for(float v : data)
            bin.f32(v);
        int bv = addBufferView(bvs, off, bin.size() - off, -1);   // IBM: not a vertex/index target
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", count);
        acc.put("type", "MAT4");
        accs.add(acc);
        return accs.size() - 1;
    }

    /**
     * Computes each bone's native (Haven-space) bind world matrix from a {@code
     * skel} layer (or an empty map if there is no local skeleton — then bones live
     * in another resource and joints stay identity-placed).
     */
    private static Map<String, float[]> skeletonWorld(SkelInfo skel) {
        Map<String, float[]> world = new LinkedHashMap<>();
        if(skel == null || !skel.recognized)
            return world;

        Map<String, SkelInfo.Bone> byName = new LinkedHashMap<>();
        for(SkelInfo.Bone b : skel.bones)
            byName.put(b.name, b);

        // Resolve parents-before-children (a bone's world needs its parent's).
        boolean progress = true;
        while(world.size() < byName.size() && progress) {
            progress = false;
            for(SkelInfo.Bone b : skel.bones) {
                if(world.containsKey(b.name))
                    continue;
                float[] q = M4.quat(b.ax, b.ay, b.az, b.ang);
                float[] local = M4.mul(M4.translate(b.px, b.py, b.pz),
                        M4.fromQuat(q[0], q[1], q[2], q[3]));
                if(b.parent.isEmpty()) {
                    world.put(b.name, local);
                    progress = true;
                } else if(world.containsKey(b.parent)) {
                    world.put(b.name, M4.mul(world.get(b.parent), local));
                    progress = true;
                }
            }
        }
        return world;
    }

    private static SkelInfo firstSkel(ResContainer res) {
        for(Layer l : res.layers)
            if(l.name.equals("skel"))
                return SkelInfo.parse(l.data);
        return null;
    }

    /**
     * Builds glTF animations from the resource's {@code skan} layers. Each skan
     * becomes one animation; each of its per-bone tracks becomes a translation and
     * a rotation channel targeting that bone's joint node, with values composed
     * onto the bind pose exactly as the client does (translation added, rotation
     * post-multiplied). Tracks for bones not in the local skeleton are skipped.
     */
    private static List<Object> buildAnimations(ResContainer res, SkelInfo skel,
                                                Map<String, Integer> boneNode,
                                                Buf bin, List<Object> bvs, List<Object> accs,
                                                boolean requireAllBones) {
        List<Object> animations = new ArrayList<>();
        if(skel == null || !skel.recognized || boneNode.isEmpty())
            return animations;
        Map<String, SkelInfo.Bone> bind = new LinkedHashMap<>();
        for(SkelInfo.Bone b : skel.bones)
            bind.put(b.name, b);

        List<SkanInfo> clips = new ArrayList<>();
        for(Layer l : res.layers) {
            if(!l.name.equals("skan"))
                continue;
            SkanInfo sa = SkanInfo.parse(l.data);
            if(!sa.recognized)
                continue;
            clips.add(sa);
        }
        Map<Integer, Integer> idCounts = new LinkedHashMap<>();
        for(SkanInfo clip : clips)
            idCounts.merge(clip.id, 1, Integer::sum);
        for(int layer = 0; layer < clips.size(); layer++) {
            SkanInfo sa = clips.get(layer);
            String name = "skan_" + sa.id;
            if(idCounts.get(sa.id) > 1)
                name += "_layer_" + layer;
            Map<String, Object> animation = buildAnimation(sa, name,
                    obj("resforgeSkanId", sa.id,
                            "resforgeSkanLayer", layer,
                            "resforgeMode", sa.mode,
                            "resforgeLength", sa.len),
                    bind, boneNode, bin, bvs, accs, requireAllBones);
            if(animation != null)
                animations.add(animation);
        }

        SkanInfo combined = combineDisjoint(clips);
        if(combined != null) {
            List<Object> ids = new ArrayList<>();
            for(SkanInfo clip : clips)
                ids.add(clip.id);
            Map<String, Object> animation = buildAnimation(combined, "skan_combined",
                    obj("resforgeCombinedSkanIds", ids,
                            "resforgeMode", combined.mode,
                            "resforgeLength", combined.len),
                    bind, boneNode, bin, bvs, accs, requireAllBones);
            if(animation != null)
                animations.add(0, animation);
        }
        return animations;
    }

    private static Map<String, Object> buildAnimation(SkanInfo clip, String name,
                                                       Map<String, Object> extras,
                                                       Map<String, SkelInfo.Bone> bind,
                                                       Map<String, Integer> boneNode,
                                                       Buf bin, List<Object> bvs,
                                                       List<Object> accs,
                                                       boolean requireAllBones) {
        List<Object> samplers = new ArrayList<>();
        List<Object> channels = new ArrayList<>();
        for(SkanInfo.Track track : clip.tracks) {
            Integer node = boneNode.get(track.bone);
            SkelInfo.Bone bone = bind.get(track.bone);
            if(node == null || bone == null) {
                if(requireAllBones)
                    throw new IllegalArgumentException("animation bone \"" + track.bone
                            + "\" is missing from the selected skeleton");
                continue;
            }
            if(track.frames == 0)
                continue;

            float exportLength = clip.len > 0 ? clip.len : STATIC_EDIT_DURATION;
            boolean closeLoop = track.times[track.frames - 1] < exportLength - 1e-6f;
            int keys = track.frames + (closeLoop ? 1 : 0);
            float[] times = closeLoop ? Arrays.copyOf(track.times, keys) : track.times;
            if(closeLoop)
                times[keys - 1] = exportLength;
            int inAccessor = addScalar(bin, bvs, accs, times);

            // The client implicitly interpolates from the final frame back to frame 0
            // until clip.len. A zero-length static pose gets a synthetic one-second
            // endpoint so Blender exposes an editable action instead of a frame-0 dot.
            float[] tv = new float[keys * 3];
            for(int i = 0; i < keys; i++) {
                int frame = (i < track.frames) ? i : 0;
                tv[i * 3] = bone.px + track.trans[frame][0];
                tv[i * 3 + 1] = bone.py + track.trans[frame][1];
                tv[i * 3 + 2] = bone.pz + track.trans[frame][2];
            }
            int tAccessor = addVec3(bin, bvs, accs, tv, keys, false, false);
            samplers.add(obj("input", inAccessor, "output", tAccessor, "interpolation", "LINEAR"));
            channels.add(obj("sampler", samplers.size() - 1,
                    "target", obj("node", node, "path", "translation")));

            float[] bindRotation = M4.quat(bone.ax, bone.ay, bone.az, bone.ang);
            float[] rv = new float[keys * 4];
            for(int i = 0; i < keys; i++) {
                int frame = (i < track.frames) ? i : 0;
                float[] q = normalizeQuat(M4.qmul(bindRotation, track.rot[frame]));
                rv[i * 4] = q[1];     // glTF order x,y,z,w
                rv[i * 4 + 1] = q[2];
                rv[i * 4 + 2] = q[3];
                rv[i * 4 + 3] = q[0];
            }
            int rAccessor = addVec4(bin, bvs, accs, rv, keys);
            samplers.add(obj("input", inAccessor, "output", rAccessor, "interpolation", "LINEAR"));
            channels.add(obj("sampler", samplers.size() - 1,
                    "target", obj("node", node, "path", "rotation")));
        }
        if(channels.isEmpty())
            return null;
        return obj("name", name, "samplers", samplers, "channels", channels, "extras", extras);
    }

    /** Returns a preview-only composite when layer fragments can be merged without resampling. */
    private static SkanInfo combineDisjoint(List<SkanInfo> clips) {
        if(clips.size() < 2)
            return null;
        SkanInfo first = clips.get(0);
        java.util.Set<String> bones = new java.util.HashSet<>();
        SkanInfo combined = new SkanInfo();
        combined.mode = first.mode;
        combined.len = first.len;
        for(SkanInfo clip : clips) {
            if(!clip.mode.equals(first.mode) || Math.abs(clip.len - first.len) > 1e-4f)
                return null;
            for(SkanInfo.Track track : clip.tracks)
                if(!bones.add(track.bone))
                    return null;
            combined.tracks.addAll(clip.tracks);
        }
        return combined;
    }

    private static float[] normalizeQuat(float[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if(n == 0)
            return new float[]{1, 0, 0, 0};
        return new float[]{(float) (q[0] / n), (float) (q[1] / n), (float) (q[2] / n), (float) (q[3] / n)};
    }

    /** SCALAR float accessor with the min/max glTF requires for animation inputs. */
    private static int addScalar(Buf bin, List<Object> bvs, List<Object> accs, float[] data) {
        bin.align4();
        int off = bin.size();
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for(float v : data) {
            bin.f32(v);
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        int bv = addBufferView(bvs, off, bin.size() - off, -1);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", data.length);
        acc.put("type", "SCALAR");
        acc.put("min", List.of(mn));
        acc.put("max", List.of(mx));
        accs.add(acc);
        return accs.size() - 1;
    }

    private static int addVec4(Buf bin, List<Object> bvs, List<Object> accs, float[] data, int count) {
        bin.align4();
        int off = bin.size();
        for(float v : data)
            bin.f32(v);
        int bv = addBufferView(bvs, off, bin.size() - off, -1);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", count);
        acc.put("type", "VEC4");
        accs.add(acc);
        return accs.size() - 1;
    }

    /** A flat SCALAR float accessor (count = number of floats); used for morph weights. */
    private static int addScalarN(Buf bin, List<Object> bvs, List<Object> accs, float[] data) {
        bin.align4();
        int off = bin.size();
        for(float v : data)
            bin.f32(v);
        int bv = addBufferView(bvs, off, bin.size() - off, -1);
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("bufferView", bv);
        acc.put("componentType", FLOAT);
        acc.put("count", data.length);
        acc.put("type", "SCALAR");
        accs.add(acc);
        return accs.size() - 1;
    }

    private static int addBufferView(List<Object> bvs, int off, int len, int target) {
        Map<String, Object> bv = new LinkedHashMap<>();
        bv.put("buffer", 0);
        bv.put("byteOffset", off);
        bv.put("byteLength", len);
        if(target > 0)
            bv.put("target", target);
        bvs.add(bv);
        return bvs.size() - 1;
    }

    /* ----------------------------------------------------------- glb container */

    private static byte[] assembleGlb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] jsonChunk = pad(jsonBytes, (byte) 0x20);      // pad with spaces
        byte[] binChunk = pad(bin, (byte) 0x00);             // pad with zeros
        int total = 12 + 8 + jsonChunk.length + (binChunk.length == 0 ? 0 : 8 + binChunk.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        le(out, 0x46546C67);                                  // "glTF"
        le(out, 2);                                            // version
        le(out, total);
        le(out, jsonChunk.length);
        le(out, 0x4E4F534A);                                  // "JSON"
        out.writeBytes(jsonChunk);
        if(binChunk.length > 0) {
            le(out, binChunk.length);
            le(out, 0x004E4942);                              // "BIN\0"
            out.writeBytes(binChunk);
        }
        return out.toByteArray();
    }

    private static byte[] pad(byte[] data, byte fill) {
        int padded = (data.length + 3) & ~3;
        if(padded == data.length)
            return data;
        byte[] r = Arrays.copyOf(data, padded);
        for(int i = data.length; i < padded; i++)
            r[i] = fill;
        return r;
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }

    /* ------------------------------------------------- textures / material map */

    private static List<TexMat> collectTextures(ResContainer res) {
        List<TexMat> out = new ArrayList<>();
        int ord = 0;
        for(Layer l : res.layers) {
            if(!l.name.equals("tex"))
                continue;
            TexInfo ti = TexInfo.parse(l.data);
            if(!ti.found)
                continue;
            byte[] image = Arrays.copyOfRange(l.data, ti.imageOffset, ti.imageOffset + ti.imageLen);
            out.add(new TexMat("tex" + ord, image, mime(ti.imageFormat)));
            ord++;
        }
        return out;
    }

    private static String mime(String fmt) {
        if(fmt == null)
            return "image/png";
        switch(fmt.toLowerCase(Locale.ROOT)) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            default:     return "image/png";
        }
    }

    /* tex layer id -> ordinal, aligned with {@link #collectTextures} (which only
     * counts located textures), so a material's tex/otex id can be mapped to the
     * matching texMats slot. */
    private static Map<Integer, Integer> collectTexIds(ResContainer res) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        int ord = 0;
        for(Layer l : res.layers) {
            if(!l.name.equals("tex"))
                continue;
            TexInfo ti = TexInfo.parse(l.data);
            if(!ti.found)
                continue;
            map.putIfAbsent(ti.id, ord);
            ord++;
        }
        return map;
    }

    private static Map<Integer, Integer> collectMatToTex(ResContainer res, Map<Integer, Integer> texIds) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        if(texIds.isEmpty())
            return map;
        for(Layer l : res.layers) {
            if(!l.name.equals("mat2"))
                continue;
            try {
                Map<String, Object> m = Mat2Codec.decode(l.data);
                int id = ((Number) m.get("id")).intValue();
                Integer ord = firstLocalTexOrdinal((List<?>) m.get("entries"), texIds);
                if(ord != null)
                    map.put(id, ord);
            } catch(RuntimeException ignored) {
            }
        }
        return map;
    }

    /* The first tex/otex command whose first value is a local texture id, mapped to
     * its tex-layer ordinal. A string first value means an external (mlink/@res)
     * texture, which is skipped. The numeric first value is the tex layer's own id
     * (the client's flayer(TexR.class, id) lookup), not its position, so it is
     * resolved through the id->ordinal map. */
    private static Integer firstLocalTexOrdinal(List<?> entries, Map<Integer, Integer> texIds) {
        for(Object e : entries) {
            Map<?, ?> entry = (Map<?, ?>) e;
            String key = String.valueOf(entry.get("key"));
            if(!key.equals("tex") && !key.equals("otex"))
                continue;
            List<?> vals = (List<?>) entry.get("values");
            if(vals.isEmpty() || vals.get(0) instanceof String)
                continue;
            Object first = vals.get(0);
            if(first instanceof Map) {
                Object v = ((Map<?, ?>) first).values().iterator().next();
                if(v instanceof Number) {
                    Integer ord = texIds.get(((Number) v).intValue());
                    if(ord != null)
                        return ord;
                }
            }
        }
        return null;
    }

    private static String baseName(String sourceName) {
        String n = sourceName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if(slash >= 0)
            n = n.substring(slash + 1);
        if(n.toLowerCase(Locale.ROOT).endsWith(".res"))
            n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "model" : n;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for(int i = 0; i + 1 < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
