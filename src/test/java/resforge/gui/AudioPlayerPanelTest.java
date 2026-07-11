package resforge.gui;

import org.junit.jupiter.api.Test;
import resforge.audio.OggVorbis;

import javax.sound.sampled.SourceDataLine;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlayerPanelTest {
    @Test
    void stalePlaybackFinallyCannotStopNewPlayback() throws Exception {
        BlockingLine first = new BlockingLine();
        BlockingLine second = new BlockingLine();
        AtomicInteger created = new AtomicInteger();
        AudioPlayerPanel panel = onEdt(() -> new AudioPlayerPanel(new byte[]{1},
                ignored -> pcm(), format -> created.getAndIncrement() == 0
                        ? first.proxy : second.proxy));
        JButton play = button(panel, "Play");

        onEdt(() -> play.doClick());
        assertTrue(first.writeEntered.await(2, TimeUnit.SECONDS));

        onEdt(() -> play.doClick()); // pause: closes and releases the first line
        onEdt(() -> play.doClick()); // immediately start a new generation
        assertTrue(second.writeEntered.await(2, TimeUnit.SECONDS));
        assertTrue(first.writeReturned.await(2, TimeUnit.SECONDS));
        flushEdt();

        assertTrue(onEdt(play::getText).contains("Pause"),
                "the stale first generation must not reset the new playback UI");
        assertEquals(2, created.get());

        onEdt(panel::dispose);
        assertTrue(second.closed.await(2, TimeUnit.SECONDS));
    }

    @Test
    void disposeWhileDecodingPreventsInvisiblePlayback() throws Exception {
        CountDownLatch decodeEntered = new CountDownLatch(1);
        CountDownLatch releaseDecode = new CountDownLatch(1);
        CountDownLatch decodeReturned = new CountDownLatch(1);
        AtomicInteger linesCreated = new AtomicInteger();
        AudioPlayerPanel panel = onEdt(() -> new AudioPlayerPanel(new byte[]{1}, ignored -> {
            decodeEntered.countDown();
            releaseDecode.await(2, TimeUnit.SECONDS);
            decodeReturned.countDown();
            return pcm();
        }, format -> {
            linesCreated.incrementAndGet();
            return new BlockingLine().proxy;
        }));

        onEdt(() -> button(panel, "Play").doClick());
        assertTrue(decodeEntered.await(2, TimeUnit.SECONDS));
        onEdt(panel::dispose);
        releaseDecode.countDown();
        assertTrue(decodeReturned.await(2, TimeUnit.SECONDS));
        flushEdt();

        assertEquals(0, linesCreated.get(),
                "a stale decode callback must not start playback after disposal");
    }

    @Test
    void staleThreadCannotDisplaceLineInstalledByNewGeneration() throws Exception {
        BlockingLine first = new BlockingLine(true);
        BlockingLine second = new BlockingLine();
        AtomicInteger created = new AtomicInteger();
        AudioPlayerPanel panel = onEdt(() -> new AudioPlayerPanel(new byte[]{1},
                ignored -> pcm(), format -> created.getAndIncrement() == 0
                        ? first.proxy : second.proxy));
        JButton play = button(panel, "Play");

        onEdt(() -> play.doClick());
        assertTrue(first.startEntered.await(2, TimeUnit.SECONDS));
        onEdt(() -> play.doClick()); // invalidate first before it installs its line
        onEdt(() -> play.doClick()); // second installs and starts writing
        assertTrue(second.writeEntered.await(2, TimeUnit.SECONDS));

        first.releaseStart.countDown();
        assertTrue(first.closed.await(2, TimeUnit.SECONDS));
        flushEdt();

        assertEquals(1L, second.closed.getCount(),
                "stale first thread must not close the current second line");
        assertTrue(onEdt(play::getText).contains("Pause"));
        onEdt(panel::dispose);
    }

    private static OggVorbis.Pcm pcm() {
        return new OggVorbis.Pcm(new byte[4000], 1000, 1);
    }

    private static JButton button(Container root, String text) {
        for(JButton button : components(root, JButton.class))
            if(button.getText().contains(text))
                return button;
        throw new AssertionError("button not found: " + text);
    }

    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for(Component component : root.getComponents()) {
            if(type.isInstance(component))
                found.add(type.cast(component));
            if(component instanceof Container)
                found.addAll(components((Container) component, type));
        }
        return found;
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
        onEdt(() -> null);
    }

    private static void onEdt(Runnable action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        if(SwingUtilities.isEventDispatchThread())
            return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch(Exception e) {
                failure.set(e);
            }
        });
        if(failure.get() != null)
            throw failure.get();
        return result.get();
    }

    private static final class BlockingLine {
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch releaseStart;
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch writeReturned = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final SourceDataLine proxy;

        BlockingLine() {
            this(false);
        }

        BlockingLine(boolean blockStart) {
            releaseStart = new CountDownLatch(blockStart ? 1 : 0);
            proxy = (SourceDataLine) Proxy.newProxyInstance(
                SourceDataLine.class.getClassLoader(), new Class<?>[]{SourceDataLine.class},
                (instance, method, args) -> {
                    switch(method.getName()) {
                        case "start":
                            startEntered.countDown();
                            releaseStart.await(2, TimeUnit.SECONDS);
                            return null;
                        case "write":
                            writeEntered.countDown();
                            closed.await(2, TimeUnit.SECONDS);
                            writeReturned.countDown();
                            return args[2];
                        case "close":
                            closed.countDown();
                            return null;
                        case "isOpen":
                        case "isRunning":
                        case "isActive":
                            return closed.getCount() != 0;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if(type == boolean.class) return false;
        if(type == byte.class) return (byte) 0;
        if(type == short.class) return (short) 0;
        if(type == int.class) return 0;
        if(type == long.class) return 0L;
        if(type == float.class) return 0f;
        if(type == double.class) return 0d;
        if(type == char.class) return '\0';
        return null;
    }
}
