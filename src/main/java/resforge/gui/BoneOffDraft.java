package resforge.gui;

import resforge.io.Json;
import resforge.layers.BoneOffCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A boneoff JSON draft whose last valid payload can feed modeless previews. */
final class BoneOffDraft {
    record Validation(boolean valid, String error) {
    }

    private final List<Consumer<byte[]>> listeners = new ArrayList<>();
    private String json;
    private byte[] payload;

    BoneOffDraft(byte[] payload) {
        this.json = editableJson(payload);
        this.payload = payload.clone();
    }

    synchronized boolean editable() {
        return json != null;
    }

    synchronized String json() {
        return json;
    }

    synchronized byte[] payload() {
        return payload.clone();
    }

    Validation update(String value) {
        byte[] encoded;
        try {
            Object parsed = Json.parse(value);
            if(!(parsed instanceof Map))
                throw new IllegalArgumentException("boneoff JSON root must be an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) parsed;
            encoded = BoneOffCodec.encode(model);
        } catch(RuntimeException failure) {
            synchronized(this) {
                json = value;
            }
            return new Validation(false, message(failure));
        }

        List<Consumer<byte[]>> notify;
        synchronized(this) {
            json = value;
            payload = encoded;
            notify = List.copyOf(listeners);
        }
        for(Consumer<byte[]> listener : notify)
            listener.accept(encoded.clone());
        return new Validation(true, null);
    }

    Runnable listen(Consumer<byte[]> listener) {
        byte[] current;
        synchronized(this) {
            listeners.add(listener);
            current = payload.clone();
        }
        listener.accept(current);
        return () -> {
            synchronized(BoneOffDraft.this) {
                listeners.remove(listener);
            }
        };
    }

    void reset(byte[] value) {
        String replacementJson = editableJson(value);
        byte[] replacementPayload = value.clone();
        List<Consumer<byte[]>> notify;
        synchronized(this) {
            json = replacementJson;
            payload = replacementPayload;
            notify = List.copyOf(listeners);
        }
        for(Consumer<byte[]> listener : notify)
            listener.accept(replacementPayload.clone());
    }

    private static String editableJson(byte[] payload) {
        return BoneOffCodec.toJsonIfLossless(payload);
    }

    private static String message(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank()
                ? message : failure.getClass().getSimpleName();
    }
}
