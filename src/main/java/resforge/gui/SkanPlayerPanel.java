package resforge.gui;

import resforge.layers.SkanInfo;
import resforge.model.BoneOffPlayback;
import resforge.model.SkanPlayback;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.FlowLayout;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Playback controls and worker lifecycle for a skinned {@link Model3DView}. */
final class SkanPlayerPanel extends JPanel implements AutoCloseable {
    private interface Playback {
        List<SkanInfo> clips();
        boolean canCombineAll();
        GeometryPose pose(List<SkanInfo> clips, float time);
    }

    private record GeometryPose(float[] positions, float[] normals) {
    }

    private final Playback playback;
    private final Model3DView view;
    private final JComboBox<ClipItem> clips;
    private final JButton play = new JButton("Play");
    private final JButton stop = new JButton("Stop");
    private final JSlider timeline = new JSlider(0, 1000, 0);
    private final JLabel timeLabel = new JLabel();
    private final JSpinner speed = new JSpinner(new SpinnerNumberModel(1.0, 0.25, 4.0, 0.25));
    private final Timer timer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "skan-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();

    private float time;
    private boolean backward;
    private boolean playing;
    private boolean updatingTimeline;
    private boolean closed;
    private long lastTick;

    SkanPlayerPanel(SkanPlayback playback, Model3DView view) {
        this(new Playback() {
            public List<SkanInfo> clips() { return playback.clips(); }
            public boolean canCombineAll() { return playback.canCombineAll(); }
            public GeometryPose pose(List<SkanInfo> clips, float time) {
                SkanPlayback.Pose pose = playback.pose(clips, time);
                return new GeometryPose(pose.positions(), pose.normals());
            }
        }, view);
    }

    SkanPlayerPanel(BoneOffPlayback playback, Model3DView view) {
        this(new Playback() {
            public List<SkanInfo> clips() { return playback.clips(); }
            public boolean canCombineAll() { return playback.canCombineAll(); }
            public GeometryPose pose(List<SkanInfo> clips, float time) {
                BoneOffPlayback.Pose pose = playback.pose(clips, time);
                return new GeometryPose(pose.positions(), pose.normals());
            }
        }, view);
    }

    private SkanPlayerPanel(Playback playback, Model3DView view) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 2));
        this.playback = playback;
        this.view = view;
        java.util.List<ClipItem> available = new java.util.ArrayList<>();
        if(playback.canCombineAll())
            available.add(ClipItem.all(playback.clips()));
        for(SkanInfo clip : playback.clips())
            available.add(ClipItem.single(clip));
        ClipItem[] items = available.toArray(new ClipItem[0]);
        this.clips = new JComboBox<>(items);
        this.timeline.setPreferredSize(UiScaling.scale(260, 22));

        add(new JLabel("Animation:"));
        add(clips);
        add(play);
        add(stop);
        add(timeline);
        add(timeLabel);
        add(new JLabel("Speed:"));
        add(speed);
        add(new JLabel("\u00d7"));

        timer = new Timer(33, e -> tick());
        timer.setCoalesce(true);
        play.addActionListener(e -> setPlaying(!playing));
        stop.addActionListener(e -> {
            setPlaying(false);
            time = 0;
            backward = false;
            updateTimeline();
            submitPose();
        });
        clips.addActionListener(e -> {
            setPlaying(false);
            time = 0;
            backward = false;
            updateTimeline();
            submitPose();
        });
        timeline.addChangeListener(e -> {
            if(updatingTimeline)
                return;
            ClipItem clip = clip();
            time = clip.length * timeline.getValue() / timeline.getMaximum();
            backward = false;
            updateTimeLabel();
            submitPose();
        });
        updateTimeline();
        submitPose();
    }

    private ClipItem clip() {
        return (ClipItem) clips.getSelectedItem();
    }

    private void setPlaying(boolean value) {
        playing = value;
        play.setText(value ? "Pause" : "Play");
        if(value) {
            lastTick = System.nanoTime();
            timer.start();
        } else {
            timer.stop();
        }
    }

    private void tick() {
        long now = System.nanoTime();
        float elapsed = Math.min(0.25f, (now - lastTick) / 1_000_000_000f);
        lastTick = now;
        float multiplier = ((Number) speed.getValue()).floatValue();
        ClipItem clip = clip();
        SkanPlayback.TimeState next =
                SkanPlayback.advance(time, backward, elapsed * multiplier, clip.length, clip.mode);
        time = next.time();
        backward = next.backward();
        if(next.done())
            setPlaying(false);
        updateTimeline();
        submitPose();
    }

    private void updateTimeline() {
        ClipItem clip = clip();
        updatingTimeline = true;
        timeline.setValue(clip.length <= 0 ? 0
                : Math.round(time / clip.length * timeline.getMaximum()));
        updatingTimeline = false;
        updateTimeLabel();
    }

    private void updateTimeLabel() {
        ClipItem clip = clip();
        timeLabel.setText(String.format("%.2f / %.2f s", time, clip.length));
    }

    private void submitPose() {
        if(closed)
            return;
        long request = generation.incrementAndGet();
        ClipItem clip = clip();
        float requestedTime = time;
        worker.execute(() -> {
            if(closed || request != generation.get())
                return;
            GeometryPose pose;
            try {
                pose = playback.pose(clip.clips, requestedTime);
            } catch(RuntimeException failure) {
                SwingUtilities.invokeLater(() -> {
                    if(!closed && request == generation.get()) {
                        setPlaying(false);
                        timeLabel.setText("Preview failed: " + failure.getMessage());
                    }
                });
                return;
            }
            if(closed || request != generation.get())
                return;
            SwingUtilities.invokeLater(() -> {
                if(!closed && request == generation.get())
                    view.setAnimatedGeometry(pose.positions(), pose.normals());
            });
        });
    }

    @Override public void close() {
        closed = true;
        timer.stop();
        generation.incrementAndGet();
        worker.shutdownNow();
    }

    private static final class ClipItem {
        final List<SkanInfo> clips;
        final String label;
        final float length;
        final String mode;

        private ClipItem(List<SkanInfo> clips, String label, float length, String mode) {
            this.clips = clips;
            this.label = label;
            this.length = length;
            this.mode = mode;
        }

        static ClipItem all(List<SkanInfo> clips) {
            SkanInfo first = clips.get(0);
            return new ClipItem(List.copyOf(clips), "All clips", first.len, first.mode);
        }

        static ClipItem single(SkanInfo clip) {
            return new ClipItem(List.of(clip), "skan " + clip.id, clip.len, clip.mode);
        }

        @Override public String toString() {
            return label + " \u00b7 " + mode + " \u00b7 " + String.format("%.2f s", length);
        }
    }
}
