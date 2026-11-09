package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class StoppedRecoveryTest {
    @TempDir Path temporary;
    RebootRecoveryTest fixture;
    MacRuntimeTest.Job job;
    OperationJournal.Operation stop;
    @BeforeEach void setup() throws Exception {
        fixture=new RebootRecoveryTest();fixture.temporary=temporary;fixture.setup();
        Path probe=fixture.support.resolve("host-probe");Files.writeString(probe,Files.readString(probe).replace(RebootRecoveryTest.NOW,RebootRecoveryTest.OLD));
        Files.writeString(fixture.fixture.cache().instance().resolve("boot-session.tmp"),RebootRecoveryTest.OLD);
        job=fixture.oldJob(false);ObjectNode value=job.lease().read();
        try(var journal=journal()) {
            for(var operation:journal.pendingStarts().values())Files.writeString(operation.completion(),operation.sha256()+"\n");
            var remove=journal.begin("remove");Files.writeString(remove.completion(),remove.sha256()+"\n");ObjectNode evidence=MacFiles.JSON.createObjectNode().put("removedFullId",RebootRecoveryTest.ID).put("absenceVerified",true);journal.settled(remove,evidence);journal.settleStarts(RebootRecoveryTest.ID,evidence);
            stop=journal.begin("stop");Files.writeString(stop.completion(),stop.sha256()+"\n");
        }
        ObjectNode mac=(ObjectNode)value.path("resources").path("macos");mac.put("phase","releasing");mac.putArray("containers");MacFiles.write(job.command().lease(),value);
    }
    @AfterEach void cleanup() throws Exception {fixture.cleanup();}
    OperationJournal journal() throws Exception {return OperationJournal.acquire(job.command().lease().getParent().resolve("tmp"),job.lease().read(),Duration.ofSeconds(1));}
    void release() throws Exception {fixture.runtime(job).release();}
    void rejected() throws Exception {byte[] before=Files.readAllBytes(job.command().lease());assertThrows(IOException.class,this::release);assertArrayEquals(before,Files.readAllBytes(job.command().lease()));assertFalse(Files.exists(stop.directory().resolve("settled.json")));}
    @Test void 未確定stopはStoppedを観測して元exitを捏造せず回復する() throws Exception {
        byte[] intent=Files.readAllBytes(stop.directory().resolve("intent.json")),complete=Files.readAllBytes(stop.completion());release();
        assertTrue(job.lease().read().path("resources").isEmpty());ObjectNode saved=(ObjectNode)MacFiles.json(stop.directory().resolve("settled.json"));assertEquals("observed-stopped",saved.path("evidence").path("recoveryKind").asText());assertFalse(saved.path("evidence").has("exitCode"));assertArrayEquals(intent,Files.readAllBytes(stop.directory().resolve("intent.json")));assertArrayEquals(complete,Files.readAllBytes(stop.completion()));
        release();try(var journal=journal()){assertTrue(journal.released());journal.requireSettled();}
    }
    @Test void 完了印だけではRunningを成功にしない() throws Exception {fixture.lima("Running");rejected();}
    @Test void 元coreまたはhostAgentがliveなら拒否する() throws Exception {
        ObjectNode value=job.lease().read();ObjectNode mac=(ObjectNode)value.path("resources").path("macos");mac.withObject("hostAgent").put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());MacFiles.write(job.command().lease(),value);rejected();
    }
    @Test void 別bootとmachine置換を拒否する() throws Exception {
        Path machine=fixture.fixture.cache().instance().resolve("vz-identifier");Files.writeString(machine,"replaced-machine");rejected();
        Files.writeString(machine,"fixture-machine");Files.writeString(fixture.fixture.cache().instance().resolve("boot-session.tmp"),RebootRecoveryTest.NOW);rejected();
    }
    @Test void container残存と未知actorを拒否する() throws Exception {
        ObjectNode value=job.lease().read();value.withObject("resources").withObject("macos").withArray("containers").addObject().put("id",RebootRecoveryTest.ID).put("name","old-worker");MacFiles.write(job.command().lease(),value);rejected();
        value.withObject("resources").withObject("macos").putArray("containers");MacFiles.write(job.command().lease(),value);Files.writeString(stop.directory().resolve("unknown-actor"),"unknown");rejected();
    }
    @Test void 完了印改変とpending併存を拒否する() throws Exception {
        Files.writeString(stop.completion(),"a".repeat(64)+"\n");rejected();Files.writeString(stop.completion(),stop.sha256()+"\n");Files.writeString(stop.directory().resolve("client-complete.pending"),stop.sha256()+"\n");rejected();
    }
    @Test void pendingCreateと複数stopを拒否する() throws Exception {try(var journal=journal()){journal.begin("create");}rejected();}
    @Test void 別ownerのintentを拒否する() throws Exception {Path intent=stop.directory().resolve("intent.json");ObjectNode value=(ObjectNode)MacFiles.json(intent);value.withObject("identity").put("ownerId",java.util.UUID.randomUUID().toString());MacFiles.write(intent,value);rejected();}
    @Test void 他deadまたはlive不明leaseを拒否する() throws Exception {fixture.fixture.job(true);rejected();}
    @Test void readClientの終端不足を拒否する() throws Exception {Files.createDirectory(job.command().lease().getParent().resolve("tmp/read-client-unknown"));rejected();}
    @Test void settled保存後の途中終了から再観測して回復する() throws Exception {
        MacRuntime runtime=fixture.runtime(job);
        try(var journal=journal();var lock=runtime.cache.lock(Duration.ofSeconds(1))) {
            assertTrue(StoppedRecovery.recover(job.command(),fixture.fixture.executable,job.lease().read(),runtime.cache,runtime.host,runtime.payload,runtime.commands,io.github.rs0325.mcicon.helper.LeaseFile.trust(job.command(),fixture.fixture.executable),journal));
        }
        byte[] settled=Files.readAllBytes(stop.directory().resolve("settled.json"));release();assertTrue(job.lease().read().path("resources").isEmpty());assertArrayEquals(settled,Files.readAllBytes(stop.directory().resolve("settled.json")));
    }
    @Test void settled後markReleased前の中断でも再開する() throws Exception {
        MacRuntime runtime=fixture.runtime(job);
        try(var journal=journal();var lock=runtime.cache.lock(Duration.ofSeconds(1))) {assertTrue(StoppedRecovery.recover(job.command(),fixture.fixture.executable,job.lease().read(),runtime.cache,runtime.host,runtime.payload,runtime.commands,io.github.rs0325.mcicon.helper.LeaseFile.trust(job.command(),fixture.fixture.executable),journal));}
        Files.delete(job.command().lease().getParent().resolve("tmp/macos-operations/released.json"));release();assertTrue(job.lease().read().path("resources").isEmpty());
    }
    @Test void hostAgentのPID再利用を終了と誤認しない() throws Exception {
        ObjectNode value=job.lease().read();value.withObject("resources").withObject("macos").withObject("hostAgent").put("pid",ProcessHandle.current().pid());MacFiles.write(job.command().lease(),value);rejected();
    }
    @Test void 複数stopは一件の限定回復へまとめない() throws Exception {try(var journal=journal()){journal.begin("stop");}rejected();}
}
