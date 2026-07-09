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

/**
 * A compact in-app player for an Ogg Vorbis sound: Play/Pause, Stop, and a
 * draggable seek slider with a time readout. The Ogg is decoded to PCM lazily
 * (on first Play, off the EDT) via {@link OggVorbis} and played through a
 * {@link SourceDataLine} on a background thread; the slider both reflects and
 * controls the playback position.
 */
public class AudioPlayerPanel extends JPanel {
    private final byte[] ogg;

    private OggVorbis.Pcm pcm;
    private AudioFormat fmt;
    private int totalFrames;
    private volatile boolean decoding;

    private volatile boolean playing;
    private final AtomicInteger posFrame = new AtomicInteger(0);
    private Thread playThread;
    private volatile SourceDataLine line;

    private final JButton playBtn = new JButton("\u25B6 Play");
    private final JButton stopBtn = new JButton("\u25A0 Stop");
    private final JSlider slider = new JSlider(0, 1000, 0);
    private final JLabel timeLabel = new JLabel("0:00 / 0:00");
    private final Timer uiTimer = new Timer(60, e -> refreshUi());
    private boolean updatingSlider;
    private boolean userSeeking;

    public AudioPlayerPanel(byte[] ogg) {
        this.ogg = ogg;
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
        if(decoding)
            return;
        decoding = true;
        playBtn.setEnabled(false);
        timeLabel.setText("Decoding\u2026");
        Thread t = new Thread(() -> {
            OggVorbis.Pcm decoded = null;
            String err = null;
            try {
                decoded = OggVorbis.decode(ogg);
            } catch(Throwable ex) {
                err = ex.getMessage();
            }
            final OggVorbis.Pcm result = decoded;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
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
        if(pcm == null || playing)
            return;
        // Make sure a previous play loop (e.g. after a quick Pause then Play) has
        // fully exited before starting a new one, so two threads never share the line.
        Thread prev = playThread;
        if(prev != null && prev.isAlive()) {
            try {
                prev.join(250);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if(posFrame.get() >= totalFrames)
            posFrame.set(0);
        playing = true;
        playBtn.setText("\u23F8 Pause");
        uiTimer.start();
        playThread = new Thread(this::playLoop, "ogg-play");
        playThread.setDaemon(true);
        playThread.start();
    }

    private void playLoop() {
        SourceDataLine ln = null;
        String err = null;
        try {
            ln = AudioSystem.getSourceDataLine(fmt);
            ln.open(fmt);
            ln.start();
            line = ln;
            int frameBytes = pcm.channels * 2;
            int chunkFrames = Math.max(1, pcm.rate / 20);   // ~50 ms
            byte[] buf = new byte[chunkFrames * frameBytes];
            while(playing) {
                int pf = posFrame.get();
                if(pf >= totalFrames)
                    break;
                int n = Math.min(chunkFrames, totalFrames - pf);
                System.arraycopy(pcm.data, pf * frameBytes, buf, 0, n * frameBytes);
                ln.write(buf, 0, n * frameBytes);
                posFrame.compareAndSet(pf, pf + n);          // don't clobber a seek
            }
            if(playing)
                ln.drain();
        } catch(Throwable e) {
            err = e.getMessage();
        } finally {
            line = null;
            if(ln != null) {
                try {
                    ln.stop();
                    ln.close();
                } catch(RuntimeException ignored) {
                }
            }
            playing = false;
            final String error = err;
            SwingUtilities.invokeLater(() -> {
                playBtn.setText("\u25B6 Play");
                uiTimer.stop();
                if(error != null)
                    timeLabel.setText("(audio error: " + error + ")");
                refreshUi();
            });
        }
    }

    private void pause() {
        playing = false;       // the play loop finishes its current chunk and exits
    }

    private void stop() {
        playing = false;
        posFrame.set(0);
        SourceDataLine ln = line;
        if(ln != null) {
            try {
                ln.flush();
            } catch(RuntimeException ignored) {
            }
        }
        SwingUtilities.invokeLater(this::refreshUi);
    }

    /** Stops playback and releases resources; call when the player is discarded. */
    public void dispose() {
        playing = false;
        uiTimer.stop();
        Thread t = playThread;
        if(t != null) {
            try {
                t.join(300);
            } catch(InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /* ------------------------------------------------------------------- slider */

    private void onSliderChanged() {
        if(updatingSlider || pcm == null)
            return;
        if(slider.getValueIsAdjusting())
            userSeeking = true;
        int frame = (int) (slider.getValue() / 1000.0 * totalFrames);
        posFrame.set(Math.max(0, Math.min(frame, totalFrames)));
        SourceDataLine ln = line;
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
