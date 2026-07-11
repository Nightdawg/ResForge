package resforge.gui;

import resforge.audio.OggVorbis;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A compact in-app player for an Ogg Vorbis sound: Play/Pause, Stop, and a
 * draggable seek slider with a time readout. The Ogg is decoded to PCM lazily
 * (on first Play, off the EDT) via {@link OggVorbis} and played through a
 * {@link SourceDataLine} on a background thread; the slider both reflects and
 * controls the playback position.
 */
public class AudioPlayerPanel extends JPanel {
    @FunctionalInterface
    interface Decoder {
        OggVorbis.Pcm decode(byte[] ogg) throws Exception;
    }

    @FunctionalInterface
    interface LineFactory {
        SourceDataLine create(AudioFormat format) throws Exception;
    }

    private final byte[] ogg;
    private final Decoder decoder;
    private final LineFactory lineFactory;

    private OggVorbis.Pcm pcm;
    private AudioFormat fmt;
    private int totalFrames;
    private volatile boolean decoding;

    private volatile boolean playing;
    private final AtomicInteger posFrame = new AtomicInteger(0);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<SourceDataLine> line = new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private Thread playThread;
    private volatile boolean disposed;

    private final JButton playBtn = new JButton("\u25B6 Play");
    private final JButton stopBtn = new JButton("\u25A0 Stop");
    private final JSlider slider = new JSlider(0, 1000, 0);
    private final JLabel timeLabel = new JLabel("0:00 / 0:00");
    private final Timer uiTimer = new Timer(60, e -> refreshUi());
    private boolean updatingSlider;
    private boolean userSeeking;

    public AudioPlayerPanel(byte[] ogg) {
        this(ogg, OggVorbis::decode, AudioSystem::getSourceDataLine);
    }

    AudioPlayerPanel(byte[] ogg, Decoder decoder, LineFactory lineFactory) {
        this.ogg = ogg;
        this.decoder = decoder;
        this.lineFactory = lineFactory;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Sound"));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(playBtn);
        controls.add(stopBtn);
        controls.add(timeLabel);
        add(controls);

        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setEnabled(false);
        add(slider);

        stopBtn.setEnabled(false);
        playBtn.addActionListener(e -> togglePlay());
        stopBtn.addActionListener(e -> stop());
        slider.addChangeListener(e -> onSliderChanged());

        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height + UiScaling.scale(10)));
    }

    /* --------------------------------------------------------------- controls */

    private void togglePlay() {
        if(playing) {
            pause();
        } else if(pcm != null) {
            startPlayback();
        } else {
            decodeThenPlay();
        }
    }

    private void decodeThenPlay() {
        if(decoding || disposed)
            return;
        long token;
        synchronized(lifecycleLock) {
            token = generation.incrementAndGet();
        }
        decoding = true;
        playBtn.setEnabled(false);
        timeLabel.setText("Decoding\u2026");
        Thread t = new Thread(() -> {
            OggVorbis.Pcm decoded = null;
            String err = null;
            try {
                decoded = decoder.decode(ogg);
            } catch(Exception ex) {
                err = ex.getMessage();
            }
            final OggVorbis.Pcm result = decoded;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(!isCurrent(token))
                    return;
                decoding = false;
                playBtn.setEnabled(true);
                if(result == null) {
                    timeLabel.setText("(could not decode: " + error + ")");
                    return;
                }
                pcm = result;
                fmt = new AudioFormat(pcm.rate, 16, pcm.channels, true, false);
                totalFrames = pcm.frames();
                slider.setEnabled(true);
                stopBtn.setEnabled(true);
                refreshUi();
                startPlayback();
            });
        }, "ogg-decode");
        t.setDaemon(true);
        t.start();
    }

    private void startPlayback() {
        if(pcm == null || playing || disposed)
            return;
        long token;
        SourceDataLine staleLine;
        synchronized(lifecycleLock) {
            token = generation.incrementAndGet();
            staleLine = line.getAndSet(null);
        }
        closeLine(staleLine);
        if(posFrame.get() >= totalFrames)
            posFrame.set(0);
        playing = true;
        playBtn.setText("\u23F8 Pause");
        uiTimer.start();
        OggVorbis.Pcm playingPcm = pcm;
        AudioFormat playingFormat = fmt;
        playThread = new Thread(() -> playLoop(token, playingPcm, playingFormat), "ogg-play");
        playThread.setDaemon(true);
        playThread.start();
    }

    private void playLoop(long token, OggVorbis.Pcm playingPcm, AudioFormat playingFormat) {
        SourceDataLine ln = null;
        String err = null;
        try {
            ln = lineFactory.create(playingFormat);
            ln.open(playingFormat);
            ln.start();
            SourceDataLine previous;
            synchronized(lifecycleLock) {
                if(!isCurrent(token))
                    return;
                previous = line.getAndSet(ln);
            }
            if(previous != null && previous != ln)
                closeLine(previous);
            if(!isCurrent(token)) {
                line.compareAndSet(ln, null);
                return;
            }
            int frameBytes = playingPcm.channels * 2;
            int chunkFrames = Math.max(1, playingPcm.rate / 20);   // ~50 ms
            byte[] buf = new byte[chunkFrames * frameBytes];
            while(isCurrent(token)) {
                int pf = posFrame.get();
                if(pf >= totalFrames)
                    break;
                int n = Math.min(chunkFrames, totalFrames - pf);
                System.arraycopy(playingPcm.data, pf * frameBytes, buf, 0, n * frameBytes);
                ln.write(buf, 0, n * frameBytes);
                posFrame.compareAndSet(pf, pf + n);          // don't clobber a seek
            }
            if(isCurrent(token))
                ln.drain();
        } catch(Exception e) {
            err = e.getMessage();
        } finally {
            if(ln != null)
                line.compareAndSet(ln, null);
            closeLine(ln);
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                if(!isCurrent(token))
                    return;
                playing = false;
                playBtn.setText("\u25B6 Play");
                uiTimer.stop();
                if(error != null)
                    timeLabel.setText("(audio error: " + error + ")");
                refreshUi();
            });
        }
    }

    private void pause() {
        invalidatePlayback(false);
    }

    private void stop() {
        invalidatePlayback(true);
    }

    /** Stops playback and releases resources; call when the player is discarded. */
    public void dispose() {
        SourceDataLine active;
        synchronized(lifecycleLock) {
            disposed = true;
            generation.incrementAndGet();
            active = line.getAndSet(null);
        }
        decoding = false;
        playing = false;
        uiTimer.stop();
        closeLine(active);
        Thread thread = playThread;
        if(thread != null)
            thread.interrupt();
    }

    /* ------------------------------------------------------------------- slider */

    private void onSliderChanged() {
        if(updatingSlider || pcm == null)
            return;
        if(slider.getValueIsAdjusting())
            userSeeking = true;
        int frame = (int) (slider.getValue() / 1000.0 * totalFrames);
        posFrame.set(Math.max(0, Math.min(frame, totalFrames)));
        SourceDataLine ln = line.get();
        if(ln != null) {
            try {
                ln.flush();
            } catch(RuntimeException ignored) {
            }
        }
        if(!slider.getValueIsAdjusting())
            userSeeking = false;
        timeLabel.setText(fmt(posFrame.get()) + " / " + fmt(totalFrames));
    }

    private void invalidatePlayback(boolean resetPosition) {
        SourceDataLine active;
        synchronized(lifecycleLock) {
            generation.incrementAndGet();
            active = line.getAndSet(null);
        }
        playing = false;
        if(resetPosition)
            posFrame.set(0);
        closeLine(active);
        playBtn.setText("\u25B6 Play");
        uiTimer.stop();
        refreshUi();
    }

    private boolean isCurrent(long token) {
        return !disposed && generation.get() == token;
    }

    private static void closeLine(SourceDataLine source) {
        if(source == null)
            return;
        try {
            source.stop();
        } catch(RuntimeException ignored) {
        }
        try {
            source.flush();
        } catch(RuntimeException ignored) {
        }
        try {
            source.close();
        } catch(RuntimeException ignored) {
        }
    }

    private void refreshUi() {
        if(pcm == null)
            return;
        int pf = posFrame.get();
        if(!userSeeking) {
            updatingSlider = true;
            slider.setValue(totalFrames == 0 ? 0 : (int) (pf / (double) totalFrames * 1000));
            updatingSlider = false;
        }
        timeLabel.setText(fmt(pf) + " / " + fmt(totalFrames));
    }

    private String fmt(int frame) {
        if(pcm == null || pcm.rate == 0)
            return "0:00";
        int secs = frame / pcm.rate;
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }
}
