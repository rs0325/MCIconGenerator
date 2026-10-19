package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;
import static io.github.rs0325.mcicon.helper.HelperProtocol.*;

/** 採用済みOS recordの閉じた形だけを検証する。実OSの所有照合や停止権限を代替しない。 */
final class ResourceRecords {
    private ResourceRecords() {}
    static void validate(JsonNode resources,String os) throws IOException {
        if(!resources.isObject())throw invalid("resourcesはobjectが必要です");if(resources.isEmpty())return;
        if(!Set.of("macos","windows","linux").contains(os))throw invalid("このOSの所有schemaは未採用です");fields(resources,os);JsonNode value=resources.get(os);
        switch(os) {case "macos"->macos(value);case "windows"->windows(value);case "linux"->linux(value);}
    }
    private static void macos(JsonNode value) throws IOException {
        fields(value,Set.of("phase","generationOwner","instanceDir","containers"),Set.of("phase","generationOwner","instanceDir","containers","machineIdSha256","hostAgent","dockerSocket","forwardConfigSha256","guestOwnerMarkerSha256"));
        String phase=text(value,"phase",32);if(!Set.of("starting","ready","releasing").contains(phase))throw invalid("Mac phaseが不正です");uuid(value,"generationOwner");text(value,"instanceDir",4096);
        if(phase.equals("ready"))for(String name:List.of("machineIdSha256","hostAgent","dockerSocket","forwardConfigSha256","guestOwnerMarkerSha256"))if(!value.has(name))throw invalid("readyの所有情報が不足しています");
        for(String name:List.of("machineIdSha256","forwardConfigSha256","guestOwnerMarkerSha256"))if(value.has(name))hash(value,name);
        if(value.has("hostAgent")) {JsonNode process=value.get("hostAgent");fields(process,"pid","processStartedAt","bootSessionId");integer(process,"pid",1);instant(process,"processStartedAt");text(process,"bootSessionId",256);}
        if(value.has("dockerSocket")) {JsonNode socket=value.get("dockerSocket");fields(socket,"path","dev","ino","uid");text(socket,"path",4096);for(String name:List.of("dev","ino","uid"))integer(socket,name,0);}
        containers(value.get("containers"),false,Set.of());
    }
    private static void windows(JsonNode value) throws IOException {
        fields(value,Set.of("phase","generationOwner","distribution","transports","containers"),Set.of("phase","generationOwner","distribution","transports","containers","socketPath","guestJobPath"));
        String phase=text(value,"phase",32);if(!Set.of("importing","ready","releasing").contains(phase))throw invalid("Windows phaseが不正です");uuid(value,"generationOwner");
        JsonNode distribution=value.get("distribution");boolean registered=distribution.has("guid")||distribution.has("markerSha256");
        if(registered){fields(distribution,"name","basePath","guid","markerSha256");uuid(distribution,"guid");hash(distribution,"markerSha256");}else fields(distribution,"name","basePath");
        text(distribution,"name",256);text(distribution,"basePath",4096);
        if(phase.equals("ready")&&(!registered||!value.has("socketPath")||!value.has("guestJobPath")))throw invalid("readyの所有情報が不足しています");
        for(String name:List.of("socketPath","guestJobPath"))if(value.has(name))text(value,name,4096);
        JsonNode transports=value.get("transports");if(!transports.isArray())throw invalid("transportsはarrayが必要です");Set<String> ids=new HashSet<>();
        for(JsonNode transport:transports) {
            if(transport.has("pid")||transport.has("processStartedAt")){fields(transport,"id","pid","processStartedAt");integer(transport,"pid",1);instant(transport,"processStartedAt");}else fields(transport,"id");
            if(!ids.add(uuid(transport,"id")))throw invalid("transport IDが重複しています");
        }
        containers(value.get("containers"),true,ids);
    }
    private static void linux(JsonNode value) throws IOException {
        fields(value,Set.of("phase","generationOwner","runtimeDir","hostBootId","uid","containers"),Set.of("phase","generationOwner","runtimeDir","hostBootId","uid","containers","userUnit","rootlesskit","dockerd","userNamespace","dockerSocket"));
        String phase=text(value,"phase",32);if(!Set.of("starting","ready","releasing").contains(phase))throw invalid("Linux phaseが不正です");
        String owner=uuid(value,"generationOwner");uuid(value,"hostBootId");linuxPath(value,"runtimeDir");long uid=integer(value,"uid",1);if(uid>4294967294L)throw invalid("Linux UIDが範囲外です");
        if(phase.equals("ready"))for(String name:List.of("userUnit","rootlesskit","dockerd","userNamespace","dockerSocket"))if(!value.has(name))throw invalid("Linux readyの所有情報が不足しています");
        if(value.has("userUnit")) {
            JsonNode unit=value.get("userUnit");fields(unit,Set.of("name","path","sha256"),Set.of("name","path","sha256","controlGroup"));
            String name=text(unit,"name",256);if(!name.equals("mcicon-rootless-"+owner+".service")||!linuxPath(unit,"path").endsWith("/"+name))throw invalid("Linux unitの世代が不正です");hash(unit,"sha256");
            if(phase.equals("ready")&&!unit.has("controlGroup"))throw invalid("Linux readyのunit cgroupがありません");
            if(unit.has("controlGroup")) {
                String group=linuxPath(unit,"controlGroup");if(!group.startsWith("/user.slice/user-"+uid+".slice/user@"+uid+".service/")||!group.endsWith("/"+name))throw invalid("Linux unit cgroupが一致しません");
            }
        }
        for(String name:List.of("rootlesskit","dockerd"))if(value.has(name)) {JsonNode process=value.get(name);fields(process,"pid","startTicks");integer(process,"pid",1);integer(process,"startTicks",0);}
        if(value.has("rootlesskit")&&value.has("dockerd")&&value.path("rootlesskit").path("pid").longValue()==value.path("dockerd").path("pid").longValue())throw invalid("Linux process identityが重複しています");
        if(value.has("userNamespace")) {JsonNode namespace=value.get("userNamespace");fields(namespace,"id","uidMapSha256","gidMapSha256");integer(namespace,"id",1);hash(namespace,"uidMapSha256");hash(namespace,"gidMapSha256");}
        if(value.has("dockerSocket")) {
            JsonNode socket=value.get("dockerSocket");fields(socket,"path","dev","ino","uid");linuxPath(socket,"path");for(String name:List.of("dev","ino","uid"))integer(socket,name,0);
            if(integer(socket,"uid",0)!=uid)throw invalid("Linux socketのUIDが一致しません");
        }
        JsonNode containers=value.get("containers");if(!containers.isArray())throw invalid("containersはarrayが必要です");Set<String> names=new HashSet<>(),ids=new HashSet<>();
        for(JsonNode container:containers) {
            if(container.has("id")||container.has("startedAt")){fields(container,"name","id","startedAt");instant(container,"startedAt");if(!ids.add(hash(container,"id")))throw invalid("container IDが重複しています");}else fields(container,"name");
            if(!names.add(text(container,"name",256)))throw invalid("container名が重複しています");
        }
    }
    private static String linuxPath(JsonNode node,String name) throws IOException {
        String path=text(node,name,4096);if(!path.startsWith("/")||path.indexOf('\0')>=0||Arrays.stream(path.substring(1).split("/",-1)).anyMatch(part->part.isEmpty()||part.equals(".")||part.equals("..")))throw invalid("Linux pathが不正です: "+name);return path;
    }
    private static void containers(JsonNode containers,boolean windows,Set<String> transports) throws IOException {
        if(!containers.isArray())throw invalid("containersはarrayが必要です");Set<String> names=new HashSet<>(),ids=new HashSet<>();
        for(JsonNode container:containers) {
            fields(container,windows?Set.of("name","transportId"):Set.of("name"),windows?Set.of("name","transportId","id"):Set.of("name","id"));
            if(!names.add(text(container,"name",256)))throw invalid("container名が重複しています");
            if(container.has("id")&&!ids.add(hash(container,"id")))throw invalid("container IDが重複しています");
            if(windows&&!transports.contains(uuid(container,"transportId")))throw invalid("containerのtransport意図がありません");
        }
    }
}
