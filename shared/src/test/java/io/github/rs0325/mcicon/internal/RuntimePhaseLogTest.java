package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePhaseLogTest {
    @TempDir Path root;
    @Test void pathや非UUID識別子を漏らさず時刻と予算を記録する() throws Exception {
        String job=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();Path tmp=Files.createDirectories(root.resolve("private-user").resolve(job).resolve("tmp"));
        RuntimePhaseLog trace=new RuntimePhaseLog(tmp,"windows","guest-release",attempt);long end=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        trace.event("native-wait",end,Duration.ofSeconds(1).toNanos());trace.event("completion-read",end,0);
        var lines=Files.readAllLines(tmp.resolve(trace.fileName()));assertEquals(2,lines.size());ObjectMapper json=new ObjectMapper();
        var first=json.readTree(lines.getFirst());var last=json.readTree(lines.getLast());assertEquals(job,first.path("jobId").asText());assertEquals(attempt,first.path("attempt").asText());assertEquals(end,first.path("deadlineNanos").asLong());
        assertTrue(first.path("remainingNanos").asLong()>0);assertEquals(1_000_000_000L,first.path("budgetNanos").asLong());assertTrue(last.path("nanoTime").asLong()>=first.path("nanoTime").asLong());assertTrue(first.path("actorPid").asLong()>0);
        assertFalse(lines.toString().contains("private-user"));assertFalse(lines.toString().contains(root.toString()));
        RuntimePhaseLog unknown=new RuntimePhaseLog(root,"core","recover","private-user");unknown.event("recovery-start",Long.MAX_VALUE,0);var value=json.readTree(Files.readString(root.resolve(unknown.fileName())));
        assertFalse(value.has("attempt"));assertFalse(value.has("jobId"));assertTrue(value.path("deadlineNanos").isNull());
    }
    @Test void 保存失敗を固定行で一度だけ通知して元操作へ例外を足さない() {
        RuntimePhaseLog trace=new RuntimePhaseLog(root.resolve("missing"),"core","recover",null);ByteArrayOutputStream bytes=new ByteArrayOutputStream();PrintStream original=System.err;
        try(var error=new PrintStream(bytes)){System.setErr(error);assertDoesNotThrow(()->{trace.event("recovery-start",1,0);trace.event("recovery-failed",1,0);});}
        finally{System.setErr(original);}
        assertEquals("MCICON_PHASE_LOG_UNAVAILABLE"+System.lineSeparator(),bytes.toString());
    }
}
