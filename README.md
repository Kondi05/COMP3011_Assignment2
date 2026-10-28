# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Concurrency Design & Correctness

### Architecture
Three-stage pipeline:
1. **Decoder thread (1)** — sole owner of the non-thread-safe `FFmpegFrameGrabber`. Emits cloned frames in order into a bounded `LinkedBlockingQueue` (capacity 8).
2. **Worker thread(s)** — pulls a cloned frame, applies its own private chain of `FrameProcessor` instances in CLI order, converts to a JavaFX `Image`, then copies pixels into a recycled `WritableImage`.
3. **Display (JavaFX FX thread via `AnimationTimer`)** — drains only the next expected frame number from a `PriorityBlockingQueue` ordered by frame number.

### Shared state & critical regions
decodeQueue — LinkedBlockingQueue bounded at capacity 8. Producer is the decoder thread; consumer is the worker thread(s). Defence: JDK queue lock plus bounded capacity (backpressure).

outputQueue — PriorityBlockingQueue bounded at capacity 16. Producer is the worker thread(s); consumer is the FX thread. Defence: JDK queue lock, ordered by frame number so out-of-order arrivals sit in the queue until their turn.

imagePool — LinkedBlockingQueue bounded at capacity 8. Producer is the FX thread (returns images after display); consumer is the worker (borrows images for the next frame). Defence: JDK queue lock plus bounded capacity.

pendingAudio — ConcurrentLinkedQueue. Producer is the decoder thread; consumer is the audio-writer thread. Defence: lock-free CAS operations, no user-level synchronisation needed.

AudioPlayer — Java Sound output line. Accessed by the audio-writer thread and by queueAudio (called from the decoder thread). Defence: model-instance synchronized on queueAudio and writeDueAudio. Both methods are short and never perform blocking I/O while holding the lock.


### Frame ordering guarantee
`nextExpectedFrameNumber` on the FX thread is only advanced when the exact next-sequential frame is received from `FramePipeline.pollNextFrame(expected)`. If a later frame arrives first (possible with multiple workers), it stays queued by the priority queue until its number is expected. This is the ordering barrier that prevents frame tearing and out-of-order display.

### Determinism guarantee
TITAN checks pixel-level correspondence against the single-threaded reference. The following are preserved:
- **Effect order** — `createFrameProcessors` constructs processors in CLI order on the main thread before any worker starts.
- **`instanceIndex`** — each processor's `static int nextInstanceIndex` counter assigns the same index sequence as the reference. `FramePipeline.buildPrivateChain` snapshots those index values from the master chain and restores them into each worker's fresh copy **before** `initialise()` runs, so `mediaSeed = saltedMediaSeed(mediaName, instanceIndex + SALT)` matches exactly.
- **Frame cloning** — the decoder clones each grabbed frame because FFmpeg recycles its native image buffers across `grab()` calls. Without cloning, workers would see the next frame's pixels (data race on shared native memory).

### Deadlock freedom
Each pipeline stage owns a single queue. There is no nested acquisition of two queues by any thread, so the four hold-and-wait conditions for deadlock cannot all be satisfied. Shutdown uses `shutdownNow()` + poison-pill sentinels; workers re-offer the sentinel so every worker exits cleanly.

### Audio isolation
Audio writes run on a dedicated bounded `ThreadPoolExecutor` (`audio-writer`, `DiscardPolicy`, queue capacity 1). If the Java Sound line blocks, submissions are dropped rather than stalling the JavaFX thread. This was the root cause of the FX-thread stalls observed during development.

### Engineering judgement — single-worker choice
Multi-worker (4 workers) was implemented and tested. It produced correct pixel output but repeatedly caused `OutOfMemoryError` inside JavaFX 26's Windows event dispatcher (`InvokeLaterDispatcher`) when the FX thread fell >500 ms behind under the full 11-effect chain. Per-worker `WritableImage` recycling reduced but did not eliminate the failure. The shipped configuration is **1 worker** — off the FX thread, full pipeline, audio isolated — verified stable at 24 fps real-time on the full effect chain.

### Regression testing
`src/test/java/comp3011/CommandLineControllerTest.java` — CLI parser tests including stacked short flags, long forms, and mixed order.
`src/test/java/comp3011/CommandLineControllerExtendedTest.java` — extended regression: short/long equivalence, options surrounding the video file argument, and help precedence.