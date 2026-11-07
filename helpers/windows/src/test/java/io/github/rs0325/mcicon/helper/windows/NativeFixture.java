package io.github.rs0325.mcicon.helper.windows;

import java.nio.file.*;
import java.util.*;

/** Java監督fixture。子孫を作らず、Windows Jobの実証には用いない。 */
public final class NativeFixture {
    public static void main(String[] args) throws Exception {
        Path record=Path.of(args[3]);var value=WindowsFiles.JSON.createObjectNode().put("parent",Long.parseLong(args[1])).put("parentStarted",Long.parseLong(args[2])).put("pid",ProcessHandle.current().pid()).put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()).put("boot",OwnershipTest.BOOT);
        Files.write(record.resolveSibling(record.getFileName()+".state.json"),WindowsFiles.JSON.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
        int offset=args[0].equals("exec")?5:8;String operation=args[offset];
        if(operation.equals("/fixture-guest-timeout")) {
            System.in.readAllBytes();System.err.println("secret C:/private/token");System.err.println("MCICON_GUEST_FAILURE=TimeoutExpired timeout=true");System.err.println("MCICON_GUEST_REASON=GUEST_OPERATION_FAILED");WindowsFiles.write(Path.of(record+".done.json"),value);System.exit(23);
        }
        if(operation.equals("/fixture-stdout-large")) {
            System.in.readAllBytes();System.out.write(new byte[1024*1024+1]);WindowsFiles.write(Path.of(record+".done.json"),value);return;
        }
        if(operation.startsWith("/fixture-input-")) {
            if(!operation.endsWith("empty"))System.err.println("MCICON_GUEST_REASON=DAEMON_RESTART_DENIED");
            if(operation.endsWith("large")){System.err.print("secret-not-retained".repeat(70000));System.err.println("\nnativeJob phase=drain-empty childExit=23 active=0 queries=1");}
            System.err.flush();
            if(operation.endsWith("timeout")){Thread.sleep(30000);return;}
            if(!operation.endsWith("missing")) {
                var proof=WindowsFiles.JSON.createObjectNode().put("schema",1).put("phase","drain-empty").put("childKnown",true).put("childExit",23).put("plannedGuardExit",23).put("jobEmpty",true);proof.set("client",value.deepCopy());
                if(operation.endsWith("mismatch"))((com.fasterxml.jackson.databind.node.ObjectNode)proof.path("client")).put("pid",1);
                WindowsFiles.write(Path.of(record+".completion.json"),proof);WindowsFiles.write(Path.of(record+".done.json"),value);
            }
            if(operation.endsWith("savefail"))Files.createDirectory(Path.of(record+".diagnostic.json"));
            System.exit(23);
        }
        if(args[5].endsWith("fixture-sleep"))Thread.sleep(30000);
        System.out.write(WindowsFiles.JSON.writeValueAsBytes(Arrays.asList(args).subList(5,args.length)));Files.write(record.resolveSibling(record.getFileName()+".done.json"),WindowsFiles.JSON.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }
}
