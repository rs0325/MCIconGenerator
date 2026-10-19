package io.github.rs0325.mcicon.cli;

import io.github.rs0325.mcicon.ManagedOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedArgumentsTest {
    @TempDir Path directory;
    private List<String> arguments(String command,String... flags) {
        List<String> args=new ArrayList<>(List.of(command,"--version","1.20.6"));
        if(command.equals("render"))args.addAll(List.of("--id","stone","--out",directory.resolve("out").toString()));
        args.addAll(Arrays.asList(flags));return args;
    }
    private Main.WorkerArguments worker(CommandLine line,String command,String... flags) {
        line.parseArgs(arguments(command,flags).toArray(String[]::new));
        Object value=line.getSubcommands().get(command).getCommand();
        return switch(value) {case Main.Doctor doctor->doctor.worker;case Main.Catalog catalog->catalog.worker;case Main.Render render->render.worker;default->throw new AssertionError();};
    }
    @Test void defaultAndExplicitManagedDoNotResolveWorkerJar() {
        for(String command:List.of("doctor","list","render"))for(String[] flags:new String[][]{{},{"--runtime","managed"}}) {
            var worker=worker(Main.commandLine(),command,flags);
            assertTrue(worker.managed());assertNull(worker.agent);
            var options=worker.managedOptions();var defaults=ManagedOptions.defaults();
            assertEquals(defaults.runtimeRoot(),options.runtimeRoot());assertEquals(defaults.preparationTimeout(),options.preparationTimeout());
        }
    }
    @Test void eachExplicitLegacyOptionSelectsDockerEvenAtDefaultValue() throws Exception {
        for(String command:List.of("doctor","list","render"))for(String[] flags:new String[][]{
                {"--docker-context","colima-mcicon"},{"--docker-image","mcicon-render:dev"},{"--worker-jar","agent.jar"},{"--java-home","/opt/java21"},{"--runtime","docker"}}) {
            var worker=worker(Main.commandLine(),command,flags);assertFalse(worker.managed());
            var options=worker.options();assertEquals("colima-mcicon",options.dockerContext());assertEquals("mcicon-render:dev",options.dockerImage());
            if(flags[0].equals("--java-home"))assertEquals("/opt/java21",options.javaHome());
            if(flags[0].equals("--worker-jar"))assertEquals(Path.of("agent.jar"),options.agentJar());
        }
    }
    @Test void managedAndEveryExplicitDockerOptionFailBeforeCacheCreation() {
        for(String command:List.of("doctor","list","render"))for(String[] flags:new String[][]{
                {"--docker-context","colima-mcicon"},{"--docker-image","mcicon-render:dev"},{"--worker-jar","agent.jar"},{"--java-home","/opt/java21"}}) {
            Path cache=directory.resolve("not-created");var args=arguments(command,"--runtime","managed","--cache",cache.toString());args.addAll(Arrays.asList(flags));
            StringWriter err=new StringWriter();var line=Main.commandLine().setErr(new PrintWriter(err));
            assertEquals(2,line.execute(args.toArray(String[]::new)));assertTrue(err.toString().startsWith("INVALID_REQUEST:"));assertFalse(Files.exists(cache));
        }
    }
    @Test void invalidModeIsUsageFailureWithoutIo() {
        for(String command:List.of("doctor","list","render")) {
            Path cache=directory.resolve("not-created");var args=arguments(command,"--runtime","automatic","--cache",cache.toString());
            assertEquals(2,Main.execute(args.toArray(String[]::new)));assertFalse(Files.exists(cache));
        }
    }
    @Test void commonBudgetsReachManagedOptionsWithoutSelectingDocker() {
        for(String command:List.of("doctor","list","render")) {
            var worker=worker(Main.commandLine(),command,"--memory-mib","4096","--heap-mib","2560","--startup-timeout","91","--item-timeout","17","--cache",directory.toString());
            assertTrue(worker.managed());var options=worker.managedOptions();
            assertEquals(4096,options.containerMemoryMiB());assertEquals(2560,options.heapMemoryMiB());
            assertEquals(Duration.ofSeconds(91),options.startupTimeout());assertEquals(Duration.ofSeconds(17),options.itemTimeout());
            assertNotEquals(directory,options.runtimeRoot());
        }
    }
    @Test void invalidManagedBudgetsAndTimeoutsAreRejected() {
        for(String[] flags:new String[][]{{"--memory-mib","0"},{"--heap-mib","0"},{"--heap-mib","3072"},{"--startup-timeout","0"},{"--item-timeout","-1"}}) {
            var worker=worker(Main.commandLine(),"doctor",flags);assertTrue(worker.managed());assertThrows(IllegalArgumentException.class,worker::managedOptions);
        }
    }
    @Test void realOfflineManagedCallsReportMissingPinsWithoutDockerFallback() {
        for(String command:List.of("doctor","list"))for(String[] flags:new String[][]{{},{"--runtime","managed"}}) {
            var args=arguments(command,"--offline","--cache",directory.resolve(command).toString(),"--json");args.addAll(Arrays.asList(flags));
            StringWriter err=new StringWriter(),out=new StringWriter();var line=Main.commandLine().setErr(new PrintWriter(err)).setOut(new PrintWriter(out));
            assertEquals(1,line.execute(args.toArray(String[]::new)));assertTrue(err.toString().startsWith("MANAGED_RUNTIME_INVALID:"),err.toString());
            assertTrue(err.toString().contains("pins.json"));assertFalse(err.toString().contains("colima-mcicon"));assertEquals("",out.toString());
        }
    }
    @Test void rawCommandsDoNotAcceptBackendOptions() {
        for(String command:List.of("extract","versions","cache")) {
            var line=Main.commandLine();assertFalse(line.getSubcommands().get(command).getCommandSpec().optionsMap().containsKey("--runtime"));
        }
        assertEquals(0,Main.execute("cache","status","--offline","--cache",directory.toString(),"--json"));
    }
    @Test void helpDescribesModeAndDockerJavaMeaning() {
        for(String command:List.of("doctor","list","render")) {
            var line=Main.commandLine();StringWriter out=new StringWriter();line.setOut(new PrintWriter(out));
            assertEquals(0,line.execute(command,"--help"));assertTrue(out.toString().contains("--runtime"));
            assertTrue(out.toString().contains("managed"));assertTrue(out.toString().contains("Docker image内"));
        }
    }
}
