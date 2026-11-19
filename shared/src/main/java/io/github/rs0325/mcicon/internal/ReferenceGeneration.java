package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Set;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 同root内の認証済みhelper世代。file IDやVM/distro incarnationとは区別する。 */
public final class ReferenceGeneration {
    private static final String[] FIELDS={"os","arch","helperDirectory","helperExecutable","descriptorSha256","archiveSha256","inventorySha256"};
    private ReferenceGeneration() {}

    /** 署名・inventory・元実体を検証したsnapshotだけから識別組を作る。 */
    public static ObjectNode of(LeaseSnapshot snapshot) throws IOException {
        snapshot.helperExecutable();
        JsonNode descriptor=snapshot.descriptor(),artifact=null;
        for(JsonNode row:descriptor.path("artifacts"))if(row.path("id").equals(descriptor.path("helper").path("artifactId")))artifact=row;
        if(artifact==null)throw invalid("helper世代のartifactがありません");
        String os=text(descriptor.path("host"),"os",16),arch=text(descriptor.path("host"),"arch",16);
        String directory="helpers/"+os+"-"+arch+"/"+hash(artifact,"sha256");
        ObjectNode result=JSON.createObjectNode().put("os",os).put("arch",arch).put("helperDirectory",directory)
                .put("helperExecutable",directory+"/"+relative(descriptor.path("helper"),"executable"))
                .put("descriptorSha256",hash(snapshot.lease(),"descriptorSha256"))
                .put("archiveSha256",hash(artifact,"sha256")).put("inventorySha256",hash(artifact,"inventorySha256"));
        return canonical(result);
    }
    public static ObjectNode canonical(JsonNode value) throws IOException {
        fields(value,FIELDS);
        String os=text(value,"os",16),arch=text(value,"arch",16);
        if(!Set.of("windows","macos","linux").contains(os)||!Set.of("x86_64","aarch64").contains(arch))throw invalid("参照世代のhostが不正です");
        hash(value,"descriptorSha256");hash(value,"inventorySha256");
        String directory="helpers/"+os+"-"+arch+"/"+hash(value,"archiveSha256");
        if(!directory.equals(relative(value,"helperDirectory"))||!relative(value,"helperExecutable").startsWith(directory+"/"))throw invalid("参照世代のcanonical helper pathが不一致です");
        ObjectNode result=JSON.createObjectNode();for(String field:FIELDS)result.set(field,value.required(field));return result;
    }
}
