package resforge.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WinFileDialogsTest {
    @Test
    void successfulShowContinuesToReadSelection() {
        assertEquals(WinFileDialogs.ShowOutcome.SUCCESS,
                WinFileDialogs.classifyShowResult(0));
    }

    @Test
    void onlyWindowsCancellationHresultMeansCancelled() {
        assertEquals(WinFileDialogs.ShowOutcome.CANCELLED,
                WinFileDialogs.classifyShowResult(0x800704C7));
    }

    @Test
    void otherComErrorsTriggerFallback() {
        assertEquals(WinFileDialogs.ShowOutcome.FAILED,
                WinFileDialogs.classifyShowResult(0x80004005)); // E_FAIL
        assertEquals(WinFileDialogs.ShowOutcome.FAILED,
                WinFileDialogs.classifyShowResult(0x80070005)); // E_ACCESSDENIED
        assertEquals(WinFileDialogs.ShowOutcome.FAILED,
                WinFileDialogs.classifyShowResult(1));          // S_FALSE is not success here
    }
}
