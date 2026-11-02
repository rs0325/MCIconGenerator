package io.github.rs0325.mcicon.helper.windows;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTest {
    @TempDir Path temporary;
    @Test void stdout読取不能でもstderrの固定reasonを失わない() throws Exception {
        Path err=temporary.resolve("stderr.bin");Files.writeString(err,"MCICON_GUEST_REASON=DAEMON_PREPARATION_UNKNOWN\n");var summary=WindowsFiles.JSON.createObjectNode();Commands.outputSummary(temporary.resolve("absent"),err,1024*1024,summary);assertTrue(summary.path("stdoutUnknown").asBoolean());assertEquals("DAEMON_PREPARATION_UNKNOWN",summary.path("guestReason").asText());assertFalse(summary.has("stderrUnknown"));
    }
    @Test void stdoutだけの超過を通常とfetchの適用上限で保存とprotocolへ渡す() throws Exception {
        Path out=temporary.resolve("stdout.bin"),err=temporary.resolve("stderr.bin"),saved=temporary.resolve("diagnostic.json");Files.write(err,new byte[0]);
        for(long limit:new long[]{1024*1024,64L*1024*1024})for(long size:new long[]{limit,limit+1}) {
            try(var file=new java.io.RandomAccessFile(out.toFile(),"rw")){file.setLength(size);}
            var summary=WindowsFiles.JSON.createObjectNode();Commands.outputSummary(out,err,limit,summary);WindowsFiles.write(saved,summary);
            var actual=WindowsFiles.json(saved);assertEquals(size,actual.path("stdoutBytes").asLong());assertEquals(limit,actual.path("stdoutLimitBytes").asLong());assertEquals(size>limit,actual.path("stdoutLimitExceeded").asBoolean());assertEquals(size>limit,actual.path("outputLimitExceeded").asBoolean());assertFalse(actual.path("stderrLimitExceeded").asBoolean());assertEquals(0,actual.path("stderrBytes").asLong());
            var response=io.github.rs0325.mcicon.helper.HelperProtocol.response("failed",java.util.List.of(new io.github.rs0325.mcicon.helper.HelperProtocol.Diagnostic("WINDOWS_RUNTIME_FAILED","Windows入力/実行失敗: "+summary,false,false)));
            String message=WindowsFiles.JSON.readTree(response).path("diagnostics").get(0).path("detail").asText();assertTrue(message.contains("\"stdoutBytes\":"+size));assertTrue(message.contains("\"stdoutLimitBytes\":"+limit));assertTrue(message.contains("\"outputLimitExceeded\":"+(size>limit)));assertTrue(response.length<16000);
        }
    }
    @Test void 過大stderrの固定reasonと末尾だけを有限保存して任意文字列を除く() throws Exception {
        Path file=temporary.resolve("stderr.bin");Files.writeString(file,"MCICON_GUEST_REASON=DAEMON_PREPARATION_UNKNOWN\n"+"secret-context-not-retained".repeat(50000)+"\nnativeJob phase=drain-empty childExit=23 active=0 queries=1\n");
        var summary=WindowsFiles.JSON.createObjectNode();Commands.stderrSummary(file,summary);
        assertEquals("DAEMON_PREPARATION_UNKNOWN",summary.path("guestReason").asText());assertTrue(summary.path("stderrTruncated").asBoolean());assertTrue(summary.path("outputLimitExceeded").asBoolean());assertEquals(Files.size(file),summary.path("stderrBytes").asLong());assertTrue(summary.path("stderr").asText().contains("nativeJob phase=drain-empty"));assertFalse(summary.toString().contains("secret-context"));assertTrue(summary.toString().length()<1000);
    }
    @Test void 空出力と不正な固定reasonやnative行を秘密値として除外する() throws Exception {
        Path file=temporary.resolve("stderr.bin");
        for(String text:new String[]{"","MCICON_GUEST_REASON=private-value\nnativeJob phase=secret-context childExit=token active=1 queries=1\n"}) {
            Files.writeString(file,text);var summary=WindowsFiles.JSON.createObjectNode();Commands.stderrSummary(file,summary);assertFalse(summary.has("guestReason"));assertEquals("",summary.path("stderr").asText());assertFalse(summary.path("stderrTruncated").asBoolean());
        }
    }
    @Test void bridgeのcommandEmptyは肯定した子0と実exit0だけ受け入れる() throws Exception {
        Path prefix=temporary.resolve("native-test");var client=WindowsFiles.JSON.createObjectNode().put("pid",11).put("started",22).put("boot",OwnershipTest.BOOT);
        var proof=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","command-empty").put("childKnown",true).put("childExit",0).put("plannedGuardExit",0).put("jobEmpty",true);proof.set("client",client);
        WindowsFiles.write(Path.of(prefix+".completion.json"),proof);assertEquals(proof,WindowsKeeper.completion(prefix,client,0));
        proof.put("childExit",1);WindowsFiles.write(Path.of(prefix+".completion.json"),proof);assertThrows(java.io.IOException.class,()->WindowsKeeper.completion(prefix,client,0));
    }
}
