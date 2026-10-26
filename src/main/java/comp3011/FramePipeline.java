package comp3011;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * Single background worker pipeline.
 * 
 * All frame grabbing, effect processing, and image conversion happens on one
 * background thread. The JavaFX thread only consumes the resulting Image.
 */
public class FramePipeline {
    private final List<FrameProcessor> processors;
    private final BlockingQueue<ProcessedFrame> processedFrameQueue;
    private final ExecutorService workerThread;
    private volatile boolean running = true;

    public FramePipeline(List<FrameProcessor> processors) {
        this.processors = processors;
        this.processedFrameQueue = new LinkedBlockingQueue<>(8);
        this.workerThread = Executors.newSingleThreadExecutor();
    }

    public void startWorker(FrameSupplier supplier) {
        workerThread.submit(() -> {
            JavaFXFrameConverter converter = new JavaFXFrameConverter();
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    Frame frame = supplier.grabFrame();
                    if (frame == null) break;
                    
                    // Loop until we get a frame with actual image data.
                    // FFmpeg often returns empty/audio frames at the start.
                    if (frame.image == null || frame.image.length == 0 || frame.image[0] == null 
                        || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
                        frame.close();
                        continue; // Skips this frame and grab the next
                    }

                    for (FrameProcessor processor : processors) {
                        processor.process(frame, supplier.getInfo());
                    }

                    Image image = converter.convert(frame);
                    frame.close();

                    processedFrameQueue.put(new ProcessedFrame(
                            image,
                            supplier.getFrameNumber(),
                            supplier.getTimestampUs(),
                            supplier.getLogicalTimestampUs(),
                            supplier.getTargetTimeNs(),
                            System.nanoTime()
                    ));
                }
            } catch (Exception e) {
                System.err.println("Pipeline error: " + e.getMessage());
                e.printStackTrace(); // 
            } finally {
                converter.close();
            }
        });
    }
    
    public void shutdown() {
        running = false;
        workerThread.shutdownNow();
        processedFrameQueue.clear();
    }

    public ProcessedFrame tryTakeProcessedFrame() {
        return processedFrameQueue.poll();
    }

    public interface FrameSupplier {
        Frame grabFrame() throws Exception;
        InfoFrame getInfo();
        int getFrameNumber();
        long getTimestampUs();
        long getLogicalTimestampUs();
        long getTargetTimeNs();
    }

    public record ProcessedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }
}