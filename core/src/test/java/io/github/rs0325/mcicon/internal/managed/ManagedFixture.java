package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.*;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.security.*;
import java.util.*;

/** テスト専用鍵と非配布fixture。本番の信頼鍵・配布URLとして使用しない。 */
final class ManagedFixture {
    static final String HASH="a".repeat(64);
    final KeyPair key;
    final ObjectNode json;
    ManagedFixture() throws Exception {
        key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        json=RuntimeDescriptor.JSON.createObjectNode();json.put("schemaVersion",1);json.put("runtimeId","fixture");json.put("toolVersion","1.1.0");
        json.putObject("host").put("os","linux").put("arch","x86_64");json.put("minimumOsVersion","5.15");
        json.putObject("helper").put("protocolVersion",1).put("artifactId","helper").put("executable","bin/helper");
        json.putObject("worker").put("toolVersion","1.1.0").put("agentSha256",HASH).put("protocolVersion",1).put("adapterSetSha256",HASH);
        ObjectNode render=json.putObject("rendering");render.put("platform","linux/amd64");render.put("imageArtifactId","image");render.put("imageId","sha256:"+HASH);render.put("contextProfile","canonical-v1");
        render.putArray("javaExecutables").addObject().put("major",21).put("executable","/opt/java/java21/bin/java").put("sha256",HASH);
        render.put("mesaVersion","fixture");render.put("llvmVersion","fixture");render.putObject("environment");render.put("cpuRequirement","sse4.1");
        ArrayNode artifacts=json.putArray("artifacts");artifact(artifacts,"helper","host-helper");artifact(artifacts,"image","renderer-image");
    }
    private static void artifact(ArrayNode array,String id,String role) {
        ObjectNode item=array.addObject().put("id",id).put("role",role).put("url","https://fixture.invalid/"+id).put("bytes",1).put("sha256",HASH).put("unpackedBytes",100).put("format","zip").put("inventoryPath","inventory/"+id+".json").put("inventorySha256",HASH);
        item.putArray("licenseFiles").add("LICENSE");item.putArray("noticeFiles");item.putArray("sourceArchives");
    }
    static RuntimeLease lease(RuntimeStore store,RuntimeDescriptor descriptor) throws Exception {
        var timeout=java.time.Duration.ofSeconds(10);
        var options=new io.github.rs0325.mcicon.ManagedOptions(store.root(),timeout,timeout,timeout,3072,2048);
        return RuntimeLease.create(store,descriptor,options,java.time.Instant.now().plus(timeout));
    }
    record Owner(long pid,java.time.Instant started) {}
    static Owner deadOwner(java.nio.file.Path directory) throws Exception {
        var lock=java.nio.file.Files.createTempFile(directory,"owner-",".lock");
        String classpath=java.nio.file.Path.of(LeaseLockProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,LeaseLockProcess.class.getName(),lock.toString(),"true").redirectError(directory.resolve("owner.stderr").toFile()).start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());var output=process.inputReader();
        try {
            if(!"HELD".equals(executor.submit(output::readLine).get(3,java.util.concurrent.TimeUnit.SECONDS)))throw new AssertionError("fixture processが開始しませんでした");
            var owner=new Owner(process.pid(),process.info().startInstant().orElseThrow());process.getOutputStream().write(0);process.getOutputStream().flush();
            if(!process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new AssertionError("fixture processが終了しませんでした");return owner;
        }finally {
            try {if(process.isAlive())process.destroyForcibly();if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("fixture processを回収できません");output.close();process.getOutputStream().close();}
            finally {executor.shutdownNow();executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);java.nio.file.Files.deleteIfExists(lock);}
        }
    }
    static void owner(java.nio.file.Path file,Owner owner) throws Exception {
        ObjectNode value=(ObjectNode)RuntimeDescriptor.read(java.nio.file.Files.readAllBytes(file));value.put("pid",owner.pid()).put("processStartedAt",owner.started().toString()).put("state","active");
        java.nio.file.Files.write(file,RuntimeDescriptor.JSON.writeValueAsBytes(value));ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));java.nio.file.Files.write(file.resolveSibling("lease.lock"),RuntimeLease.receipt(identity));
    }
    byte[] bytes() throws Exception {return RuntimeDescriptor.JSON.writeValueAsBytes(json);}
    byte[] sign(byte[] bytes) throws Exception {Signature signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(bytes);return signature.sign();}
    RuntimeDescriptor.Pin pin(byte[] bytes) {return new RuntimeDescriptor.Pin(FilesSupport.hash(bytes),"1.1.0",HASH,HASH,new RuntimeDescriptor.Host("linux","x86_64"),Set.of(21));}
    RuntimeDescriptor descriptor() throws Exception {byte[] bytes=bytes();return RuntimeDescriptor.verify(bytes,sign(bytes),key.getPublic(),pin(bytes));}
}
