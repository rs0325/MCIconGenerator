package io.github.rs0325.mcicon.cli;

import org.junit.jupiter.api.Test;
import io.github.rs0325.mcicon.McIconException;
import picocli.CommandLine;
import java.io.*;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliArgumentsTest {
    @CommandLine.Command(name="io-failure")
    static class IoFailure implements Callable<Integer> {
        final Throwable failure;
        final String message;
        IoFailure(Throwable failure){this(failure,"secret outer message");}
        IoFailure(Throwable failure,String message){this.failure=failure;this.message=message;}
        @CommandLine.Option(names="--json") boolean json;
        public Integer call() {
            throw new CompletionException(new McIconException("IO_FAILED",message,1,failure));
        }
    }
    @CommandLine.Command(name="other-failure")
    static class OtherFailure implements Callable<Integer> {
        public Integer call(){throw new McIconException("OTHER_FAILED","original message",4);}
    }
    @Test void nonIoFailureAndSuccessfulCommandOutputRemainUnchanged() {
        StringWriter out=new StringWriter(),err=new StringWriter();
        var command=Main.commandLine().addSubcommand(new OtherFailure()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(4,command.execute("other-failure"));
        assertEquals("",out.toString());
        assertEquals("OTHER_FAILED: original message"+System.lineSeparator(),err.toString());
        out=new StringWriter();err=new StringWriter();
        @CommandLine.Command(name="success") class Success implements Callable<Integer>{public Integer call(){return 0;}}
        command=Main.commandLine().addSubcommand(new Success()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(0,command.execute("success"));
        assertEquals("",out.toString());assertEquals("",err.toString());
    }
    @Test void debugPreservesExitCodeAndKeepsCauseOffStdout() {
        Throwable cause=new IOException("synthetic preparation failure");
        for(String[] args:new String[][]{{"io-failure"},{"--debug","io-failure"},{"io-failure","--debug"}}) {
            StringWriter out=new StringWriter(),err=new StringWriter();
            CommandLine line=Main.commandLine().addSubcommand(new IoFailure(cause))
                .setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
            assertEquals(1,line.execute(args));
            assertEquals("",out.toString());
            if(args.length==1) {
                assertTrue(err.toString().startsWith("IO_FAILED: Input/output operation failed"));
                assertTrue(err.toString().contains("IO_FAILED_DETAIL category=IO_ERROR causeClass=java.io.IOException"));
            }
            else {
                assertTrue(err.toString().startsWith("IO_FAILED: secret outer message"+System.lineSeparator()));
                assertFalse(err.toString().contains("IO_FAILED_DETAIL"));
                assertTrue(err.toString().contains("Caused by: java.io.IOException: synthetic preparation failure"));
            }
        }
    }
    @Test void ioFailureClassifiesCauseChainWithoutLeakingItsContents() {
        String secret="SYNTHETIC_SECRET_206\nINJECTED_LINE_206";
        var shared=new java.nio.file.FileSystemException("C:\\private\\"+secret,"/home/private/"+secret,"sharing violation "+secret);
        shared.addSuppressed(new IOException("native stderr "+secret+" https://private.invalid/"+secret));
        String outer="outer message "+secret;
        IOException cause=new IOException("cause message "+secret,shared);
        String output=runIoWithMessage(cause,outer,"io-failure","--json");
        assertEquals("IO_FAILED: Input/output operation failed"+System.lineSeparator()+
            "IO_FAILED_DETAIL category=FILE_SYSTEM_ERROR causeClass=java.nio.file.FileSystemException"+System.lineSeparator(),output);
        assertFalse(output.contains(secret));
        assertFalse(output.contains("C:\\private"));
        assertFalse(output.contains("/home/private"));
        assertFalse(output.contains("private.invalid"));
        assertFalse(output.contains("sharing violation"));
        for(String[] args:new String[][]{{"--debug","io-failure"},{"io-failure","--debug"}}) {
            String debug=runIoWithMessage(new IOException("cause message "+secret,shared),outer,args);
            assertTrue(debug.startsWith("IO_FAILED: "+outer+System.lineSeparator()));
            assertTrue(debug.contains("Caused by: java.io.IOException: cause message "+secret));
            assertTrue(debug.contains("Caused by: java.nio.file.FileSystemException: C:\\private\\"+secret));
            assertTrue(debug.contains("Suppressed: java.io.IOException: native stderr "+secret));
            assertFalse(debug.contains("IO_FAILED_DETAIL"));
        }
    }
    @Test void ioFailureUsesCategoryPriorityAcrossTheCauseChain() {
        assertTrue(runIo(new IOException("outer",new InterruptedException("interrupted"))).contains("category=INTERRUPTED causeClass=java.lang.InterruptedException"));
        assertTrue(runIo(new IOException("outer",new java.net.SocketTimeoutException("timed out"))).contains("category=TIMEOUT causeClass=java.net.SocketTimeoutException"));
        assertTrue(runIo(new java.nio.file.AccessDeniedException("file")).contains("category=ACCESS_DENIED causeClass=java.nio.file.AccessDeniedException"));
        assertTrue(runIo(new java.nio.file.NoSuchFileException("file")).contains("category=NOT_FOUND causeClass=java.nio.file.NoSuchFileException"));
        assertTrue(runIo(new EOFException("eof")).contains("category=IO_ERROR causeClass=java.io.EOFException"));
        assertTrue(runIo(new java.net.ConnectException("connection refused")).contains("category=IO_ERROR causeClass=java.net.ConnectException"));
        assertTrue(runIo(new IllegalStateException("unknown")).contains("category=UNKNOWN causeClass=OTHER"));
        assertTrue(runIo(null).contains("category=UNKNOWN causeClass=NONE"));
        IOException unknown=new IOException("unknown subtype"){};
        assertTrue(runIo(unknown).contains("category=IO_ERROR causeClass=OTHER"));
    }
    @Test void ioFailureStopsOnCyclicCause() {
        Throwable cyclic=new Throwable(){@Override public synchronized Throwable getCause(){return this;}};
        assertTrue(runIo(cyclic).contains("category=UNKNOWN causeClass=OTHER"));
    }
    private static String runIo(Throwable cause,String... args) {
        return runIoWithMessage(cause,"secret outer message",args);
    }
    private static String runIoWithMessage(Throwable cause,String message,String... args) {
        StringWriter out=new StringWriter(),err=new StringWriter();
        var command=Main.commandLine().addSubcommand(new IoFailure(cause,message)).setOut(new PrintWriter(out)).setErr(new PrintWriter(err));
        assertEquals(1,command.execute(args.length==0?new String[]{"io-failure"}:args));
        assertEquals("",out.toString());
        return err.toString();
    }
    @Test void debugPrintsItemDiagnosticsToStderrOnlyWhenEnabled() {
        for(boolean debug:new boolean[]{false,true}) {
            StringWriter err=new StringWriter();
            CommandLine line=Main.commandLine().setErr(new PrintWriter(err));
            var args=new ArrayList<>(List.of("render","--version","1.21.4","--id","stone","--out","out"));
            if(debug)args.add(0,"--debug");
            line.parseArgs(args.toArray(String[]::new));
            var render=(Main.Render)line.getSubcommands().get("render").getCommand();
            render.common.listener(null).onDiagnostic("minecraft:stone",new IllegalStateException("original failure"));
            assertEquals(debug,err.toString().contains("DIAGNOSTIC minecraft:stone")&&err.toString().contains("java.lang.IllegalStateException: original failure"));
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
    @Test void memoryArgumentsReachWorkerOptionsAndInvalidPairsReturnUsageErrors() throws Exception {
        var common=List.of("render","--version","1.21.4","--id","stone","--out","out","--worker-jar","agent.jar");
        for(String[] flags:new String[][]{{},{"--memory-mib","4096","--heap-mib","2560"}}) {
            CommandLine line=Main.commandLine();
            var args=new ArrayList<>(common);args.addAll(Arrays.asList(flags));
            line.parseArgs(args.toArray(String[]::new));Main.Render render=line.getSubcommands().get("render").getCommand();var options=render.worker.options();
            assertEquals(flags.length==0?3072:4096,options.containerMemoryMiB());assertEquals(flags.length==0?2048:2560,options.heapMemoryMiB());
        }
        for(String[] flags:new String[][]{{"--memory-mib","0"},{"--heap-mib","0"},{"--memory-mib","2048"},{"--heap-mib","3072"},{"--memory-mib","not-an-int"}}) {
            var args=new ArrayList<>(common);args.addAll(Arrays.asList(flags));
            assertEquals(2,Main.execute(args.toArray(String[]::new)));
        }
    }

}
