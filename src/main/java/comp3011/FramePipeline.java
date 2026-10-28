package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

/**
 * Frame processing pipeline with a dedicated decoder thread, a worker thread
 * pool, and an ordered output queue.
 *
 * <p><b>Threading model.</b> Three stages, each on its own thread(s):</p>
 * <ol>
 *   <li><b>Decoder (1 thread)</b> — sole owner of the FFmpeg grabber, which
 *       is not thread-safe. Emits cloned frames in order into
 *       {@code decodeQueue}. The clone is mandatory: FFmpeg recycles its
 *       native image buffers across {@code grab()} calls, so without cloning
 *       a worker would see the next frame's pixels.</li>
 *   <li><b>Worker (1 thread, configurable)</b> — pulls a cloned frame, applies
 *       its own private processor chain in CLI order, converts to a JavaFX
 *       {@link Image}, then copies pixels into a recycled {@link WritableImage}.
 *       Image recycling is essential: allocating a fresh 1920×800 Image per
 *       frame caused the FX Windows dispatcher to OOM under load.</li>
 *   <li><b>Output queue (priority by frame number)</b> — the display thread
 *       only drains the next expected frame number. This is the ordering
 *       barrier that preserves linear playback regardless of worker schedule.</li>
 * </ol>
 *
 * <p><b>Critical regions.</b> Only the three JDK concurrent queues are shared
 * between threads. {@code decodeQueue} is producer (decoder) / consumer
 * (workers). {@code outputQueue} is producer (workers) / consumer (FX thread).
 * {@code imagePool} is borrowed by workers and returned by the FX thread.
 * All three are {@link java.util.concurrent.BlockingQueue} implementations,
 * so their internal synchronisation is provided by the JDK.</p>
 *
 * <p><b>Worker count.</b> Currently fixed at 1. Multi-worker was attempted
 * (4 workers) and produced correct pixel output but repeatedly hit an
 * {@code OutOfMemoryError} inside JavaFX 26's Windows event dispatcher when
 * the FX thread fell behind by more than ~500ms under the full 11-effect
 * chain. Per-worker {@link WritableImage} recycling reduced but did not
 * eliminate the failure. The single-worker configuration preserves the
 * off-FX-thread architecture, the audio isolation, and the deterministic
 * effect ordering, while remaining stable on the target platform.</p>
 */
public class FramePipeline {

    private static final int WORKER_COUNT = 1;
    private static final int DECODE_QUEUE_CAPACITY = 8;
    private static final int OUTPUT_QUEUE_CAPACITY = 16;
    private static final int MAX_POOL_SIZE = 8;

    private final List<FrameProcessor> masterProcessors;
    private final BlockingQueue<DecodedFrame> decodeQueue;
    private final PriorityBlockingQueue<ProcessedFrame> outputQueue;
    private final BlockingQueue<WritableImage> imagePool = new LinkedBlockingQueue<>();
    private final ExecutorService decoder;
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public FramePipeline(List<FrameProcessor> masterProcessors) {
        this.masterProcessors = masterProcessors;
        this.decodeQueue = new LinkedBlockingQueue<>(DECODE_QUEUE_CAPACITY);
        this.outputQueue = new PriorityBlockingQueue<>(
                OUTPUT_QUEUE_CAPACITY,
                (a, b) -> Integer.compare(a.frameNumber(), b.frameNumber()));

        this.decoder = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "video-decoder");
            t.setDaemon(true);
            return t;
        });
        this.workers = Executors.newFixedThreadPool(WORKER_COUNT, r -> {
            Thread t = new Thread(r, "video-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public int workerCount() {
        return WORKER_COUNT;
    }

    public void start(FrameSupplier supplier) {
        int[] masterIndexes = new int[masterProcessors.size()];
        for (int i = 0; i < masterProcessors.size(); i++) {
            masterIndexes[i] = masterProcessors.get(i).getInstanceIndex();
        }
        for (int i = 0; i < WORKER_COUNT; i++) {
            workers.submit(() -> runWorker(masterIndexes));
        }
        decoder.submit(() -> runDecoder(supplier));
    }

    private void runDecoder(FrameSupplier supplier) {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Frame frame = supplier.grabFrame();
                if (frame == null) break;
                if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
                    frame.close();
                    continue;
                }

                // Clone before handing off -  FFmpeg's native buffers are reused
                // by the next grab() call, so a raw reference would be unsafe to use
                Frame owned = frame.clone();
                frame.close();

                // Bounded queue: applies backpressure if workers fall behind.
                decodeQueue.put(new DecodedFrame(owned, supplier.getFrameNumber(), supplier.getInfo()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Decoder error: " + e.getMessage());
        } finally {
            //  each worker consumes one sentinel and
            // re-offers it so every worker exits its loop.
            for (int i = 0; i < WORKER_COUNT; i++) {
                try { decodeQueue.put(DecodedFrame.POISON); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void runWorker(int[] masterIndexes) {
        List<FrameProcessor> localProcessors = buildPrivateChain(masterIndexes);
        JavaFXFrameConverter converter = new JavaFXFrameConverter();
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                DecodedFrame decoded = decodeQueue.take();
                if (decoded == DecodedFrame.POISON) {
                    decodeQueue.put(DecodedFrame.POISON);
                    break;
                }
                Frame frame = decoded.frame();
                try {
                    // Each worker owns a private processor chain 
                    // so mutable per-instance state in
                    // effects like FrameMottler cannot race across workers.
                    for (FrameProcessor processor : localProcessors) {
                        processor.process(frame, decoded.info());
                    }
                    Image converted = converter.convert(frame);
                    WritableImage reusable = acquireWritableImage(converted);

                    // Priority queue + frame-number gate on the display side
                    // should be able to be showing ordered output even if this worker runs ahead.
                    outputQueue.put(new ProcessedFrame(
                            reusable, decoded.frameNumber(), System.nanoTime()));
                } finally {
                    frame.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Worker error: " + e.getMessage());
        } finally {
            converter.close();
        }
    }

    /**
     * Returns a {@link WritableImage} containing the pixels of {@code source}.
     *  a pooled image so we do not allocate per frame.
     */
    private WritableImage acquireWritableImage(Image source) {
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();
        WritableImage target = imagePool.poll();
        if (target == null || (int) target.getWidth() != w || (int) target.getHeight() != h) {
            target = new WritableImage(w, h);
        }
        // Recycling the same WritableImage across frames because without this FX
        // thread is forced to upload a fresh texture every frame 
        target.getPixelWriter().setPixels(0, 0, w, h, source.getPixelReader(), 0, 0);
        return target;
    }

    /**
     * Called by the display thread after the frame has been shown. Returning
     * the image lets us reuse its backing store for a future frame.
     */
    public void returnImage(WritableImage image) {
        if (image != null && imagePool.size() < MAX_POOL_SIZE) {
            imagePool.offer(image);
        }
    }

    /**
     * Builds a fresh processor chain for one worker. The instanceIndex values
     * from the master chain are restored before initialise() runs so the
     * per-effect seeds match the single-threaded reference 
     */
    private List<FrameProcessor> buildPrivateChain(int[] masterIndexes) {
        List<FrameProcessor> chain = new ArrayList<>(masterProcessors.size());
        for (int i = 0; i < masterProcessors.size(); i++) {
            FrameProcessor template = masterProcessors.get(i);
            FrameProcessor copy = instantiateLike(template);
            copy.setInstanceIndex(masterIndexes[i]);
            try {
                copy.initialise(template.getVideoInfo());
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialise worker copy: " + e, e);
            }
            chain.add(copy);
        }
        return chain;
    }

    private static FrameProcessor instantiateLike(FrameProcessor template) {
        if (template instanceof FrameNumberer) return new FrameNumberer();
        if (template instanceof FrameScratcher) return new FrameScratcher();
        if (template instanceof FrameFlickerer) return new FrameFlickerer();
        if (template instanceof FrameBlackAndWhiter) return new FrameBlackAndWhiter();
        if (template instanceof FrameYellower) return new FrameYellower();
        if (template instanceof FrameVignetter) return new FrameVignetter();
        if (template instanceof FrameDuster) return new FrameDuster();
        if (template instanceof FrameJitterer) return new FrameJitterer();
        if (template instanceof FrameMottler) return new FrameMottler();
        if (template instanceof FrameBleeder) return new FrameBleeder();
        if (template instanceof FramePepperer) return new FramePepperer();
        throw new IllegalStateException("Unknown processor type: " + template.getClass().getName());
    }

    public ProcessedFrame pollNextFrame(int expectedFrameNumber) {
        ProcessedFrame head = outputQueue.peek();
        if (head != null && head.frameNumber() == expectedFrameNumber) {
            return outputQueue.poll();
        }
        return null;
    }

    public ProcessedFrame pollFirstFrame() {
        return outputQueue.poll();
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        decoder.shutdownNow();
        workers.shutdownNow();
        decodeQueue.clear();
        outputQueue.clear();
        imagePool.clear();
    }

    public interface FrameSupplier {
        Frame grabFrame() throws Exception;
        InfoFrame getInfo();
        int getFrameNumber();
    }

    private record DecodedFrame(Frame frame, int frameNumber, InfoFrame info) {
        static final DecodedFrame POISON = new DecodedFrame(null, -1, null);
    }

    public record ProcessedFrame(WritableImage image, int frameNumber, long preparedAtNs) {
    }
}