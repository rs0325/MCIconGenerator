package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 実coreのhelper展開を使い、全file bytes・owner実行権限・入口到達を非VMで確認する。 */
public final class RuntimeArchiveFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("ZIP、packaging.json、新規出力directoryを指定してください");
        Path archive=Path.of(args[0]).toAbsolutePath(),record=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        Files.createDirectory(output);ObjectMapper json=new ObjectMapper();JsonNode manifest=json.readTree(Files.readAllBytes(record));
        long unpacked=0;Map<String,JsonNode> expected=new TreeMap<>();for(JsonNode row:manifest.path("files")) {unpacked+=row.path("bytes").asLong();expected.put(row.path("path").asText(),row);}
        var artifact=new RuntimeDescriptor.Artifact("host","host-helper",URI.create("https://fixture.invalid/helper.zip"),Files.size(archive),FilesSupport.hash(archive,"SHA-256"),unpacked,"zip","inventory/host.json","0".repeat(64),List.of("MCIconHelper.app/Contents/app/support/share/doc/lima/LICENSE"),List.of(),List.of());
        var store=new RuntimeStore(output.resolve("store"),true,new CancellationToken(),Duration.ofMinutes(2));Path extracted=output.resolve("extracted");
        RuntimeArchive.extract(archive,extracted,artifact,store);Path app=extracted.resolve("MCIconHelper.app");Map<String,Path> actual=new TreeMap<>();
        try(var paths=Files.walk(app)) {for(Path file:paths.toList()) {if(Files.isSymbolicLink(file))throw new AssertionError("linkが展開されました");if(Files.isRegularFile(file))actual.put(app.relativize(file).toString().replace('\\','/'),file);}}
        if(!actual.keySet().equals(expected.keySet()))throw new AssertionError("全file集合が一致しません");
        for(var entry:actual.entrySet()) {
            JsonNode row=expected.get(entry.getKey());Path file=entry.getValue();
            if(Files.size(file)!=row.path("bytes").asLong()||!FilesSupport.hash(file,"SHA-256").equals(row.path("sha256").asText()))throw new AssertionError("展開bytesが一致しません: "+entry.getKey());
            int expectedMode=Integer.parseInt(row.path("mode").asText().substring(2),8),actualMode=(int)Files.getAttribute(file,"unix:mode");
            if((expectedMode&0100)!=(actualMode&0100))throw new AssertionError("owner実行権限が一致しません: "+entry.getKey());
        }
        Process signature=new ProcessBuilder("/usr/bin/codesign","--verify","--deep","--strict",app.toString()).redirectOutput(output.resolve("codesign.stdout").toFile()).redirectError(output.resolve("codesign.stderr").toFile()).start();
        if(!signature.waitFor(15,TimeUnit.SECONDS)){signature.destroyForcibly();if(!signature.waitFor(2,TimeUnit.SECONDS))throw new AssertionError("署名確認childの回収が未確認です");throw new AssertionError("署名確認の期限超過");}if(signature.exitValue()!=0)throw new AssertionError("展開後署名が不正です");
        Path root=output.resolve("invalid-root"),temporary=output.resolve("tmp");Files.createDirectory(root);Files.createDirectory(temporary);
        List<String> argv=List.of(app.resolve("Contents/MacOS/MCIconHelper").toString(),"--protocol","1","--root",root.toString(),"--lease",root.resolve("jobs/00000000-0000-0000-0000-000000000001/lease.json").toString(),"preflight");
        ProcessBuilder builder=new ProcessBuilder(argv).redirectOutput(output.resolve("entry.stdout").toFile()).redirectError(output.resolve("entry.stderr").toFile());builder.environment().clear();for(String key:List.of("TMP","TEMP","TMPDIR"))builder.environment().put(key,temporary.toString());Process entry=builder.start();
        if(!entry.waitFor(10,TimeUnit.SECONDS)){entry.destroyForcibly();if(!entry.waitFor(2,TimeUnit.SECONDS))throw new AssertionError("入口childの回収が未確認です");throw new AssertionError("入口確認の期限超過");}
        JsonNode response=json.readTree(Files.readAllBytes(output.resolve("entry.stdout")));if(entry.exitValue()!=1||!response.path("status").asText().equals("failed")||!response.path("diagnostics").get(0).path("detail").asText().equals("所有fileを読み取れません: lease.json"))throw new AssertionError("invalid lease境界へ到達していません");
        Files.write(output.resolve("result.json"),json.writeValueAsBytes(Map.of("status","PASS-core-RuntimeArchive-only-no-vm","fileCount",actual.size(),"unpackedBytes",unpacked,"allFileBytesVerified",true,"ownerExecutableBitsVerified",true,"codesignDeepStrictExit",0,"entryExit",entry.exitValue(),"vmOrKeyOrGuest",false)));
    }
}
