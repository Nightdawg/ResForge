package resforge;

import resforge.io.MessageWriter;
import resforge.model.ExternalTextures;
import resforge.model.ModelGeometry;
import resforge.res.Layer;
import resforge.res.ResContainer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for resolving external static materials (mlink / external tex string) by
 *  fetching the linked resource, used by the 3D viewer's "Resolve external textures". */
class ExternalTexturesTest {

    private static final byte A = 0x41;   // marker baked into "other-tex"'s image
    private static final byte B = 0x42;   // marker baked into "item-res"'s image

    /** A tex layer wrapping a tiny PNG whose 9th byte is {@code marker}. */
    private static byte[] tex(int id, byte marker) {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker, 2};
        MessageWriter w = new MessageWriter();
        w.int16(id).uint16(0).uint16(0).uint16(64).uint16(64);
        w.uint8(0).int32(png.length).bytes(png);
        return w.toByteArray();
    }

    private static byte[] mat2LocalTex(int id, int texId) {
        MessageWriter w = new MessageWriter();
        w.uint16(id).string("tex").uint8(4).uint8(texId).uint8(0);
        return w.toByteArray();
    }

    /** mat2 with an external {@code mlink [respath, ver, matid]}. */
    private static byte[] mat2MlinkExternal(int id, String path, int ver, int matid) {
        MessageWriter w = new MessageWriter();
        w.uint16(id).string("mlink")
                .uint8(2).string(path)
                .uint8(4).uint8(ver)
                .uint8(4).uint8(matid)
                .uint8(0);
        return w.toByteArray();
    }

    /** mat2 with a local {@code mlink [{u8:matid}]} (link to another local material). */
    private static byte[] mat2MlinkLocal(int id, int localMatid) {
        MessageWriter w = new MessageWriter();
        w.uint16(id).string("mlink").uint8(4).uint8(localMatid).uint8(0);
        return w.toByteArray();
    }

    /** mat2 with an external {@code tex [respath, …]} string. */
    private static byte[] mat2TexExternal(int id, String path) {
        MessageWriter w = new MessageWriter();
        w.uint16(id).string("tex")
                .uint8(2).string(path)
                .uint8(4).uint8(0)
                .uint8(4).uint8(0)
                .uint8(0);
        return w.toByteArray();
    }

    private static byte[] vbufTex() {
        MessageWriter w = new MessageWriter();
        w.uint8(0);
        w.uint16(3);
        w.string("pos2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0})
            w.float32(v);
        w.string("nrm2").uint8(1).string("f4");
        for(float v : new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1})
            w.float32(v);
        w.string("tex2").uint8(1).string("f4");
        for(int i = 0; i < 6; i++)
            w.float32(0.5f);
        return w.toByteArray();
    }

    private static byte[] meshMat(int matid) {
        MessageWriter w = new MessageWriter();
        w.uint8(16);
        w.uint16(1);
        w.int16(matid);
        w.int16(0);
        w.uint16(0).uint16(1).uint16(2);
        return w.toByteArray();
    }

    /** A fetcher backed by an in-memory map (returns null for unknown paths). */
    private static Map<String, ResContainer> servers() {
        Map<String, ResContainer> serve = new HashMap<>();

        ResContainer otherTex = new ResContainer(1);
        otherTex.layers.add(new Layer("tex", tex(2, A)));
        otherTex.layers.add(new Layer("mat2", mat2LocalTex(2, 2)));   // matid 2 -> local tex id 2
        serve.put("other-tex", otherTex);

        ResContainer itemRes = new ResContainer(2);
        itemRes.layers.add(new Layer("tex", tex(0, B)));              // its primary texture
        serve.put("item-res", itemRes);

        return serve;
    }

    @Test
    void resolvesExternalMlinkToTheLinkedResourceTexture() {
        Map<String, ResContainer> serve = servers();
        ResContainer main = new ResContainer(7);
        main.layers.add(new Layer("mat2", mat2MlinkExternal(5, "other-tex", 1, 2)));

        Map<Integer, ExternalTextures.Resolved> r = ExternalTextures.resolve(main, serve::get);
        assertTrue(r.containsKey(5));
        assertEquals(A, r.get(5).image[8]);
    }

    @Test
    void followsLocalMlinkChainsToAnExternalBase() {
        Map<String, ResContainer> serve = servers();
        ResContainer main = new ResContainer(7);
        main.layers.add(new Layer("mat2", mat2MlinkLocal(7, 5)));                   // 7 -> local 5
        main.layers.add(new Layer("mat2", mat2MlinkExternal(5, "other-tex", 1, 2))); // 5 -> external

        Map<Integer, ExternalTextures.Resolved> r = ExternalTextures.resolve(main, serve::get);
        assertEquals(A, r.get(7).image[8]);
        assertEquals(A, r.get(5).image[8]);
    }

    @Test
    void resolvesExternalTexStringToLinkedResourcePrimary() {
        Map<String, ResContainer> serve = servers();
        ResContainer main = new ResContainer(7);
        main.layers.add(new Layer("mat2", mat2TexExternal(9, "item-res")));

        Map<Integer, ExternalTextures.Resolved> r = ExternalTextures.resolve(main, serve::get);
        assertEquals(B, r.get(9).image[8]);
    }

    @Test
    void leavesUnreachableAndCyclicLinksUnresolved() {
        Map<String, ResContainer> serve = servers();
        ResContainer main = new ResContainer(7);
        main.layers.add(new Layer("mat2", mat2MlinkExternal(11, "nope", 1, 0)));   // 404
        main.layers.add(new Layer("mat2", mat2MlinkLocal(13, 13)));                // self-cycle
        main.layers.add(new Layer("mat2", mat2MlinkLocal(14, 15)));                // 14 <-> 15 cycle
        main.layers.add(new Layer("mat2", mat2MlinkLocal(15, 14)));

        Map<Integer, ExternalTextures.Resolved> r = ExternalTextures.resolve(main, serve::get);
        assertFalse(r.containsKey(11));
        assertFalse(r.containsKey(13));
        assertFalse(r.containsKey(14));
        assertFalse(r.containsKey(15));
    }

    @Test
    void nullFetcherResolvesNothing() {
        ResContainer main = new ResContainer(7);
        main.layers.add(new Layer("mat2", mat2MlinkExternal(5, "other-tex", 1, 2)));
        assertTrue(ExternalTextures.resolve(main, null).isEmpty());
    }

    @Test
    void modelGeometryTexturesExternalMaterialOnlyWhenAFetcherIsGiven() {
        Map<String, ResContainer> serve = servers();
        ResContainer model = new ResContainer(7);
        model.layers.add(new Layer("vbuf2", vbufTex()));
        model.layers.add(new Layer("mesh", meshMat(5)));
        model.layers.add(new Layer("mat2", mat2MlinkExternal(5, "other-tex", 1, 2)));

        ModelGeometry plain = ModelGeometry.from(model);
        assertFalse(plain.hasTextures());                 // external unresolved → shaded
        assertTrue(plain.externalTextures.isEmpty());

        ModelGeometry resolved = ModelGeometry.from(model, serve::get);
        assertTrue(resolved.hasTextures());
        assertEquals(1, resolved.externalTextures.size());
        assertEquals(A, resolved.externalTextures.get(0)[8]);

        ModelGeometry.Material mat = resolved.materials.get(0);
        assertFalse(mat.localBase);
        // external materials index into the combined palette after the local entries
        assertEquals(resolved.localTextures.size(), mat.defaultTex);
    }
}
