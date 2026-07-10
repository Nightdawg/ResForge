package resforge.gui;

/**
 * EDT-confined identity and revision gate for operations that may replace the
 * current document after completing in the background.
 */
final class DocumentRevision {
    private long documentIdentity;
    private long revision;
    private long operationGeneration;

    Token beginOperation() {
        return new Token(++operationGeneration, documentIdentity, revision);
    }

    boolean complete(Token token) {
        if(!isCurrent(token))
            return false;
        operationGeneration++;
        return true;
    }

    boolean isLatest(Token token) {
        return token.operationGeneration == operationGeneration;
    }

    void replaceDocument() {
        documentIdentity++;
        revision = 0;
        operationGeneration++;
    }

    void modified() {
        revision++;
    }

    void invalidateOperations() {
        operationGeneration++;
    }

    private boolean isCurrent(Token token) {
        return token.operationGeneration == operationGeneration
                && token.documentIdentity == documentIdentity
                && token.revision == revision;
    }

    static final class Token {
        private final long operationGeneration;
        private final long documentIdentity;
        private final long revision;

        private Token(long operationGeneration, long documentIdentity, long revision) {
            this.operationGeneration = operationGeneration;
            this.documentIdentity = documentIdentity;
            this.revision = revision;
        }
    }
}
