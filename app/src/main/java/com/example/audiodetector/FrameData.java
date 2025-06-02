package com.example.audiodetector;

import java.nio.ByteBuffer;

public class FrameData {
    public ByteBuffer data;
    public long pts;
    public boolean isAudio;

    public FrameData(ByteBuffer data, long pts, boolean isAudio) {
        this.data = data;
        this.pts = pts;
        this.isAudio = isAudio;
    }
}