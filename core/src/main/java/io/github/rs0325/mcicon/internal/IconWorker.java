package io.github.rs0325.mcicon.internal;

import java.nio.file.Path;

/** Narrow export boundary, also used by synthetic crash/recovery tests. */
public interface IconWorker {
    Path render(String id,int size) throws Exception;
    String renderer();
    String agentSha256();
    String imageId();
    Diagnostics.WorkerJava javaRuntime();
}
