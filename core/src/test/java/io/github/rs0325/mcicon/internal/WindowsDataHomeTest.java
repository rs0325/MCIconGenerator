package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.ManagedOptions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WindowsDataHomeTest {
    private boolean windows(){return System.getProperty("os.name").startsWith("Windows");}
    private List<String> resolve(String value) throws Exception {
        String classpath=Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator+Path.of(WindowsDataHome.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dstdout.encoding=UTF-8","-cp",classpath,Probe.class.getName());
        builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME"));if(value!=null)builder.environment().put("MCICON_DATA_HOME",value);
        Process child=builder.redirectErrorStream(true).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());return child.inputReader(StandardCharsets.UTF_8).lines().toList();}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
    }
    @Test void absentKeepsExistingRuntimeDefault() throws Exception {
        List<String> value=resolve(null);assertEquals("null",value.get(0));
        Path home=Path.of(System.getProperty("user.home"));String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);Path expected;
        if(os.contains("mac"))expected=home.resolve("Library/Application Support/MCIconGenerator");
        else if(os.contains("win")){String local=System.getenv("LOCALAPPDATA");expected=local==null?home.resolve("AppData/Local/MCIconGenerator"):Path.of(local).resolve("MCIconGenerator");}
        else {String data=System.getenv("XDG_DATA_HOME");expected=data==null?home.resolve(".local/share/mcicon"):Path.of(data).resolve("mcicon");}
        assertEquals(expected.toString(),value.get(1));
    }
    @Test void otherSystemsIgnoreEvenInvalidValues() throws Exception {
        assumeFalse(windows());List<String> absent=resolve(null);
        for(String value:List.of(""," ","relative","H:\\data home"))assertEquals(absent,resolve(value));
    }
    @Test void windowsNormalizesAbsoluteRootAndKeepsExplicitOptions() throws Exception {
        assumeTrue(windows());Path root=Path.of("H:\\data home\\before\\..\\日本語").normalize();
        List<String> value=resolve("H:\\data home\\before\\..\\日本語");assertEquals(root.toString(),value.get(0));assertEquals(root.resolve("runtime").toString(),value.get(1));assertEquals("explicit",value.get(2));
        assertEquals(Path.of("H:/").toString(),resolve("H:/").getFirst());
    }
    @Test void windowsRejectsInvalidValuesWithoutIo() throws Exception {
        assumeTrue(windows());
        for(String value:List.of(""," ","relative","H:relative","\\root","\\\\server\\share","\\\\?\\H:\\data","\\\\.\\H:\\data","\"H:\\data\"","H:\\bad<name","%LOCALAPPDATA%\\data"))assertEquals(List.of("INVALID"),resolve(value),value);
    }
    @Test void explicitConstructorDoesNotResolveInvalidEnvironment() throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator+Path.of(WindowsDataHome.class.getProtectionDomain().getCodeSource().getLocation().toURI()),Probe.class.getName(),"explicit");
        builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME"));builder.environment().put("MCICON_DATA_HOME","relative");Process child=builder.redirectErrorStream(true).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertEquals("explicit",child.inputReader().readLine());}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
    }
    public static final class Probe {
        public static void main(String[] args) {
            if(args.length>0){System.out.println(new ManagedOptions(Path.of("explicit"),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),2,1).runtimeRoot());return;}
            try {
                Path root=WindowsDataHome.configuredRoot();System.out.println(root);System.out.println(ManagedOptions.defaults().runtimeRoot());
                System.out.println(new ManagedOptions(Path.of("explicit"),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),2,1).runtimeRoot());
            }catch(IllegalArgumentException failure){System.out.println("INVALID");}
        }
    }
}
