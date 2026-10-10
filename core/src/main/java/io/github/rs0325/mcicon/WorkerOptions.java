package io.github.rs0325.mcicon;
import java.nio.file.Path;
import java.time.Duration;
public record WorkerOptions(Path agentJar,String dockerContext,String dockerImage,Duration startupTimeout,Duration itemTimeout) {
    public WorkerOptions {
        if(agentJar==null||startupTimeout==null||itemTimeout==null||startupTimeout.isNegative()||startupTimeout.isZero()||itemTimeout.isNegative()||itemTimeout.isZero())
            throw new IllegalArgumentException("Worker path and positive timeouts are required");
    }
}
