package resforge.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentRevisionTest {
    @Test
    void outOfOrderCompletionAppliesOnlyNewestOperation() {
        DocumentRevision state = new DocumentRevision();
        state.replaceDocument();
        DocumentRevision.Token first = state.beginOperation();
        DocumentRevision.Token second = state.beginOperation();
        List<String> applied = new ArrayList<>();

        if(state.complete(first))
            applied.add("first");
        if(state.complete(second))
            applied.add("second");

        assertFalse(applied.contains("first"));
        assertTrue(applied.contains("second"));
    }

    @Test
    void editAfterOperationStartsRejectsItsCompletion() {
        DocumentRevision state = new DocumentRevision();
        state.replaceDocument();
        DocumentRevision.Token operation = state.beginOperation();

        state.modified();

        assertTrue(state.isLatest(operation));
        assertFalse(state.complete(operation));
    }

    @Test
    void documentReplacementRejectsOldCompletion() {
        DocumentRevision state = new DocumentRevision();
        state.replaceDocument();
        DocumentRevision.Token operation = state.beginOperation();

        state.replaceDocument();

        assertFalse(state.complete(operation));
    }

    @Test
    void acceptedCompletionCannotApplyTwice() {
        DocumentRevision state = new DocumentRevision();
        state.replaceDocument();
        DocumentRevision.Token operation = state.beginOperation();

        assertTrue(state.complete(operation));
        assertFalse(state.complete(operation));
    }

    @Test
    void explicitInvalidationRejectsPendingOperation() {
        DocumentRevision state = new DocumentRevision();
        state.replaceDocument();
        DocumentRevision.Token operation = state.beginOperation();

        state.invalidateOperations();

        assertFalse(state.complete(operation));
    }
}
