package com.example.audiodetector;

class Segment {
    public final String label;
    public final float startTimeSec;
    public float endTimeSec;
    public final float confidence;

    public Segment(String label, float start, float end, float confidence) {
        this.label = label;
        this.startTimeSec = start;
        this.endTimeSec = end;
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        return "[" + startTimeSec + "s - " + endTimeSec + "s]: " + label;
    }
}