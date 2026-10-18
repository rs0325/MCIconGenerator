package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rs0325.mcicon.McIconException;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.net.URI;
import java.security.*;
import java.util.*;

/** 署名済み元bytesと同梱pinを照合した、不変の管理環境指定。更新channelは参照しない。 */
public record RuntimeDescriptor(String digest,String runtimeId,String toolVersion,Host host,String minimumOsVersion,
        String helperArtifact,String helperExecutable,String agentSha256,String adapterSetSha256,
        String imageArtifact,String imageId,List<JavaRuntime> javaExecutables,Map<String,String> environment,List<Artifact> artifacts) {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record Host(String os,String arch) {}
    public record JavaRuntime(int major,String executable,String sha256) {}
    public record Source(URI url,String sha256) {}
    public record Artifact(String id,String role,URI url,long bytes,String sha256,long unpackedBytes,String format,
            String inventoryPath,String inventorySha256,List<String> licenseFiles,List<String> noticeFiles,List<Source> sourceArchives) {}
    /** coreに同梱した値を渡す。鍵IDやdescriptor内の値を信頼根にしない。 */
    public record Pin(String descriptorSha256,String toolVersion,String agentSha256,String adapterSetSha256,Host host,Set<Integer> javaMajors) {}

    /** hashとEd25519を元bytesで検証してから、schemaと意味上の互換条件を検証する。 */
    public static RuntimeDescriptor verify(byte[] original,byte[] signature,PublicKey trustKey,Pin pin) {
        try {
            if(original.length>1024*1024||!FilesSupport.hash(original).equals(pin.descriptorSha256()))throw invalid("descriptorの固定hashが一致しません");
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(trustKey);verifier.update(original);
            if(!verifier.verify(signature))throw invalid("descriptorの署名が一致しません");
            JsonNode root=read(original);
            fields(root,"schemaVersion","runtimeId","toolVersion","host","minimumOsVersion","helper","worker","rendering","artifacts");
            constant(root,"schemaVersion",1);
            String runtimeId=id(root,"runtimeId"),tool=version(root,"toolVersion");
            JsonNode h=root.get("host");fields(h,"os","arch");
            Host host=new Host(choice(h,"os","macos","windows","linux"),choice(h,"arch","aarch64","x86_64"));
            String minimum=text(root,"minimumOsVersion",64);
            if(!minimum.matches("[0-9]+(?:[.][0-9]+)*(?:[-+][A-Za-z0-9._-]+)?"))throw invalid("OS下限の数値版が不正です");
            JsonNode helper=root.get("helper");fields(helper,"protocolVersion","artifactId","executable");constant(helper,"protocolVersion",1);
            String helperId=id(helper,"artifactId"),executable=relative(text(helper,"executable",512));
            JsonNode worker=root.get("worker");fields(worker,"toolVersion","agentSha256","protocolVersion","adapterSetSha256");constant(worker,"protocolVersion",1);
            String workerVersion=version(worker,"toolVersion"),agent=hash(worker,"agentSha256"),adapters=hash(worker,"adapterSetSha256");
            if(!tool.equals(pin.toolVersion())||!tool.equals(workerVersion)||!agent.equals(pin.agentSha256())||!adapters.equals(pin.adapterSetSha256())||!host.equals(pin.host()))throw invalid("coreとdescriptorの互換条件が一致しません");
            JsonNode rendering=root.get("rendering");fields(rendering,"platform","imageArtifactId","imageId","contextProfile","javaExecutables","mesaVersion","llvmVersion","environment","cpuRequirement");
            exact(rendering,"platform","linux/amd64");exact(rendering,"contextProfile","canonical-v1");exact(rendering,"cpuRequirement","sse4.1");
            text(rendering,"mesaVersion",256);text(rendering,"llvmVersion",256);
            String imageArtifact=id(rendering,"imageArtifactId"),image=text(rendering,"imageId",71);
            if(!image.matches("sha256:[0-9a-f]{64}"))throw invalid("image IDが不正です");
            List<JavaRuntime> java=new ArrayList<>();Set<Integer> majors=new HashSet<>();
            for(JsonNode item:array(rendering,"javaExecutables",1,4)) {
                fields(item,"major","executable","sha256");long major=positive(item,"major");
                if(!Set.of(8L,17L,21L,25L).contains(major)||!majors.add((int)major))throw invalid("Java majorが不正または重複しています");
                String path=text(item,"executable",4096);
                if(!path.matches("/[^\\s]+/bin/java")||path.contains("\\")||Arrays.stream(path.substring(1).split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))throw invalid("Java実行pathが不正です");
                java.add(new JavaRuntime((int)major,path,hash(item,"sha256")));
            }
            if(!majors.containsAll(pin.javaMajors()))throw invalid("adapterに必要なJava majorが不足しています");
            JsonNode env=rendering.get("environment");if(!env.isObject())throw invalid("環境変数はobjectが必要です");
            Map<String,String> environment=new TreeMap<>();
            var names=env.fieldNames();while(names.hasNext()) {String name=names.next();if(!name.matches("[A-Z][A-Z0-9_]*"))throw invalid("環境変数名が不正です");environment.put(name,text(env,name,1024,true));}
            List<Artifact> artifacts=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonNode item:array(root,"artifacts",2,3)) {
                fields(item,"id","role","url","bytes","sha256","unpackedBytes","format","inventoryPath","inventorySha256","licenseFiles","noticeFiles","sourceArchives");
                String artifactId=id(item,"id");if(!ids.add(artifactId))throw invalid("artifact IDが重複しています");
                String role=choice(item,"role","host-helper","guest-base","renderer-image");
                List<Source> sources=new ArrayList<>();for(JsonNode source:array(item,"sourceArchives",0,Integer.MAX_VALUE)) {fields(source,"url","sha256");sources.add(new Source(https(text(source,"url",8192)),hash(source,"sha256")));}
                artifacts.add(new Artifact(artifactId,role,https(text(item,"url",8192)),positive(item,"bytes"),hash(item,"sha256"),positive(item,"unpackedBytes"),
                        choice(item,"format","zip","tar.gz","tar.xz","qcow2"),relative(text(item,"inventoryPath",512)),hash(item,"inventorySha256"),
                        paths(item,"licenseFiles",1),paths(item,"noticeFiles",0),List.copyOf(sources)));
            }
            if(artifacts.stream().filter(a->a.id().equals(helperId)&&a.role().equals("host-helper")).count()!=1||artifacts.stream().filter(a->a.id().equals(imageArtifact)&&a.role().equals("renderer-image")).count()!=1)throw invalid("helper/image参照が正しいroleに解決しません");
            if(artifacts.stream().map(Artifact::role).distinct().count()!=artifacts.size())throw invalid("artifact roleが重複しています");
            return new RuntimeDescriptor(pin.descriptorSha256(),runtimeId,tool,host,minimum,helperId,executable,agent,adapters,imageArtifact,image,List.copyOf(java),Map.copyOf(environment),List.copyOf(artifacts));
        } catch(McIconException failure) {throw failure;}
        catch(Exception failure) {throw new McIconException("MANAGED_RUNTIME_INVALID","descriptorの署名または形式を検証できません",1,failure);}
    }
    public Artifact artifact(String id) {return artifacts.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->invalid("artifact参照が見つかりません"));}
    public void verifyInventory(Artifact artifact,byte[] bytes) {if(!FilesSupport.hash(bytes).equals(artifact.inventorySha256()))throw invalid("inventoryのhashが一致しません");}
    static JsonNode read(byte[] bytes) throws java.io.IOException {
        String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return JSON.readTree(text);
    }
    static McIconException invalid(String message) {return new McIconException("MANAGED_RUNTIME_INVALID",message,1);}
    static String relative(String value) {if(!value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")||Arrays.stream(value.split("/")).anyMatch(s->s.equals(".")||s.equals("..")))throw invalid("相対pathが不正です");return value;}
    static void fields(JsonNode node,String... expected) {if(node==null||!node.isObject()||node.size()!=expected.length)throw invalid("必須fieldまたは未知fieldが不正です");for(String name:expected)if(!node.has(name))throw invalid("必須fieldがありません: "+name);}
    static String text(JsonNode node,String name,int limit) {return text(node,name,limit,false);}
    static String text(JsonNode node,String name,int limit,boolean empty) {JsonNode value=node.get(name);if(value==null||!value.isTextual()||(!empty&&value.textValue().isEmpty())||value.textValue().length()>limit)throw invalid("文字列fieldが不正です: "+name);return value.textValue();}
    static String id(JsonNode node,String name) {String value=text(node,name,96);if(!value.matches("[a-z0-9][a-z0-9._-]{0,95}"))throw invalid("IDが不正です");return value;}
    static String version(JsonNode node,String name) {String value=text(node,name,256);if(!value.matches("[0-9]+[.][0-9]+[.][0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"))throw invalid("tool版が不正です");return value;}
    static String hash(JsonNode node,String name) {String value=text(node,name,64);if(!value.matches("[0-9a-f]{64}"))throw invalid("SHA-256が不正です");return value;}
    static String choice(JsonNode node,String name,String... values) {String value=text(node,name,256);if(!List.of(values).contains(value))throw invalid("未対応のfield値です: "+name);return value;}
    static void exact(JsonNode node,String name,String value) {if(!text(node,name,256).equals(value))throw invalid("固定値が一致しません: "+name);}
    static void constant(JsonNode node,String name,int value) {JsonNode number=node.get(name);if(number==null||!number.isIntegralNumber()||!number.canConvertToInt()||number.intValue()!=value)throw invalid("protocol/schema版が一致しません");}
    static long positive(JsonNode node,String name) {JsonNode value=node.get(name);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw invalid("正の整数が必要です: "+name);return value.longValue();}
    static JsonNode array(JsonNode node,String name,int minimum,int maximum) {JsonNode value=node.get(name);if(value==null||!value.isArray()||value.size()<minimum||value.size()>maximum)throw invalid("配列fieldが不正です: "+name);return value;}
    static List<String> paths(JsonNode node,String name,int minimum) {List<String> result=new ArrayList<>();for(JsonNode value:array(node,name,minimum,Integer.MAX_VALUE)) {if(!value.isTextual()||value.textValue().length()>512)throw invalid("pathが不正です");result.add(relative(value.textValue()));}if(new HashSet<>(result).size()!=result.size())throw invalid("pathが重複しています");return List.copyOf(result);}
    static URI https(String value) {try {URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw invalid("HTTPS URLが不正です");return uri;}catch(IllegalArgumentException failure){throw invalid("HTTPS URLが不正です");}}
}
