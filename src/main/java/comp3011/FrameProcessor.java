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

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/**
 * Base class for all the video player's frame-based compositing effects.
 */
public abstract class FrameProcessor {
    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    public InfoVideo getVideoInfo() {
        return videoInfo;
    }

    /** Returns this processor's deterministic instance index, or -1 if unused. */
    public int getInstanceIndex() {
        return -1;
    }

    /** Restores a previously recorded instance index. */
    public void setInstanceIndex(int index) {
        // no-op for processors that do not use an instance index
    }

    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    public abstract void process(Frame frame, InfoFrame info) throws Exception;
}