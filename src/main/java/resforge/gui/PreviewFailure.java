package resforge.gui;

/** A bounded GUI preview failed; document editing and export remain available. */
final class PreviewFailure extends RuntimeException {
    PreviewFailure(String message) {
        super(message);
    }

    PreviewFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
