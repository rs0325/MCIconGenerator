package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class WindowsDataHomeChildTest {
    @TempDir Path directory;
    private void probe(String data) throws Exception {probe(data,false);}
    private void probe(String data,boolean mixedCase) throws Exception {
        String cp=System.getProperty("bootstrap.testClasspath");
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-Dbootstrap.testClasspath="+cp,"-cp",cp,Probe.class.getName(),directory.toRealPath().toString(),Boolean.toString(mixedCase));
        builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME"));
        if(data!=null)builder.environment().put(mixedCase?"mcicon_data_home":"MCICON_DATA_HOME",data);
        if(mixedCase) {
            builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("TMP")||name.equalsIgnoreCase("TEMP"));
            builder.environment().put("Temp",directory.resolve("old-temp").toString());builder.environment().put("tMp",directory.resolve("old-tmp").toString());
        }
        Path out=directory.resolve("probe.stdout"),err=directory.resolve("probe.stderr");Process child=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {assertTrue(child.waitFor(120,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),()->{try{return Files.readString(err);}catch(IOException failure){throw new UncheckedIOException(failure);}});assertEquals("OK",Files.readString(out).strip());}
        finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
    }
    @Test void absentOrOtherOsPreservesChildArgumentsAndEnvironment() throws Exception {probe(null);}
    @Test void windowsChildUsesNormalizedEnvironmentAndSingleTempArgument() throws Exception {
        assumeTrue(Main.host().os().equals("windows"));probe(directory.toRealPath().resolve("data home/unused/..").toString());
    }
    @Test void windowsChildRemovesAllCaseVariantsOfEnvironment() throws Exception {
        assumeTrue(Main.host().os().equals("windows"));probe(directory.toRealPath().resolve("日本語 data/unused/..").toString(),true);
    }
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path base=Path.of(args[0]);Fixture fixture=new Fixture(base);String cp=System.getProperty("bootstrap.testClasspath");
            Path data=io.github.rs0325.mcicon.internal.WindowsDataHome.configuredRoot();
            Bootstrap bootstrap=fixture.bootstrap(data==null?base.resolve("state"):Main.root(fixture.host));
            List<String> argv=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-XX:-UsePerfData","-Dstdout.encoding=UTF-8","-cp",cp,Environment.class.getName(),"space value","literal*","日本語");
            ProcessBuilder builder=new ProcessBuilder(new ArrayList<>(argv));
            if(Boolean.parseBoolean(args[1])) {
                for(String name:List.of("MCICON_DATA_HOME","TMP","TEMP")) {
                    if(builder.environment().keySet().stream().noneMatch(key->key.equalsIgnoreCase(name)&&!key.equals(name)))throw new AssertionError("別大小文字の親環境がありません");
                    builder.environment().put(name,"stale");
                }
            }
            Map<String,String> env=Map.copyOf(builder.environment());
            bootstrap.configureChild(builder,System.nanoTime()+Duration.ofSeconds(30).toNanos());
            if(data==null) {
                if(!argv.equals(builder.command())||!env.equals(builder.environment())||Files.exists(bootstrap.root.resolve("tmp")))throw new AssertionError("未指定時の設定が変わりました");
            }else {
                Path temp=data.resolve("bootstrap/tmp");String property="-Djava.io.tmpdir="+temp;
                List<String> expected=new ArrayList<>(argv);expected.add(1,property);
                if(!expected.equals(builder.command()))throw new AssertionError("tmpは単一argvが必要です");
                Map<String,String> other=new HashMap<>(builder.environment()),before=new HashMap<>(env);
                for(Map<String,String> values:List.of(other,before))values.keySet().removeIf(name->name.equalsIgnoreCase("MCICON_DATA_HOME")||name.equalsIgnoreCase("TMP")||name.equalsIgnoreCase("TEMP"));
                if(!other.equals(before))throw new AssertionError("対象外の環境が変わりました");
                Path out=base.resolve("environment.json"),err=base.resolve("environment.stderr");
                Process child=builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
                try {
                    if(!child.waitFor(10,TimeUnit.SECONDS)||child.exitValue()!=0)throw new AssertionError("子JVM起動失敗: "+Files.readString(err));
                    JsonNode observed=Seed.json(Files.readAllBytes(out));
                    for(String name:List.of("MCICON_DATA_HOME","TMP","TEMP"))if(observed.path("counts").path(name).asInt()!=1)throw new AssertionError(name+"の重複があります");
                    if(!data.toString().equals(observed.path("data").asText()))throw new AssertionError("正規化envが不一致です");
                    for(String name:List.of("temp","tmp","tmpdir","fileParent"))if(!temp.toString().equals(observed.path(name).asText()))throw new AssertionError(name+"が不一致です");
                    if(!List.of("space value","literal*","日本語").equals(Seed.JSON.convertValue(observed.get("args"),List.class)))throw new AssertionError("argvが変わりました");
                }finally {if(child.isAlive()){child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("子JVM回収失敗");}}
                // 新規時だけでなく既存tmpを再検査し、期限切れ・通常fileへの差替えではspawn設定を返さない。
                bootstrap.configureChild(new ProcessBuilder("java"),Long.MAX_VALUE);
                try {bootstrap.configureChild(new ProcessBuilder("java"),System.nanoTime()-1);throw new AssertionError("期限切れを受理しました");}catch(IOException expectedFailure){}
                Files.delete(temp);Files.writeString(temp,"keep");
                try {bootstrap.configureChild(new ProcessBuilder("java"),Long.MAX_VALUE);throw new AssertionError("fileのtmpを受理しました");}catch(IOException expectedFailure){}
                if(!Files.readString(temp).equals("keep"))throw new AssertionError("既存fileを変更しました");
            }
            System.out.println("OK");
        }
    }
    public static final class Environment {
        public static void main(String[] args) throws Exception {
            Path temp=Files.createTempFile("mcicon-data-home-", ".tmp");
            try {var observed=Seed.JSON.createObjectNode().put("data",System.getenv("MCICON_DATA_HOME")).put("temp",System.getenv("TEMP")).put("tmp",System.getenv("TMP")).put("tmpdir",System.getProperty("java.io.tmpdir")).put("fileParent",temp.getParent().toString());var counts=observed.putObject("counts");for(String name:List.of("MCICON_DATA_HOME","TMP","TEMP"))counts.put(name,System.getenv().keySet().stream().filter(key->key.equalsIgnoreCase(name)).count());observed.set("args",Seed.JSON.valueToTree(args));System.out.println(observed);}
            finally {Files.delete(temp);}
        }
    }
}
