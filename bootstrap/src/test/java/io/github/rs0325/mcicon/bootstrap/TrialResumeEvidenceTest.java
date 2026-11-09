package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 実file identityを使い、停止した再開試行の設定snapshotとCASを検証する。 */
class TrialResumeEvidenceTest {
    @TempDir Path temporary;

    @Test void 設定の明示有効化と再有効化は新試行へ固定し旧再開証拠を保持する() throws Exception {
        Context c=new Context(temporary);
        JsonNode state=c.bootstrap.state(),firstSettings=c.recovery.settings(Long.MAX_VALUE);
        assertFalse(firstSettings.path("present").asBoolean());
        save(c,firstSettings);
        Path original=c.evidence().getFirst();byte[] saved=Files.readAllBytes(original);

        c.updates.configure(true,Long.MAX_VALUE);
        JsonNode explicit=c.recovery.settings(Long.MAX_VALUE);
        assertNotEquals(firstSettings,explicit);save(c,explicit);
        assertEquals(2,c.evidence().size());assertArrayEquals(saved,Files.readAllBytes(original));

        c.updates.configure(false,Long.MAX_VALUE);
        assertThrows(IOException.class,()->c.recovery.resume(c.trial));
        assertEquals(state,c.bootstrap.state());assertEquals(2,c.evidence().size());
        c.updates.configure(true,Long.MAX_VALUE);
        JsonNode enabled=c.recovery.settings(Long.MAX_VALUE);
        assertNotEquals(explicit.path("fileKey"),enabled.path("fileKey"));
        save(c,enabled);
        assertEquals(3,c.evidence().size());assertArrayEquals(saved,Files.readAllBytes(original));
        assertEquals(state,c.bootstrap.state());
        List<JsonNode> settings=new ArrayList<>();
        for(Path file:c.evidence())settings.add(Seed.json(Files.readAllBytes(file)).path("settings"));
        assertTrue(settings.contains(firstSettings));assertTrue(settings.contains(explicit));assertTrue(settings.contains(enabled));
    }

    @Test void 再開証拠保存後の設定CAS競合はtrialを保持し次の試行を保存できる() throws Exception {
        Context c=new Context(temporary);
        c.updates.configure(true,Long.MAX_VALUE);
        JsonNode configuration=c.recovery.settings(Long.MAX_VALUE),state=c.bootstrap.state();
        save(c,configuration);Path original=c.evidence().getFirst();byte[] saved=Files.readAllBytes(original);
        c.updates.configure(false,Long.MAX_VALUE);c.updates.configure(true,Long.MAX_VALUE);
        JsonNode next=c.recovery.settings(Long.MAX_VALUE);
        assertNotEquals(configuration,next);
        for(String startup:List.of("failed","prepare")) {
            IOException failure=assertThrows(IOException.class,()->clear(c,state,configuration,startup));
            assertTrue(failure.getMessage().contains("state/settings CAS"));
            assertEquals(state,c.bootstrap.state());assertArrayEquals(saved,Files.readAllBytes(original));
        }
        save(c,next);assertEquals(2,c.evidence().size());assertArrayEquals(saved,Files.readAllBytes(original));
    }

    @Test void 明示resumeは無効設定の間は証拠もtrialも更新しない() throws Exception {
        Context c=new Context(temporary);JsonNode state=c.bootstrap.state();
        c.updates.configure(false,Long.MAX_VALUE);
        IOException failure=assertThrows(IOException.class,()->c.recovery.resume(c.trial));
        assertTrue(failure.getMessage().contains("再開対象trialまたは更新設定"));
        assertEquals(state,c.bootstrap.state());assertTrue(c.evidence().isEmpty());
    }

    private static void save(Context c,JsonNode configuration) throws Exception {
        Method method=TrialRecovery.class.getDeclaredMethod("saveResume",String.class,JsonNode.class,
                JsonNode.class,JsonNode.class,JsonNode.class,String.class,long.class);
        method.setAccessible(true);
        invoke(method,c.recovery,c.trial,c.bootstrap.state(),configuration,c.record,c.proof,"prepare",Long.MAX_VALUE);
    }

    private static void clear(Context c,JsonNode state,JsonNode configuration,String startup) throws Exception {
        Method method=TrialRecovery.class.getDeclaredMethod("clear",String.class,JsonNode.class,JsonNode.class,
                JsonNode.class,Path.class,FileChannel.class,ObjectNode.class,String.class,JsonNode.class,JsonNode.class,long.class);
        method.setAccessible(true);
        invoke(method,c.recovery,c.trial,state.path("pending"),state,configuration,null,null,c.record,startup,c.proof,null,Long.MAX_VALUE);
    }

    private static void invoke(Method method,Object target,Object... arguments) throws Exception {
        try{method.invoke(target,arguments);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }

    private static final class Context {
        final Bootstrap bootstrap;
        final TrialRecovery recovery;
        final UpdatePreparation updates;
        final String trial=UUID.randomUUID().toString();
        final ObjectNode record=Seed.JSON.createObjectNode().put("testReceipt","unchanged");
        final ObjectNode proof=Seed.JSON.createObjectNode().put("testProof","unchanged");

        Context(Path temporary) throws Exception {
            Fixture fixture=new Fixture(temporary.resolve("fixture"),true);
            bootstrap=fixture.bootstrap(temporary.resolve("bootstrap"));bootstrap.repair();
            ObjectNode state=(ObjectNode)bootstrap.state();
            state.set("pending",state.path("active").deepCopy());state.put("trialInvocation",trial);
            bootstrap.atomic(bootstrap.root.resolve("active.json"),state);
            recovery=new TrialRecovery(bootstrap);updates=new UpdatePreparation(bootstrap);
        }

        List<Path> evidence() throws IOException {
            try(var entries=Files.list(bootstrap.root.resolve("logs"))) {
                return entries.filter(path->path.getFileName().toString().startsWith(trial+".resume")
                        &&path.getFileName().toString().endsWith("-v1.json")).sorted().toList();
            }
        }
    }
}
