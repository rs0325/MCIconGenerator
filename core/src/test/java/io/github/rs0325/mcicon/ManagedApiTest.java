package io.github.rs0325.mcicon;

import java.nio.file.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedApiTest {
    @TempDir Path root;
    ManagedOptions options(){return new ManagedOptions(root.resolve("runtime"),Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),3072,2048);}
    RenderRequest request(){return new RenderRequest("1.13",List.of(),true,"all",32,root.resolve("out"),false,false,false);}
    @Test void optionsKeepDeclaredBudgetsAndRejectInvalidValues() {
        assertEquals(3072,options().containerMemoryMiB());assertEquals(2048,options().heapMemoryMiB());assertEquals(Duration.ofMinutes(15),ManagedOptions.defaults().preparationTimeout());
        assertThrows(IllegalArgumentException.class,()->new ManagedOptions(root,Duration.ZERO,Duration.ofSeconds(1),Duration.ofSeconds(1),3072,2048));
        assertThrows(IllegalArgumentException.class,()->new ManagedOptions(root,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),2048,2048));
        assertThrows(IllegalArgumentException.class,()->new ManagedOptions(null,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),3072,2048));
    }
    @Test void missingPublishedRuntimeFailsBeforeGameInputsAndDoesNotBecomeHealthyDoctor() throws Exception {
        seedManifest();
        List<String> phases=new ArrayList<>();
        try(var generator=new MCIconGenerator(root.resolve("inputs"),true,event->phases.add(event.phase()),new CancellationToken())) {
            assertFailure("MANAGED_RUNTIME_INVALID",generator.renderManaged(request(),options()));
            assertFailure("MANAGED_RUNTIME_INVALID",generator.doctorManaged("1.13",options()));
            assertFailure("MANAGED_RUNTIME_INVALID",generator.listEntriesManaged("1.13",options()));
            assertFalse(Files.exists(root.resolve("inputs/versions")));assertFalse(Files.exists(options().runtimeRoot()));assertFalse(phases.contains("startup-ready"));
        }
    }
    @Test void rejectedVersionsWinBeforeTrustAndAnyManagedPreparation() throws Exception {
        seedManifest();
        for(String support:List.of("experimental","discovered","unsupported")) {
            try(var generator=new MCIconGenerator(root.resolve("inputs"),true,ProgressListener.NONE,new CancellationToken())) {
                var versions=MCIconGenerator.class.getDeclaredField("versions");versions.setAccessible(true);
                var statuses=VersionService.class.getDeclaredField("support");statuses.setAccessible(true);statuses.set(versions.get(generator),Map.of("1.13",support));
                for(boolean allow:List.of(false,true)) {
                    if(support.equals("experimental")&&allow)continue;
                    RenderRequest request=new RenderRequest("1.13",List.of(),true,"all",32,root.resolve("out"),false,false,allow);
                    assertFailure(support.equals("experimental")?"UNVERIFIED_VERSION":"ADAPTER_UNAVAILABLE",generator.renderManaged(request,options()));
                }
                assertFailure("UNSUPPORTED_VERSION",generator.renderManaged(new RenderRequest("1.12",List.of(),true,"all",32,root.resolve("out"),false,false,true),options()));
                assertFailure("ADAPTER_UNAVAILABLE",generator.renderManaged(new RenderRequest("latest",List.of(),true,"all",32,root.resolve("out"),false,false,true),options()));
            }
            assertFalse(Files.exists(options().runtimeRoot()),"拒否した版でroot・helper・artifact・leaseを準備してはいけません");assertFalse(Files.exists(root.resolve("inputs/versions")));
        }
    }
    private void seedManifest() throws Exception {
        String manifest="""
                {"latest":{"release":"26.4"},"versions":[
                {"id":"1.13","type":"release","releaseTime":"2018-07-18T00:00:00Z","url":"https://fixture.invalid/1.13","sha1":"a"},
                {"id":"26.4","type":"release","releaseTime":"2026-10-15T00:00:00Z","url":"https://fixture.invalid/26.4","sha1":"b"}]}
                """;
        new AssetStore(root.resolve("inputs"),false,new CancellationToken(),uri->new ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .obtain("metadata/version_manifest_v2.json",VersionService.MANIFEST,null,-1,true);
    }
    @Test void allManagedCallsUseInstanceCancellationAndClosedFutures() throws Exception {
        CancellationToken token=new CancellationToken();token.cancel();
        try(var generator=new MCIconGenerator(root,true,ProgressListener.NONE,token)) {assertFailure("CANCELLED",generator.doctorManaged("1.13",options()));}
        MCIconGenerator closed=new MCIconGenerator(root,true,ProgressListener.NONE,new CancellationToken());closed.close();
        assertFailure("GENERATOR_CLOSED",closed.renderManaged(request()));assertFailure("GENERATOR_CLOSED",closed.doctorManaged("1.13"));assertFailure("GENERATOR_CLOSED",closed.listEntriesManaged("1.13"));
        assertFailure("GENERATOR_CLOSED",closed.renderManaged(request(),options()));assertFailure("GENERATOR_CLOSED",closed.doctorManaged("1.13",options()));assertFailure("GENERATOR_CLOSED",closed.listEntriesManaged("1.13",options()));
    }
    private static void assertFailure(String code,CompletableFuture<?> future) throws Exception {var failure=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertEquals(code,((McIconException)failure.getCause()).code());}
    // 既存のnull呼出しにoverloadの曖昧性を追加しないことをコンパイルで確認する。
    private void existingNullCalls(MCIconGenerator generator,RenderRequest request) {generator.render(request,null);generator.doctor("1.13",null);generator.listEntries("1.13",null);}
}
