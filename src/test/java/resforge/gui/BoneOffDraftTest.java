package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.io.MessageWriter;
import resforge.layers.BoneOffCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoneOffDraftTest {
    @Test
    void validDraftsPublishWhileInvalidDraftsKeepLastValidPayload() {
        byte[] original = new MessageWriter().string("h")
                .uint8(2).string("hand").toByteArray();
        BoneOffDraft draft = new BoneOffDraft(original);
        List<byte[]> published = new ArrayList<>();
        Runnable unsubscribe = draft.listen(published::add);

        BoneOffDraft.Validation valid =
                draft.update(draft.json().replace("\"hand\"", "\"root\""));
        byte[] lastValid = draft.payload();
        BoneOffDraft.Validation invalid = draft.update("{");

        assertTrue(valid.valid());
        assertFalse(invalid.valid());
        assertEquals(2, published.size(), "subscription publishes current and changed payloads");
        assertArrayEquals(lastValid, draft.payload());
        assertEquals("{", draft.json(), "the editor keeps the incomplete draft text");
        Map<String, Object> model = BoneOffCodec.decode(lastValid);
        List<?> ops = (List<?>) model.get("ops");
        assertEquals("root", ((Map<?, ?>) ops.get(0)).get("bone"));

        unsubscribe.run();
        draft.update("{\"name\":\"h\",\"ops\":[]}");
        assertEquals(2, published.size());
    }

    @Test
    void resetRestoresSnapshotPayloadAndNotifiesOpenPreviews() {
        byte[] original = new MessageWriter().string("h")
                .uint8(2).string("hand").toByteArray();
        BoneOffDraft draft = new BoneOffDraft(original);
        List<byte[]> published = new ArrayList<>();
        draft.listen(published::add);
        draft.update("{\"name\":\"h\",\"ops\":[]}");

        draft.reset(original);

        assertArrayEquals(original, draft.payload());
        assertEquals(3, published.size());
        assertArrayEquals(original, published.get(2));
        assertTrue(draft.json().contains("\"hand\""));
    }

    @Test
    void unsupportedPayloadRemainsAvailableAsANonEditableDraft() {
        byte[] unsupported = new MessageWriter().string("h").uint8(255).toByteArray();

        BoneOffDraft draft = new BoneOffDraft(unsupported);

        assertFalse(draft.editable());
        assertArrayEquals(unsupported, draft.payload());
    }
}
