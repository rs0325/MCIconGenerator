package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderSupportTest {
    @TempDir Path root;
    private final RuntimeException prepared=new RuntimeException("入力準備へ到達");
    private final WorkerOptions worker=new WorkerOptions(Path.of("missing-agent.jar"),"unused","unused",Duration.ofSeconds(1),Duration.ofSeconds(1));

    @Test void currentExperimentalRequiresOptInBeforeAnyInputPreparation() throws Exception {
        try(var generator=generator(null)) {
            var rejected=failure(generator,"1.13",false);
            assertEquals("UNVERIFIED_VERSION",((McIconException)rejected).code());
            assertEquals(2,((McIconException)rejected).exitCode());
            assertFalse(Files.exists(root.resolve("versions")));
            assertSame(prepared,failure(generator,"1.13",true));
        }
    }
    @Test void verifiedAllowsBothFlagsAndNeverLetsLatestFallBackToIt() throws Exception {
        try(var generator=generator(Map.of("1.13","verified"))) {
            for(boolean allow:new boolean[]{false,true}) {
                assertSame(prepared,failure(generator,"1.13",allow));
                var rejected=(McIconException)failure(generator,"latest",allow);
                assertEquals("ADAPTER_UNAVAILABLE",rejected.code());
                assertTrue(rejected.getMessage().contains("26.4"));
                assertEquals(2,rejected.exitCode());
            }
            assertFalse(Files.exists(root.resolve("versions")));
        }
    }
    @Test void optInDoesNotPermitDiscoveredOrUnsupportedVersions() throws Exception {
        for(String support:List.of("discovered","unsupported")) {
            try(var generator=generator(Map.of("1.13",support))) {
                for(boolean allow:new boolean[]{false,true}) {
                    var rejected=(McIconException)failure(generator,"1.13",allow);
                    assertEquals("ADAPTER_UNAVAILABLE",rejected.code());
                    assertEquals(2,rejected.exitCode());
                }
                assertFalse(Files.exists(root.resolve("versions")));
            }
        }
    }
    private Throwable failure(MCIconGenerator generator,String version,boolean allow) {
        var request=new RenderRequest(version,List.of(ResourceId.parse("stone")),false,"all",64,root.resolve("out"),false,false,allow);
        return assertThrows(ExecutionException.class,()->generator.render(request,worker).get(5,TimeUnit.SECONDS)).getCause();
    }
    private MCIconGenerator generator(Map<String,String> support) throws Exception {
        String manifest="""
                {"latest":{"release":"26.4"},"versions":[
                {"id":"1.13","type":"release","releaseTime":"2018-07-18T00:00:00Z","url":"https://example.invalid/1.13","sha1":"a"},
                {"id":"26.4","type":"release","releaseTime":"2026-10-15T00:00:00Z","url":"https://example.invalid/26.4","sha1":"b"}]}
                """;
        new AssetStore(root,false,new CancellationToken(),uri->new ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .obtain("metadata/version_manifest_v2.json",VersionService.MANIFEST,null,-1,true);
        var generator=new MCIconGenerator(root,true,event->{throw prepared;},new CancellationToken());
        if(support!=null) {
            // 対応表を昇格せず、解決済みsupportだけをfixtureへ置換する。
            var versions=MCIconGenerator.class.getDeclaredField("versions");versions.setAccessible(true);
            var statuses=VersionService.class.getDeclaredField("support");statuses.setAccessible(true);
            statuses.set(versions.get(generator),support);
        }
        return generator;
    }
}
