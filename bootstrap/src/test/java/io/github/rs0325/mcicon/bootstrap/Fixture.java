package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.node.*;
import org.apache.commons.compress.archivers.zip.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.io.*;
import java.util.jar.*;
import org.junit.jupiter.api.Assumptions;

/** fixtureごとの一時署名鍵をJVM内だけに保持し、公開鍵だけをQA入力へ保存する。 */
final class Fixture {
    final Path installed;
    final Seed.Host host;
    final Map<String,byte[]> files=new LinkedHashMap<>();
    final ObjectNode manifest=Seed.JSON.createObjectNode(),descriptor=Seed.JSON.createObjectNode();
    final PublicKey publicKey;
    private final PrivateKey signingKey;
    Fixture(Path directory) throws Exception {
        String reader=System.getenv("MCICON_WINDOWS_READER_STAGE");
        Assumptions.assumeTrue(!System.getProperty("os.name").startsWith("Windows")||reader!=null,"Windows実機ではstage済みreaderの環境変数が必要");
        installed=directory.resolve("package");Files.createDirectories(installed.resolve("seed"));
        if(reader!=null&&System.getProperty("os.name").startsWith("Windows")){Path source=Path.of(reader,"support");try(var tree=Files.walk(source)){for(Path from:(Iterable<Path>)tree::iterator){Path to=installed.resolve("support").resolve(source.relativize(from).toString());if(Files.isDirectory(from))Files.createDirectories(to);else Files.copy(from,to);}}}host=Main.host();KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();publicKey=pair.getPublic();signingKey=pair.getPrivate();
        ObjectNode info=Seed.JSON.createObjectNode().put("schemaVersion",1).put("bootstrapVersion","1.1.0").put("bootstrapProtocol",2).put("owner","archive").putNull("stableManifestUrl");info.set("host",Seed.JSON.valueToTree(host));Files.write(installed.resolve("bootstrap-package.json"),Seed.JSON.writeValueAsBytes(info));Files.write(installed.resolve("trust.der"),publicKey.getEncoded());
        descriptor.put("schemaVersion",1).put("toolVersion","1.1.0");descriptor.set("host",Seed.JSON.valueToTree(host));descriptor.putObject("helper").put("protocolVersion",1);
        files.put("inventory/tool.json","{\"fixtureOnly\":true}".getBytes());files.put("LICENSE","fixture license".getBytes());
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString().replace("'","'\"'\"'");if(host.os().equals("windows")){Path runtime=windowsRuntime();try(var tree=Files.walk(runtime)){for(Path file:(Iterable<Path>)tree.filter(Files::isRegularFile)::iterator)files.put("java/"+runtime.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));}}else files.put("java/bin/java",("#!/bin/sh\nexec '"+java+"' \"$@\"\n").getBytes());
        ByteArrayOutputStream data=new ByteArrayOutputStream();try(JarOutputStream jar=new JarOutputStream(data)){for(String path:List.of("io/github/rs0325/mcicon/cli/Main.class","io/github/rs0325/mcicon/cli/StartupControl.class")){jar.putNextEntry(new JarEntry(path));try(InputStream in=getClass().getResourceAsStream("/"+path)){jar.write(Objects.requireNonNull(in).readAllBytes());}jar.closeEntry();}}
        for(Class<?> type:List.of(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class)){Path jar=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());files.put("app/lib/"+jar.getFileName(),Files.readAllBytes(jar));}
        files.put("app/lib/cli-1.1.0.jar",data.toByteArray());files.put("app/lib/core-1.1.0.jar",new byte[]{1});files.put("app/lib/worker-1.1.0.jar",new byte[]{1});files.put("app/worker/worker-agent.jar",new byte[]{1});
        manifest.put("schemaVersion",1).put("channel","stable").put("sequence",1).put("toolVersion","1.1.0").put("minimumBootstrapProtocol",2);ObjectNode platform=manifest.putArray("platforms").addObject();platform.set("host",Seed.JSON.valueToTree(host));
        ObjectNode payload=platform.putObject("payload").put("id","tool").put("role","tool-payload").put("url","https://fixture.invalid/tool.zip").put("format","zip").put("inventoryPath","inventory/tool.json").put("inventorySha256",Seed.hash(files.get("inventory/tool.json")));payload.putArray("licenseFiles").add("LICENSE");payload.putArray("noticeFiles");payload.putArray("sourceArchives");platform.putObject("runtimeDescriptor").put("path","runtime/descriptor.json");publish();
    }
    /** slotで起動できる最小runtimeをJVMごとに一度だけjlinkで作る。 */
    private static synchronized Path windowsRuntime() throws Exception {
        Path runtime=Path.of(System.getProperty("java.io.tmpdir"),"mcicon-fixture-runtime-"+System.getProperty("java.runtime.version").replaceAll("[^0-9A-Za-z.]","_")),built=runtime.resolveSibling(runtime.getFileName()+"."+ProcessHandle.current().pid());
        if(!Files.exists(runtime)){Process jlink=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","jlink.exe").toString(),"--add-modules","java.base,java.logging,java.management,java.net.http,jdk.security.auth,jdk.crypto.ec","--strip-debug","--no-man-pages","--no-header-files","--compress","zip-9","--output",built.toString()).redirectErrorStream(true).start();jlink.getInputStream().readAllBytes();if(jlink.waitFor()!=0)throw new IOException("jlinkに失敗しました");
            /* slot展開ではnodeごとにreaderを起動するため、起動に不要なfileを減らして5秒期限に収める。 */
            try(var tree=Files.walk(built)){for(Path file:(Iterable<Path>)tree.filter(Files::isRegularFile)::iterator){String name=built.relativize(file).toString();if(name.startsWith("legal")||name.startsWith("bin\\api-ms-")||name.matches("bin\\\\(javaw|keytool|kinit|klist|ktab)\\.exe"))Files.delete(file);}}
            try {Files.move(built,runtime,StandardCopyOption.ATOMIC_MOVE);}catch(FileSystemException lost) {if(!Files.exists(runtime))throw lost;}}
        return runtime;
    }
    byte[] sign(byte[] body) throws Exception {
        Signature signature=Signature.getInstance("Ed25519");signature.initSign(signingKey);signature.update(body);return signature.sign();
    }
    void publish() throws Exception {
        byte[] raw=Seed.JSON.writeValueAsBytes(descriptor);files.put("runtime/descriptor.json",raw);files.put("runtime/descriptor.json.sig",sign(raw));
        byte[] zip=zip(null);Files.write(installed.resolve("seed/tool.zip"),zip);ObjectNode platform=(ObjectNode)manifest.path("platforms").get(0),payload=(ObjectNode)platform.get("payload");payload.put("bytes",zip.length).put("sha256",Seed.hash(zip)).put("unpackedBytes",files.values().stream().mapToLong(b->b.length).sum());((ObjectNode)platform.get("runtimeDescriptor")).put("sha256",Seed.hash(raw));writeManifest();
    }
    void writeManifest() throws Exception {byte[] raw=Seed.JSON.writeValueAsBytes(manifest);Files.write(installed.resolve("seed/stable.json"),raw);Files.write(installed.resolve("seed/stable.json.sig"),sign(raw));}
    byte[] zip(String extra) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipArchiveOutputStream zip=new ZipArchiveOutputStream(bytes)){
            for(var row:files.entrySet()){ZipArchiveEntry entry=new ZipArchiveEntry(row.getKey());entry.setUnixMode(row.getKey().equals("java/bin/java")?0100700:0100600);zip.putArchiveEntry(entry);zip.write(row.getValue());zip.closeArchiveEntry();}
            if(extra!=null){ZipArchiveEntry entry=new ZipArchiveEntry(extra);entry.setUnixMode(extra.equals("link")?0120777:0100600);zip.putArchiveEntry(entry);zip.write(new byte[]{1});zip.closeArchiveEntry();}
        }return bytes.toByteArray();
    }
    Seed seed() throws Exception {return new Seed(installed.resolve("seed"),publicKey,host);}
    Bootstrap bootstrap(Path root) throws IOException {return new Bootstrap(installed,root,host);}
    void replaceZip(byte[] bytes) throws Exception {Files.write(installed.resolve("seed/tool.zip"),bytes);ObjectNode payload=(ObjectNode)manifest.path("platforms").get(0).get("payload");payload.put("bytes",bytes.length).put("sha256",Seed.hash(bytes)).put("unpackedBytes",10000000);writeManifest();}
}
