package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratorLifecycleTest {
    @TempDir Path directory;
    @Test void closeCompletesActiveAndQueuedFuturesInsteadOfLeavingQueuedJobsPending() throws Exception {
        String manifest="""
                {"latest":{"release":"1.13"},"versions":[{"id":"1.13","type":"release",
                "releaseTime":"2018-07-18T00:00:00Z","url":"https://example.invalid/version","sha1":"a"}]}
                """;
        new AssetStore(directory,false,new CancellationToken(),uri->new ByteArrayInputStream(manifest.getBytes()))
                .obtain("metadata/version_manifest_v2.json",VersionService.MANIFEST,null,-1,true);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ProgressListener progress=event->{entered.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        MCIconGenerator generator=new MCIconGenerator(directory,true,progress,new CancellationToken());
        try {
            var active=generator.extract(new ExtractRequest("1.13",List.of(),true,directory.resolve("out"),false,false));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var queued=generator.cache(false);
            generator.close();
            assertTrue(queued.isDone(),"Queued future must terminate on close");
            assertTrue(active.isDone(),"Active future must terminate on close");
            assertThrows(CancellationException.class,queued::join);assertThrows(CancellationException.class,active::join);
        } finally {release.countDown();generator.close();}
    }
    @Test void callsAfterCloseReturnACompletedTypedFailure() {
        MCIconGenerator generator=new MCIconGenerator(directory,true,ProgressListener.NONE,new CancellationToken());
        generator.close();generator.close();
        var future=generator.listVersions();assertTrue(future.isDone());
        CompletionException failure=assertThrows(CompletionException.class,future::join);
        assertEquals("GENERATOR_CLOSED",((McIconException)failure.getCause()).code());
    }
    @Test void closePreservesResultsOfAlreadyCompletedJobs() throws Exception {
        MCIconGenerator generator=new MCIconGenerator(directory,true,ProgressListener.NONE,new CancellationToken());
        try {
            var future=generator.cache(false);var result=future.get(5,TimeUnit.SECONDS);
            generator.close();assertFalse(future.isCancelled());assertEquals(result,future.join());
        } finally {generator.close();}
    }
}
