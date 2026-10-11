package io.github.rs0325.mcicon.cli;

import org.junit.jupiter.api.Test;
import io.github.rs0325.mcicon.McIconException;
import picocli.CommandLine;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CliArgumentsTest {
    @CommandLine.Command(name="io-failure")
    static class IoFailure implements Callable<Integer> {
        public Integer call() {
            throw new CompletionException(new McIconException("IO_FAILED","Input/output operation failed",1,
                new IOException("synthetic preparation failure")));
        }
    }
    @Test void debugPreservesExitCodeAndKeepsCauseOffStdout() {
        for(String[] args:new String[][]{{"io-failure"},{"--debug","io-failure"},{"io-failure","--debug"}}) {
            StringWriter out=new StringWriter(),err=new StringWriter();
            CommandLine line=Main.commandLine().addSubcommand(new IoFailure())
                .setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
            assertEquals(1,line.execute(args));
            assertEquals("",out.toString());
            assertTrue(err.toString().startsWith("IO_FAILED: Input/output operation failed"));
            if(args.length==1)assertEquals("IO_FAILED: Input/output operation failed"+System.lineSeparator(),err.toString());
            else assertTrue(err.toString().contains("Caused by: java.io.IOException: synthetic preparation failure"));
        }
    }
    @Test void everySubcommandShowsHelpWithoutRequiredArgumentsOrIo() {
        assertEquals(0,Main.execute("--help"));
        for(String command:new String[]{"versions","list","render","extract","doctor","cache"}) {
            assertEquals(0,Main.execute(command,"--help"),command);
            assertEquals(0,Main.execute(command,"-h"),command);
        }
    }
    @Test void idAndAllAreMutuallyExclusiveWithoutStartingWorker() {
        assertEquals(2,Main.execute("render","--version","1.21.4","--all","--id","stone","--out","out"));
        assertEquals(2,Main.execute("extract","--version","1.21.4","--all","--texture","item/diamond","--out","out"));
    }
    @Test void invalidIdAndSizeReturnUsageErrorsWithoutNetwork() {
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","../stone","--out","out"));
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","stone","--size","63","--out","out"));
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","stone","--item-timeout","0","--out","out"));
    }
}
