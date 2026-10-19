package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.JarFile;

/** 正式package入口で、生成resourceと署名seed内のcoreを同じ元bytesへ結合する。 */
final class ManagedPackageVerifier {
    private static final String PREFIX="META-INF/mcicon/runtime/";
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("resources seed publicTrust toolVersion currentCoreJarが必要です");
        verify(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),args[3],Path.of(args[4]));
    }
    static void verify(Path resources,Path seedDirectory,Path trust,String version,Path currentCore) throws Exception {
        Map<String,byte[]> expected=new TreeMap<>();Path prefix=resources.resolve(PREFIX);
        if(!Files.isDirectory(prefix,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(resources))throw Seed.invalid("明示生成runtime resourceが必要です");
        try(var paths=Files.walk(prefix)){for(Path file:paths.toList()){if(Files.isSymbolicLink(file))throw Seed.invalid("resource linkは拒否します");if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))continue;expected.put(resources.relativize(file).toString().replace('\\','/'),Seed.read(file,4*1024*1024));}}
        JsonNode pins=Seed.json(expected.get(PREFIX+"pins.json"));Seed.fields(pins,"schemaVersion","runtimes");Seed.constant(pins,"schemaVersion",1);JsonNode selected=null;Set<JsonNode> hosts=new HashSet<>();var host=Seed.JSON.valueToTree(Main.host());
        for(JsonNode entry:Seed.array(pins,"runtimes",1,6)){Seed.fields(entry,"host","descriptorPath","descriptorSha256","trustKeyPath");if(!hosts.add(entry.get("host")))throw Seed.invalid("resource hostが重複しています");if(host.equals(entry.get("host")))selected=entry;}
        if(selected==null)throw Seed.invalid("package host用resourceがありません");
        byte[] der=Seed.read(trust,1024),runtimeTrust=required(expected,Seed.text(selected,"trustKeyPath"));if(!Arrays.equals(der,runtimeTrust))throw Seed.invalid("runtimeとpackageの公開trustが異なります");
        var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));Seed seed=new Seed(seedDirectory,key,Main.host());if(!seed.version.equals(version))throw Seed.invalid("package版とseed版が不一致です");
        byte[] descriptor=required(expected,Seed.text(selected,"descriptorPath")),signature=required(expected,Seed.text(selected,"descriptorPath")+".sig");if(!Seed.hash(descriptor).equals(Seed.digest(selected,"descriptorSha256")))throw Seed.invalid("resource descriptor pinが不一致です");Seed.signed(descriptor,signature,key);
        Path temporary=Files.createTempDirectory("mcicon-managed-package-");Exception original=null;
        try {
            Path slot=temporary.resolve("slot");seed.extract(seedDirectory.resolve("tool.zip"),slot,System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos());
            Path definition=Seed.resolve(slot,Seed.text(seed.platform.get("runtimeDescriptor"),"path"));if(!Arrays.equals(descriptor,Seed.read(definition,1024*1024))||!Arrays.equals(signature,Seed.read(Path.of(definition+".sig"),64)))throw Seed.invalid("seedとcoreのdescriptor署名bytesが異なります");
            String agent=Seed.text(Seed.json(descriptor).get("worker"),"agentSha256");if(!agent.equals(Seed.hash(slot.resolve("app/worker/worker-agent.jar"))))throw Seed.invalid("payload agentとruntime指定が異なります");
            Path core=slot.resolve("app/lib/core-"+version+".jar");Seed.regular(currentCore);if(Files.size(core)!=Files.size(currentCore)||!Seed.hash(core).equals(Seed.hash(currentCore)))throw Seed.invalid("payload coreと現在buildしたcore JAR全体が異なります");Set<String> seen=new HashSet<>();
            try(var jars=Files.list(slot.resolve("app/lib"))){for(Path file:jars.filter(p->p.getFileName().toString().endsWith(".jar")).toList())try(JarFile jar=new JarFile(file.toFile())){
                var entries=jar.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.isDirectory()||!entry.getName().startsWith(PREFIX))continue;if(!file.equals(core)||!seen.add(entry.getName())||!expected.containsKey(entry.getName()))throw Seed.invalid("payload runtime resourceが重複/不明です");
                    try(InputStream stream=jar.getInputStream(entry)){byte[] body=stream.readNBytes(4*1024*1024+1);if(!Arrays.equals(body,expected.get(entry.getName())))throw Seed.invalid("payload core runtime resourceのbytesが異なります");}
                }
            }}
            if(!seen.equals(expected.keySet()))throw Seed.invalid("payload coreのruntime resourceが欠落しています");
        }catch(Exception failure){original=failure;throw failure;}
        finally {try{try(var paths=Files.walk(temporary)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}catch(IOException failure){if(original!=null)original.addSuppressed(failure);else throw failure;}}
    }
    private static byte[] required(Map<String,byte[]> resources,String path) throws IOException {Seed.relative(path);byte[] bytes=resources.get(PREFIX+path);if(bytes==null)throw Seed.invalid("生成resourceが欠落しています: "+path);return bytes;}
}
