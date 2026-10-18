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
    byte[] bytes() throws Exception {return RuntimeDescriptor.JSON.writeValueAsBytes(json);}
    byte[] sign(byte[] bytes) throws Exception {Signature signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(bytes);return signature.sign();}
    RuntimeDescriptor.Pin pin(byte[] bytes) {return new RuntimeDescriptor.Pin(FilesSupport.hash(bytes),"1.1.0",HASH,HASH,new RuntimeDescriptor.Host("linux","x86_64"),Set.of(21));}
    RuntimeDescriptor descriptor() throws Exception {byte[] bytes=bytes();return RuntimeDescriptor.verify(bytes,sign(bytes),key.getPublic(),pin(bytes));}
}
