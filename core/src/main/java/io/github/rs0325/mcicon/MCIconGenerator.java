package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Application-facing API. No Minecraft classes or authentication values cross this boundary. */
public final class MCIconGenerator implements AutoCloseable {
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mcicon-job").factory());
    private final AssetStore assets;
    private final VersionService versions;
    private final CancellationToken cancellation;
    private final ProgressListener progress;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private boolean closed;
    public MCIconGenerator(Path cache,boolean offline,ProgressListener progress,CancellationToken cancellation) {
        this.assets=new AssetStore(cache,offline,cancellation);this.versions=new VersionService(assets);this.cancellation=cancellation;this.progress=progress;
    }
    public CompletableFuture<List<VersionInfo>> listVersions() {return submit(versions::list);}
    public CompletableFuture<DiagnosticReport> doctor(String version,WorkerOptions options) {return submit(()->Diagnostics.doctor(versions,version,options));}
    public CompletableFuture<CacheReport> cache(boolean verify) {return submit(()->Diagnostics.cache(assets.root(),verify,cancellation));}
    public CompletableFuture<List<CatalogEntry>> listEntries(String version,WorkerOptions options) {
        return submit(()-> {
            var runtime=new RuntimePreparer(assets,versions,cancellation).prepare(versions.resolve(version));
            try(var worker=new WorkerClient(runtime,assets.root(),options,cancellation)){return worker.catalog();}
        });
    }
    public CompletableFuture<ExportResult> render(RenderRequest request,WorkerOptions options) {
        return submit(()-> {
            VersionInfo version=versions.resolve(request.version());
            if(!request.allowExperimental())throw new McIconException("UNVERIFIED_VERSION","Rendering adapters are experimental; opt in with --allow-experimental",2);
            progress.onProgress(new ProgressListener.ProgressEvent("prepare",version.id(),0,1));
            var runtime=new RuntimePreparer(assets,versions,cancellation).prepare(version);
            try(var worker=new WorkerClient(runtime,assets.root(),options,cancellation)) {
                return new IconExporter(cancellation,progress).export(runtime,worker,worker.catalog(),request);
            }
        });
    }
    public CompletableFuture<ExportResult> extract(ExtractRequest request) {
        return submit(()-> {
            VersionInfo version=versions.resolve(request.version());
            progress.onProgress(new ProgressListener.ProgressEvent("prepare",version.id(),0,1));
            Path client=versions.client(version);
            return new RawExporter(cancellation,progress).export(version.id(),client,request);
        });
    }
    private synchronized <T> CompletableFuture<T> submit(Callable<T> task) {
        if(closed)return CompletableFuture.failedFuture(new McIconException("GENERATOR_CLOSED","Generator is closed; create a new instance",1));
        CompletableFuture<T> future=new CompletableFuture<>();pending.add(future);
        future.whenComplete((result,error)->pending.remove(future));
        executor.execute(()-> {
            if(future.isDone())return;
            try {cancellation.check();future.complete(task.call());}
            catch(Throwable error) {
                future.completeExceptionally(error instanceof RuntimeException||error instanceof Error?error
                        :new McIconException("IO_FAILED","Input/output operation failed",1,error));
            }
        });
        return future;
    }
    /** Cancels active/queued futures and requests worker cleanup. Completed results remain available. */
    @Override public synchronized void close() {
        if(closed)return;
        closed=true;cancellation.cancel();
        pending.forEach(future->future.cancel(false));executor.shutdownNow();
    }
}
