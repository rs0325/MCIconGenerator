package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** OS別Mainからだけ使う内部protocol。provider探索や公開SPIは持たない。 */
public final class HelperProtocol {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private HelperProtocol() {}
    public record Keeper(long pid,long started,String attempt,Instant deadline,boolean recovery) {}
    public record Command(Path root,Path lease,String operation,List<String> dockerArguments,Keeper keeper) {
        public Command(Path root,Path lease,String operation,List<String> dockerArguments){this(root,lease,operation,dockerArguments,null);}
    }
    public record Diagnostic(String code,String detail,boolean requiresPrivilege,boolean requiresRestart) {}
    /** 固定argvを解釈する。shell文字列やhost PATHへ変換しない。 */
    public static Command parse(String[] args) throws IOException {
        if(args.length<7||!args[0].equals("--protocol")||!args[1].equals("1")||!args[2].equals("--root")||!args[4].equals("--lease"))throw invalid("helper引数が一致しません");
        Path root=Path.of(args[3]),lease=Path.of(args[5]);if(!root.isAbsolute()||!lease.isAbsolute())throw invalid("helperには絶対pathが必要です");
        String operation=args[6];List<String> docker=List.of();Keeper keeper=null;
        if(operation.equals("docker")) {if(args.length<9||!args[7].equals("--"))throw invalid("Docker argvが不足しています");docker=List.copyOf(Arrays.asList(args).subList(8,args.length));}
        else if(operation.equals("keep")) {
            if(args.length!=16||!args[7].equals("--controller")||!args[10].equals("--attempt")||!args[12].equals("--deadline")||!args[14].equals("--recovery"))throw invalid("keeper引数が一致しません");
            try {
                long pid=Long.parseLong(args[8]),started=Long.parseLong(args[9]);String attempt=UUID.fromString(args[11]).toString();Instant deadline=Instant.parse(args[13]);
                if(pid<1||started<1||!attempt.equals(args[11])||!Set.of("true","false").contains(args[15]))throw invalid("keeper controllerが不正です");
                keeper=new Keeper(pid,started,attempt,deadline,Boolean.parseBoolean(args[15]));
            }catch(IllegalArgumentException|java.time.DateTimeException failure){throw invalid("keeper identityが不正です");}
        }
        else if(args.length!=7||!Set.of("preflight","prepare","release").contains(operation))throw invalid("helper操作が一致しません");
        return new Command(root.normalize(),lease.normalize(),operation,docker,keeper);
    }
    /** controlだけのUTF-8応答。Docker/workerのstdoutには書き込まない。 */
    public static byte[] response(String status,List<Diagnostic> diagnostics) throws IOException {
        exitCode(status);if(diagnostics.size()>4096)throw invalid("診断件数が上限を超えています");
        var root=JSON.createObjectNode().put("protocolVersion",1).put("status",status);var array=root.putArray("diagnostics");
        for(var diagnostic:diagnostics) {
            if(diagnostic.code()==null||diagnostic.code().isEmpty()||diagnostic.code().length()>256||diagnostic.detail()==null||diagnostic.detail().isEmpty()||diagnostic.detail().length()>16384)throw invalid("診断内容が不正です");
            array.addObject().put("code",diagnostic.code()).put("detail",diagnostic.detail()).put("requiresPrivilege",diagnostic.requiresPrivilege()).put("requiresRestart",diagnostic.requiresRestart());
        }
        byte[] bytes=JSON.writeValueAsBytes(root);if(bytes.length>1024*1024)throw invalid("control応答が上限を超えています");return bytes;
    }
    public static int exitCode(String status) throws IOException {return switch(status){case "ok"->0;case "setup-required"->2;case "failed"->1;default->throw invalid("control statusが不正です");};}
    static JsonNode read(byte[] bytes) throws IOException {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JsonNode node=JSON.readTree(text);if(node==null||!node.isObject())throw invalid("JSON objectが必要です");return node;
    }
    static void fields(JsonNode node,Set<String> required,Set<String> allowed) throws IOException {
        if(node==null||!node.isObject())throw invalid("objectが必要です");var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
        if(!names.containsAll(required)||!allowed.containsAll(names))throw invalid("必須fieldまたは未知fieldが不正です");
    }
    static void fields(JsonNode node,String... names) throws IOException {fields(node,Set.of(names),Set.of(names));}
    static String text(JsonNode node,String name,int limit) throws IOException {JsonNode value=node.get(name);if(value==null||!value.isTextual()||value.textValue().isEmpty()||value.textValue().length()>limit)throw invalid("文字列が不正です: "+name);return value.textValue();}
    static long integer(JsonNode node,String name,long minimum) throws IOException {JsonNode value=node.get(name);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<minimum)throw invalid("整数が不正です: "+name);return value.longValue();}
    static String hash(JsonNode node,String name) throws IOException {String value=text(node,name,64);if(!value.matches("[0-9a-f]{64}"))throw invalid("SHA-256が不正です: "+name);return value;}
    static String uuid(JsonNode node,String name) throws IOException {String value=text(node,name,36);try {if(!UUID.fromString(value).toString().equals(value))throw invalid("UUIDが不正です");}catch(IllegalArgumentException failure){throw invalid("UUIDが不正です: "+name);}return value;}
    static void instant(JsonNode node,String name) throws IOException {String value=text(node,name,64);try {if(!value.endsWith("Z"))throw invalid("UTC時刻が必要です");Instant.parse(value);}catch(java.time.DateTimeException failure){throw invalid("時刻が不正です: "+name);}}
    static String relative(JsonNode node,String name) throws IOException {String value=text(node,name,512);if(!value.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")||Arrays.stream(value.split("/")).anyMatch(part->part.equals(".")||part.equals("..")))throw invalid("相対pathが不正です: "+name);return value;}
    static IOException invalid(String message) {return new IOException(message);}
}
