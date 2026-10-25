package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;

/** native APIのboot/SIDとHKCU Lxssの登録値を、推測せず扱う。 */
record HostFacts(String sid,String boot,String version,long memoryBytes,int cpus,String defaultGuid,List<Distribution> distributions) {
    record Distribution(String name,String guid,String basePath,int version) {}
    static HostFacts parse(JsonNode value,JsonNode descriptor) throws IOException {
        if(value==null||!value.isObject()||!descriptor.path("host").path("os").asText().equals("windows")||!descriptor.path("host").path("arch").asText().equals("x86_64")||!value.path("arch").asText().equals("x86_64"))throw new IOException("Windows x86_64 helperが必要です");
        String sid=value.path("sid").asText(),boot=value.path("boot").asText(),version=value.path("version").asText(),defaultGuid=value.path("defaultGuid").asText();
        if(!sid.matches("S-1-[0-9-]+")||!uuid(boot)||!defaultGuid.isEmpty()&&!uuid(defaultGuid)||!value.path("memoryBytes").canConvertToLong()||value.path("memoryBytes").asLong()<1||value.path("cpus").asInt()<4||!value.path("distributions").isArray()||compare(version,descriptor.path("minimumOsVersion").asText())<0)throw new IOException("Windows native能力の結果が不正または不足です");
        List<Distribution> distributions=new ArrayList<>();Set<String> names=new HashSet<>(),ids=new HashSet<>();
        for(JsonNode row:value.path("distributions")) {
            String name=row.path("name").asText(),guid=row.path("guid").asText(),base=row.path("basePath").asText();int wslVersion=row.path("version").asInt();
            if(name.isEmpty()||name.length()>256||!uuid(guid)||base.isEmpty()||wslVersion<1||wslVersion>2||!names.add(name.toLowerCase(Locale.ROOT))||!ids.add(guid))throw new IOException("WSL登録の読取結果が不明です");
            distributions.add(new Distribution(name,guid,base,wslVersion));
        }
        if(!defaultGuid.isEmpty()&&!ids.contains(defaultGuid))throw new IOException("WSL default登録が一覧にありません");
        return new HostFacts(sid,boot,version,value.path("memoryBytes").asLong(),value.path("cpus").asInt(),defaultGuid,List.copyOf(distributions));
    }
    Distribution named(String name) throws IOException {for(Distribution row:distributions)if(row.name().equalsIgnoreCase(name)){if(!row.name().equals(name))throw new IOException("WSL名のcase衝突です");return row;}return null;}
    static boolean uuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException failure){return false;}}
    private static int compare(String left,String right) throws IOException {
        if(!left.matches("[0-9]+(?:[.][0-9]+){0,3}")||!right.matches("[0-9]+(?:[.][0-9]+){0,3}"))throw new IOException("Windows版の形式が不正です");
        String[] a=left.split("[.]"),b=right.split("[.]");try{for(int i=0;i<4;i++){int result=Long.compare(i<a.length?Long.parseLong(a[i]):0,i<b.length?Long.parseLong(b[i]):0);if(result!=0)return result;}return 0;}catch(NumberFormatException failure){throw new IOException("Windows版が範囲外です",failure);}
    }
}
