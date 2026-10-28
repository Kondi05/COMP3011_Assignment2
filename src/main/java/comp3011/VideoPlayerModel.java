/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Kondinya Bung Chakma and a1960283
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p><b>Ownership.</b> Holds the FFmpeg decoder, the playback clock, the
 * seeking logic, the audio scheduler, and the {@link FramePipeline}.
 * The pipeline runs entirely off the JavaFX Application Thread; only
 * {@link #notifyFrameReady(Image)} touches JavaFX scene graph state, and it
 * always runs inside the {@link AnimationTimer} pulse (i.e. on the FX thread).</p>
 *
 * <p><b>Thread model.</b></p>
 * <ul>
 *   <li><b>FX thread</b> — runs {@link #pumpPlayback(long)} at ~60Hz. Drains
 *       the next sequential frame from the pipeline, enforces the
 *       frame-number ordering barrier, and updates the view. Never blocks on
 *       audio I/O or effect processing.</li>
 *   <li><b>Decoder thread</b> (inside {@link FramePipeline}) — sole owner of
 *       the FFmpeg grabber. Calls {@link #grabFrameInternal()} which also
 *       copies any audio samples off the frame before the clone is handed
 *       to the worker.</li>
 *   <li><b>Worker thread(s)</b> (inside {@link FramePipeline}) — apply the
 *       effect chain and convert to a JavaFX Image.</li>
 *   <li><b>audio-writer thread</b> — a single bounded executor that drains
 *       {@link #pendingAudio} and writes to the Java Sound line. Isolated
 *       from the FX thread so a slow sound device cannot stall the GUI.</li>
 * </ul>
 *
 * <p><b>Critical regions.</b></p>
 * <ul>
 *   <li>{@link #pendingAudio} — {@link ConcurrentLinkedQueue}; producer is
 *       the decoder thread, consumer is the audio-writer thread. No user
 *       lock needed.</li>
 *   <li>{@link #queueAudio} and {@link #writeDueAudio} are {@code synchronized}
 *       on the model instance. They touch {@link AudioPlayer} (not
 *       thread-safe) and must not interleave. This is the only user-level
 *       monitor in the class, and it is held for microsecond-scale work —
 *       no I/O and no allocation on the hot path.</li>
 *   <li>{@link FramePipeline} internal queues are independent critical
 *       regions inside that class; see its Javadoc.</li>
 * </ul>
 *
 * <p><b>Ordering.</b> {@code nextExpectedFrameNumber} is only
 * advanced when the exact next-sequential frame is received from the
 * pipeline. If a later frame is ready first (possible with multiple workers),
 * the display simply waits — the priority queue holds the later frames until
 * they are due. This is what prevents frame tearing and reordering.</p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // Runs on the FX thread at ~60Hz , should be able to return
    // quickly — anything slow here stalls GUI interaction 
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private FramePipeline framePipeline;
    private AudioPlayer audioPlayer;
    private FramePipeline.ProcessedFrame pendingDueFrame;

    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private boolean pipelineStarted;

    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    private int nextExpectedFrameNumber = -1;
    private long displayFrameCounter;

    // Single-thread bounded executor for audio writes
    private final java.util.concurrent.ThreadPoolExecutor audioWriter =
            new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r, "audio-writer");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    public VideoPlayerModel(
            boolean audioEnabled,
            List<String> frameProcessorNames,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        // Construct all processors on the main thread, in CLI order, before
        // any pipeline thread starts. This is needed so per-instance
        // instanceIndex values (and therefore the deterministic seeds used by
        // the effects) match the single-threaded reference exactly
        this.frameProcessors = createFrameProcessors(frameProcessorNames);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) return;
        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }
        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }
        notifyPlaybackStateChanged();
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        audioWriter.shutdownNow();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) return;
        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);
        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }
        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();
        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;
        notifyStatusChanged(videoFile.getName());
        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0 ? durationUs : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }

        InfoVideo videoInfo = new InfoVideo(
                mediaName(videoFile),
                totalVideoFrames,
                grabber.getImageWidth(),
                grabber.getImageHeight(),
                Frame.DEPTH_UBYTE,
                3,
                grabber.getImageWidth() * 3,
                frameRate,
                intFrameRate,
                videoFrameDurationUs,
                grabber.getPixelFormat());

        initialiseFrameProcessors(videoInfo);
        frameProcessorsInitialised = true;
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        pipelineStarted = false;
        pendingDueFrame = null;
        nextExpectedFrameNumber = -1;
        displayFrameCounter = 0;

        
        if (framePipeline != null) {
            framePipeline.shutdown();
        }
        framePipeline = new FramePipeline(frameProcessors);
    }

    private void pumpPlayback(long now) {
        if (!playbackOpen || framePipeline == null) return;

        if (!pipelineStarted) {
            framePipeline.start(new FramePipeline.FrameSupplier() {
                @Override public Frame grabFrame() throws Exception { return grabFrameInternal(); }
                @Override public InfoFrame getInfo() {
                    return new InfoFrame(grabber.getFrameNumber(), grabber.getTimestamp());
                }
                @Override public int getFrameNumber() { return grabber.getFrameNumber(); }
            });
            pipelineStarted = true;
            System.out.printf("FramePipeline started with %d effect-worker threads%n",
                    framePipeline.workerCount());
        }

        // Audio write is dispatched off the FX thread. If the sound device
        // stalls, the audio-writer executor drops the submission rather than
        // blocking the pulse.
        final long audioNow = now;
        audioWriter.submit(() -> writeDueAudio(audioNow));

        // Bootstrap timing from the first decoded frame. Its frame number
        // becomes the baseline for the ordering gate.
        if (nextExpectedFrameNumber < 0) {
            FramePipeline.ProcessedFrame first = framePipeline.pollFirstFrame();
            if (first != null) {
                nextExpectedFrameNumber = first.frameNumber();
                firstTimestampUs = grabber.getTimestamp();
                playbackStartNs = now;
                if (pauseRequested) pauseStartedNs = now;
                displayFrameCounter = 0;
                displayProcessedFrame(first);
                // Return the WritableImage to the pool after display so the
                // next frame can reuse its backing store.
                framePipeline.returnImage(first.image());
                nextExpectedFrameNumber++;
                displayFrameCounter++;
            }
            return;
        }

        // Only accept the exact next frame number. Later frames stay queued
        // in the priority queue until they become the expected number.
        if (pendingDueFrame == null) {
            pendingDueFrame = framePipeline.pollNextFrame(nextExpectedFrameNumber);
        }
        if (pendingDueFrame == null) return;

        // Display pacing - hold the frame until its scheduled display time.
        long relativeTimestampUs = displayFrameCounter * videoFrameDurationUs;
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
        if (now < targetTimeNs) return;

        displayProcessedFrame(pendingDueFrame);
        framePipeline.returnImage(pendingDueFrame.image());
        pendingDueFrame = null;
        nextExpectedFrameNumber++;
        displayFrameCounter++;
    }

    private void displayProcessedFrame(FramePipeline.ProcessedFrame frame) {
        int frameNumber = frame.frameNumber();
        currentTimestampUs = (long) frameNumber * videoFrameDurationUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Headroom diagnostic: logs once per second showing how much of the
        // per-frame budget was still spare when this frame hit display
        if (intFrameRate > 0 && frameNumber % intFrameRate == 0) {
            long targetNs = playbackStartNs + displayFrameCounter * (long) videoFrameDurationUs * 1_000L;
            long remainingUs = (targetNs - System.nanoTime()) / 1_000L;
            long budgetUs = videoFrameDurationUs;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs, budgetUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs, budgetUs);
            }
        }
        notifyFrameReady(frame.image());
    }

    private synchronized void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) return;
        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private synchronized void writeDueAudio(long now) {
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }
            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }
            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST ? videoDurationUs : currentTimestampUs;
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        if (framePipeline != null) {
            framePipeline.shutdown();
            framePipeline = null;
        }
        playbackTimer.stop();
        playbackOpen = false;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
        pipelineStarted = false;
        pendingDueFrame = null;
        nextExpectedFrameNumber = -1;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception e) { }
            try { grabber.close(); } catch (Exception e) { }
            grabber = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) return fileName;
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }
        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }
        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrameInternal() throws Exception {
        Frame frame = audioAvailable ? grabber.grab() : grabber.grabImage();
        if (frame != null && audioAvailable && frame.samples != null) {
            // Capture audio on the decoder thread (before the clone) so the
            // audio queue stays in step with the frame queue.
            queueAudio(grabber.getTimestamp(), frame);
        }
        return frame;
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() { return timestampUs; }
        byte[] samples() { return samples; }
        int offset() { return offset; }
        int remaining() { return samples.length - offset; }
        void advance(int byteCount) { offset += byteCount; }
        boolean finished() { return offset >= samples.length; }
    }

    // Factory method for FrameProcessors.
    // for new effect:
    // 1. Implement FrameProcessor subclass.
    // 2. Add case mapping a stable string name to the new class.
    // 3. Map corresponding CLI flag in CommandLineController.
    // 4. Add long form flag and help text in CommandLineController.printHelp.
    // To note - processors be constructed on main thread, in CLI order,
    // so that per-instance instanceIndex values (and therefore deterministic
    // effect output) match the reference implementation exactly.
    private static List<FrameProcessor> createFrameProcessors(List<String> effectNames) {
        List<FrameProcessor> processors = new ArrayList<>();
        for (String name : effectNames) {
            switch (name) {
                case "number" -> processors.add(new FrameNumberer());
                case "scratch" -> processors.add(new FrameScratcher());
                case "flicker" -> processors.add(new FrameFlickerer());
                case "blackandwhite" -> processors.add(new FrameBlackAndWhiter());
                case "yellow" -> processors.add(new FrameYellower());
                case "vignette" -> processors.add(new FrameVignetter());
                case "dust" -> processors.add(new FrameDuster());
                case "jitter" -> processors.add(new FrameJitterer());
                case "mottle" -> processors.add(new FrameMottler());
                case "bleed" -> processors.add(new FrameBleeder());
                case "pepper" -> processors.add(new FramePepperer());
                default -> System.err.println("Warning: Unknown frame processor ignored: " + name);
            }
        }
        return processors;
    }
}