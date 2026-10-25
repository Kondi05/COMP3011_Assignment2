package comp3011;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages multi-threaded processing pipeline for video frames.
 * 
 * Pipeline : three stages:
 * 1. Producer (decoder) enqueues raw frames.
 * 2. Workers (effect processors) apply effects and enqueue processed frames.
 * 3. Consumer (JavaFX thread) dequeues processed frames for display.
 */
public class FramePipeline {
    private final List<FrameProcessor> processors;
    private final BlockingQueue<RawFrame> rawFrameQueue;
    private final BlockingQueue<ProcessedFrame> processedFrameQueue;
    private final ExecutorService workerPool;

    public FramePipeline(List<FrameProcessor> processors) {
        this.processors = processors;
        // Bounded queue to prevent memory blowup if workers fall behind
        this.rawFrameQueue = new LinkedBlockingQueue<>(8);
        this.processedFrameQueue = new LinkedBlockingQueue<>(8);
        // available cores for worker threads
        int cores = Runtime.getRuntime().availableProcessors();
        this.workerPool = Executors.newFixedThreadPool(Math.max(2, cores - 1));
    }
    
    /**
     * Starts worker threads that process raw frames
     * each worker continuously takes a raw frame from the queue, applies all effects,
     * converts it to a JavaFX Image, and puts it into the processed frame queue
     */
    public void startWorkers() {
        for (int i = 0; i < Runtime.getRuntime().availableProcessors() - 1; i++) {
            workerPool.submit(() -> {
                // one converter per worker thread lifetime to avoid memory leaks
                org.bytedeco.javacv.JavaFXFrameConverter converter = 
                    new org.bytedeco.javacv.JavaFXFrameConverter();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        // Blocking call: waits until a raw frame is available
                        RawFrame raw = rawFrameQueue.take();
                        
                        // Effects in order applied
                        try {
                            for (FrameProcessor processor : processors) {
                                processor.process(raw.frame(), raw.info());
                            }
                        } catch (Exception e) {
                            System.err.println("Frame processing failed: " + e.getMessage());
                            continue; 
                        }
                        
                        // Convert to JavaFX Image 
                        javafx.scene.image.Image image = converter.convert(raw.frame());
                        
                        // Put processed frame in the output queue
                        processedFrameQueue.put(new ProcessedFrame(
                            image,
                            raw.info().frameNumber(),
                            raw.info().timestampUs(),
                            0, // logical timestamp set later by model
                            raw.targetTimeNs(),
                            System.nanoTime()
                        ));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    converter.close(); // Clean up when worker terminates
                }
            });
        }
    }
    
    /**
     * Gracefully shuts down worker pool and clears queues
     * call when playback stops, seeks, or the app closes
     */
    public void shutdown() {
        workerPool.shutdownNow(); // Interrupts workers
        rawFrameQueue.clear();
        processedFrameQueue.clear();
    }

    /**
     * Submits a raw frame to the processing queue
     * Blocks if the queue is full 
     */
    public void submitRawFrame(RawFrame raw) throws InterruptedException {
        rawFrameQueue.put(raw);
    }

    /**
     * Takes a processed frame from the output queue.
     * Blocks until one is available.
     */
    public ProcessedFrame takeProcessedFrame() throws InterruptedException {
        return processedFrameQueue.take();
    }
    
    /**
     * Checks if a processed frame is ready without blocking.
     */
    public ProcessedFrame tryTakeProcessedFrame() {
        return processedFrameQueue.poll();
    }
    
    // Holds a frame before processing
    public record RawFrame(
            org.bytedeco.javacv.Frame frame,
            InfoFrame info,
            long targetTimeNs,
            long preparedAtNs) {
    }

    // Holds a frame after processing
    public record ProcessedFrame(
            javafx.scene.image.Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }
    
}