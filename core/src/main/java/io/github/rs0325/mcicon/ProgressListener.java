package io.github.rs0325.mcicon;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = event -> {};
    void onProgress(ProgressEvent event);
    record ProgressEvent(String phase, String id, int completed, int total) {}
}
