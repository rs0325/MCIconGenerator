package io.github.rs0325.mcicon.bootstrap;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WindowsDataHomeRootTest {
    private String root(String data) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dstdout.encoding=UTF-8","-cp",System.getProperty("bootstrap.testClasspath"),Probe.class.getName());
        builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME"));if(data!=null)builder.environment().put("MCICON_DATA_HOME",data);
        Process child=builder.redirectErrorStream(true).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());return child.inputReader(StandardCharsets.UTF_8).readLine();}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
    }
    @Test void absentAndNonWindowsKeepExistingRoot() throws Exception {
        String existing=root(null);String os=Main.host().os();Path home=Path.of(System.getProperty("user.home"));Path expected;
        if(os.equals("macos"))expected=home.resolve("Library/Application Support/MCIconBootstrap");
        else if(os.equals("windows")){String local=System.getenv("LOCALAPPDATA");expected=(local==null?home.resolve("AppData/Local"):Path.of(local)).resolve("MCIconBootstrap");}
        else {String data=System.getenv("XDG_DATA_HOME");expected=(data==null?home.resolve(".local/share"):Path.of(data)).resolve("mcicon-bootstrap");}
        assertEquals(expected.toString(),existing);
        if(!os.equals("windows"))for(String value:List.of("","relative","H:\\data home"))assertEquals(existing,root(value));
    }
    @Test void windowsUsesConfiguredBootstrapRootAndRejectsInvalidValues() throws Exception {
        assumeTrue(Main.host().os().equals("windows"));
        assertEquals(Path.of("H:\\data home\\before\\..\\日本語").normalize().resolve("bootstrap").toString(),root("H:\\data home\\before\\..\\日本語"));
        for(String value:List.of("","relative","H:relative","\\\\server\\share","\\\\?\\H:\\data"))assertEquals("INVALID",root(value));
    }
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            try {System.out.println(Main.root(Main.host()));}catch(IllegalArgumentException failure){System.out.println("INVALID");}
        }
    }
}
