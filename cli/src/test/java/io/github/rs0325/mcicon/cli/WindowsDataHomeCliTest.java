package io.github.rs0325.mcicon.cli;

import io.github.rs0325.mcicon.internal.Json;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WindowsDataHomeCliTest {
    @TempDir Path directory;
    private boolean windows(){return System.getProperty("os.name").startsWith("Windows");}
    private String classpath() throws Exception {
        List<String> entries=new ArrayList<>();
        for(Class<?> type:List.of(Main.class,io.github.rs0325.mcicon.MCIconGenerator.class,com.fasterxml.jackson.databind.JsonNode.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class,picocli.CommandLine.class))entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(java.io.File.pathSeparator,entries);
    }
    private record Result(int exit,String out,String error) {}
    private Result run(String data,String... args) throws Exception {
        List<String> argv=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",classpath(),Main.class.getName()));argv.addAll(List.of(args));
        ProcessBuilder builder=new ProcessBuilder(argv).directory(directory.toFile());builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME"));if(data!=null)builder.environment().put("MCICON_DATA_HOME",data);
        Path output=directory.resolve("stdout"),error=directory.resolve("stderr");Process child=builder.redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));return new Result(child.exitValue(),Files.readString(output),Files.readString(error));}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
    }
    private void marker(Path cache,String content) throws Exception {Files.createDirectories(cache);Files.writeString(cache.resolve("sentinel"),content);}
    private void bytes(long expected,Result result) throws Exception {assertEquals(0,result.exit(),result.error());assertEquals(expected,Json.MAPPER.readTree(result.out()).path("bytes").asLong());}
    @Test void absentAndOtherSystemValuesKeepCwdCache() throws Exception {
        marker(directory.resolve(".mcicon-cache"),"old");bytes(3,run(null,"cache","status","--offline","--json"));
        if(!windows())for(String value:List.of("","relative","H:\\data home"))bytes(3,run(value,"cache","status","--offline","--json"));
    }
    @Test void windowsDefaultAndExplicitRelativeCacheUseDifferentInputs() throws Exception {
        assumeTrue(windows());Path data=directory.resolve("data home");marker(data.resolve("cache"),"new-data");marker(directory.resolve("relative cache"),"explicit value");marker(directory.resolve(".mcicon-cache"),"old");
        bytes(8,run(data.resolve("before/..").toString(),"cache","status","--offline","--json"));
        bytes(14,run(data.toString(),"cache","status","--cache","relative cache","--offline","--json"));
        assertEquals("old",Files.readString(directory.resolve(".mcicon-cache/sentinel")));
    }
    @Test void windowsInvalidRootFailsBeforeCacheIoEvenWithExplicitCache() throws Exception {
        assumeTrue(windows());Path cache=directory.resolve("must not exist");
        for(String value:List.of(""," ","relative","H:relative","\\\\server\\share","\\\\?\\H:\\data","H:\\bad<name")) {
            Result result=run(value,"cache","status","--cache",cache.toString(),"--offline","--json");assertEquals(2,result.exit());assertEquals("",result.out());assertTrue(result.error().startsWith("INVALID_REQUEST:"),result.error());assertFalse(Files.exists(cache));assertFalse(Files.exists(directory.resolve(".mcicon-cache")));
        }
    }
    @Test void explicitCacheAndOutputRemainRelativeArguments() {
        var line=Main.commandLine();line.parseArgs("render","--version","1.20.6","--id","stone","--cache","relative cache","--out","relative output");Main.Render render=line.getSubcommands().get("render").getCommand();
        assertEquals(Path.of("relative cache"),render.common.cache);assertEquals(Path.of("relative output"),render.output);
    }
}
