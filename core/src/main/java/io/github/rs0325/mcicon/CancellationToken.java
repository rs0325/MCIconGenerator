package io.github.rs0325.mcicon;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void check() {
        if (isCancelled() || Thread.currentThread().isInterrupted())
            throw new McIconException("CANCELLED", "Operation cancelled", 1);
    }
}
