package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** native probeの読取結果から、実証対象のVZ/Rosetta構成とdescriptor下限を検査する。 */
record HostFacts(long uid,String hostBindingSha256,String bootSessionId,long memoryBytes,int cpus,String osVersion) {
    static HostFacts read(Path probe,Commands commands,JsonNode descriptor,String hostId) throws IOException {
        Commands.Result result=commands.capture(List.of(probe.toString(),"--host-salt",hostId),Duration.ofSeconds(5));
        if(result.exit()!=0)throw new IOException("macOS能力の読取に失敗しました");
        return parse(MacFiles.JSON.readTree(result.stdout()),descriptor);
    }
    static HostFacts parse(JsonNode value,JsonNode descriptor) throws IOException {
        JsonNode host=descriptor.path("host");
        if(value==null||!value.isObject()||!host.path("os").asText().equals("macos")||!host.path("arch").asText().equals("aarch64")||!value.path("arch").asText().equals("aarch64"))throw new IOException("現在のMac helperはApple Siliconだけを対象にします");
        if(!value.path("vz").asBoolean()||!value.path("rosetta").asText().equals("installed"))throw new IOException("VZまたは導入済みRosettaが必要です");
        String version=value.path("osVersion").asText();if(compare(version,descriptor.path("minimumOsVersion").asText())<0)throw new IOException("descriptorのmacOS下限を満たしません");
        if(!value.path("uid").isIntegralNumber()||value.path("uid").asLong(-1)<0||!value.path("physicalMemory").canConvertToLong()||value.path("physicalMemory").asLong()<1||value.path("cpus").asInt()<4||value.path("bootSessionId").asText().isEmpty())throw new IOException("macOS能力の結果が不正です");
        String binding=value.path("hostBindingSha256").asText(),boot=value.path("bootSessionId").asText();
        if(!binding.matches("[0-9a-f]{64}"))throw new IOException("host bindingの読取結果が不正です");
        try {if(!UUID.fromString(boot).toString().equals(boot))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("boot識別の読取結果が不正です");}
        return new HostFacts(value.path("uid").asLong(),binding,boot,value.path("physicalMemory").asLong(),value.path("cpus").asInt(),version);
    }
    static void requireNoLimaProcess(Path probe,Path lima,Commands commands) throws IOException {
        Commands.Result result=commands.capture(List.of(probe.toString(),"--owned-executable",lima.toString()),Duration.ofSeconds(5));
        if(result.exit()!=0)throw new IOException("当該managed実行物の現在process観測が不明です");
        JsonNode value=MacFiles.JSON.readTree(result.stdout());
        if(value==null||!value.isObject()||!value.path("complete").isBoolean()||!value.path("complete").booleanValue()||!value.path("matched").isBoolean()||value.path("matched").booleanValue())throw new IOException("当該managed実行物が現在動作し得るため再起動終了を確定しません");
    }
    private static int compare(String left,String right) throws IOException {
        if(!left.matches("[0-9]+(?:[.][0-9]+){0,2}")||!right.matches("[0-9]+(?:[.][0-9]+){0,2}"))throw new IOException("macOS版の形式が不正です");
        String[] a=left.split("[.]"),b=right.split("[.]");
        try {for(int i=0;i<3;i++){int result=Long.compare(i<a.length?Long.parseLong(a[i]):0,i<b.length?Long.parseLong(b[i]):0);if(result!=0)return result;}return 0;}
        catch(NumberFormatException failure){throw new IOException("macOS版が範囲外です",failure);}
    }
}
