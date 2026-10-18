package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OperationJournalTest {
    @TempDir Path temporary;
    private ObjectNode identity() {
        ObjectNode value=MacFiles.JSON.createObjectNode();value.put("jobId",UUID.randomUUID().toString());value.put("ownerId",UUID.randomUUID().toString());value.put("descriptorSha256","a".repeat(64));return value;
    }
    @Test void 回収証拠だけではdaemon操作を成功にしない() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            var operation=journal.begin("create");
            assertThrows(java.io.IOException.class,journal::requireSettled);
            Files.writeString(operation.completion(),operation.sha256()+"\n");
            assertThrows(java.io.IOException.class,journal::requireSettled);
            ObjectNode evidence=MacFiles.JSON.createObjectNode().put("containerId","b".repeat(64));
            journal.settled(operation,evidence);journal.markReleased();
            assertTrue(journal.released());assertThrows(java.io.IOException.class,()->journal.begin("start"));
        }
    }
    @Test void release保存後のcore再終了でも再生成を拒否する() throws Exception {
        ObjectNode identity=identity();Path root=temporary.toRealPath();
        try(OperationJournal journal=OperationJournal.acquire(root,identity,Duration.ofSeconds(1))){journal.markReleased();}
        try(OperationJournal journal=OperationJournal.acquire(root,identity,Duration.ofSeconds(1))){journal.markReleased();assertThrows(java.io.IOException.class,()->journal.begin("prepare"));}
        try(OperationJournal journal=OperationJournal.acquire(root,identity(),Duration.ofSeconds(1))){assertThrows(java.io.IOException.class,journal::released);}
    }
    @Test void completionの結合改変とlinkを拒否する() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            var operation=journal.begin("create");ObjectNode evidence=MacFiles.JSON.createObjectNode().put("containerId","b".repeat(64));
            Files.writeString(operation.completion(),"c".repeat(64)+"\n");assertThrows(java.io.IOException.class,()->journal.settled(operation,evidence));
            Files.delete(operation.completion());Path other=temporary.resolve("other");Files.writeString(other,operation.sha256()+"\n");Files.createSymbolicLink(operation.completion(),other);
            assertThrows(java.io.IOException.class,()->journal.settled(operation,evidence));
        }
    }
    @Test void job排他と途中記録を有限失敗として保持する() throws Exception {
        ObjectNode identity=identity();Path root=temporary.toRealPath();
        try(OperationJournal journal=OperationJournal.acquire(root,identity,Duration.ofSeconds(1))) {
            journal.begin("prepare");assertThrows(java.io.IOException.class,()->OperationJournal.acquire(root,identity,Duration.ofMillis(20)));
        }
        try(OperationJournal journal=OperationJournal.acquire(root,identity,Duration.ofSeconds(1))){assertThrows(java.io.IOException.class,journal::markReleased);}
    }
    @Test void startの正常終端証拠とstream回収を別々に要求する() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            ObjectNode resources=MacFiles.JSON.createObjectNode();resources.putArray("containers").addObject().put("name","owned").put("id","a".repeat(64));
            var operation=journal.beginStart("a".repeat(64),resources);assertThrows(java.io.IOException.class,journal::requireRecoverable);
            Files.writeString(operation.completion(),operation.sha256()+"\n");journal.requireRecoverable();assertThrows(java.io.IOException.class,journal::markReleased);
            journal.settleStarts("a".repeat(64),MacFiles.JSON.createObjectNode().put("removedFullId","a".repeat(64)).put("absenceVerified",true));journal.markReleased();assertTrue(journal.released());
        }
    }
    private OperationJournal.Operation start(OperationJournal journal) throws Exception {
        ObjectNode resources=MacFiles.JSON.createObjectNode();resources.putArray("containers").addObject().put("name","owned").put("id","a".repeat(64));return journal.beginStart("a".repeat(64),resources);
    }
    @Test void start回収印のatomic公開を有限待機する() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            var operation=start(journal);Path pending=operation.directory().resolve("client-complete.pending");Files.writeString(pending,operation.sha256()+"\n");
            var publish=new Thread(()->{try{Thread.sleep(40);Files.move(pending,operation.completion(),StandardCopyOption.ATOMIC_MOVE);}catch(Exception failure){throw new AssertionError(failure);}});
            publish.start();try{journal.awaitRecoverable(Duration.ofSeconds(1));}finally{publish.join();}
            assertFalse(Files.exists(pending));assertThrows(java.io.IOException.class,journal::markReleased);
        }
    }
    @Test void 回収待機は期限中断と未知のdaemon終端を保持する() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            start(journal);assertThrows(java.io.IOException.class,()->journal.awaitRecoverable(Duration.ofMillis(20)));
            Thread.currentThread().interrupt();try{assertThrows(java.io.InterruptedIOException.class,()->journal.awaitRecoverable(Duration.ofSeconds(1)));}finally{Thread.interrupted();}
            var create=journal.begin("create");Files.writeString(create.completion(),create.sha256()+"\n");
            assertTrue(assertThrows(java.io.IOException.class,()->journal.awaitRecoverable(Duration.ofSeconds(1))).getMessage().contains("settled.json"));assertThrows(java.io.IOException.class,journal::markReleased);
        }
    }
    @Test void 不正finalとpendingのlink衝突は待たず拒否する() throws Exception {
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),identity(),Duration.ofSeconds(1))) {
            var operation=start(journal);Path pending=operation.directory().resolve("client-complete.pending"),other=temporary.resolve("other");Files.writeString(other,"preserved");Files.createSymbolicLink(pending,other);
            assertThrows(java.io.IOException.class,()->journal.awaitRecoverable(Duration.ofSeconds(1)));Files.delete(pending);
            Files.writeString(operation.completion(),"");assertThrows(java.io.IOException.class,()->journal.awaitRecoverable(Duration.ofSeconds(1)));
            Files.writeString(operation.completion(),operation.sha256()+"\n");Files.writeString(pending,operation.sha256()+"\n");assertThrows(java.io.IOException.class,()->journal.awaitRecoverable(Duration.ofSeconds(1)));assertEquals("preserved",Files.readString(other));
        }
    }

}
