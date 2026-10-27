package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 固定package trustで同梱seedを検証する。runtime世代や更新channelの取得は扱わない。 */
final class Seed {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Host(String os,String arch) {}
    final Path source;
    final PublicKey key;
    final byte[] manifestBytes,signatureBytes;
    final JsonNode manifest,platform,payload;
    final String manifestHash,version,hash,descriptorHash;
    Seed(Path source,PublicKey key,Host host) throws IOException {
        this.source=source;this.key=key;
        manifestBytes=read(source.resolve("stable.json"),1024*1024);signatureBytes=read(source.resolve("stable.json.sig"),64);signed(manifestBytes,signatureBytes,key);manifest=json(manifestBytes);manifestHash=hash(manifestBytes);
        fields(manifest,"schemaVersion","channel","sequence","toolVersion","minimumBootstrapProtocol","platforms");constant(manifest,"schemaVersion",1);exact(manifest,"channel","stable");positive(manifest,"sequence");constant(manifest,"minimumBootstrapProtocol",2);version=version(manifest,"toolVersion");
        JsonNode selected=null;Set<Host> seen=new HashSet<>();
        for(JsonNode row:array(manifest,"platforms",1,6)) {
            fields(row,"host","payload","runtimeDescriptor");JsonNode h=row.get("host");fields(h,"os","arch");Host candidate=new Host(choice(h,"os","macos","windows","linux"),choice(h,"arch","aarch64","x86_64"));
            if(!seen.add(candidate))throw invalid("seed hostが重複しています");artifact(row.get("payload"));JsonNode descriptor=row.get("runtimeDescriptor");fields(descriptor,"path","sha256");relative(text(descriptor,"path"));digest(descriptor,"sha256");if(candidate.equals(host))selected=row;
        }
        if(selected==null)throw invalid("このhost用のseedがありません");platform=selected;payload=selected.get("payload");hash=digest(payload,"sha256");descriptorHash=digest(selected.get("runtimeDescriptor"),"sha256");
    }
    private static void artifact(JsonNode row) throws IOException {
        fields(row,"id","role","url","bytes","sha256","unpackedBytes","format","inventoryPath","inventorySha256","licenseFiles","noticeFiles","sourceArchives");
        if(!text(row,"id").matches("[a-z0-9][a-z0-9._-]{0,95}"))throw invalid("payload idが不正です");exact(row,"role","tool-payload");exact(row,"format","zip");https(text(row,"url"));positive(row,"bytes");positive(row,"unpackedBytes");digest(row,"sha256");relative(text(row,"inventoryPath"));digest(row,"inventorySha256");
        paths(row,"licenseFiles",1);paths(row,"noticeFiles",0);for(JsonNode source:array(row,"sourceArchives",0,100000)){fields(source,"url","sha256");https(text(source,"url"));digest(source,"sha256");}
    }
    private static void paths(JsonNode value,String field,int minimum) throws IOException {Set<String> seen=new HashSet<>();for(JsonNode path:array(value,field,minimum,100000)){if(!path.isTextual()||!seen.add(path.textValue()))throw invalid("文書pathが重複/不正です");relative(path.textValue());}}
    void verifyArchive(Path archive,long deadline) throws IOException {regular(archive);if(Files.size(archive)!=positive(payload,"bytes")||!hash(archive,deadline).equals(hash))throw invalid("seed ZIPのsize/hashが不一致です");}
    void extract(Bootstrap files,Path archive,Path destination,long deadline) throws IOException {unpack(Objects.requireNonNull(files),archive,destination,deadline);}
    /** 包装検証toolの新規一時directory専用。利用者stateの展開には使用しない。 */
    void extractForPackage(Path archive,Path destination,long deadline) throws IOException {unpack(null,archive,destination,deadline);}
    private void unpack(Bootstrap files,Path archive,Path destination,long deadline) throws IOException {
        check(deadline);verifyArchive(archive,deadline);check(deadline);if(files!=null&&!files.host.os().equals("windows"))files=null;if(files==null){Files.createDirectory(destination);if(Files.getFileStore(destination).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(destination,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}else files.newDirectory(destination,deadline);check(deadline);Set<String> names=new HashSet<>();long total=0;
        try(ZipFile zip=ZipFile.builder().setPath(archive).get()) {
            var entries=zip.getEntries();while(entries.hasMoreElements()) {
                check(deadline);ZipArchiveEntry entry=entries.nextElement();String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(entry.getRawName())).toString();String name=entry.isDirectory()&&raw.endsWith("/")?raw.substring(0,raw.length()-1):raw;relative(name);
                int kind=entry.getUnixMode()&0170000;if(entry.isUnixSymlink()||kind!=0&&kind!=0100000&&kind!=0040000||!zip.canReadEntryData(entry)||!names.add(name)||!raw.equals(entry.getName()))throw invalid("ZIPのlink/特殊/重複entryを拒否しました");
                Path target=destination.resolve(name);if(entry.isDirectory()){if(files==null)Files.createDirectories(target);else files.directory(target,deadline);check(deadline);continue;}
                long size=entry.getSize();if(size<0||size>positive(payload,"unpackedBytes")-total)throw invalid("展開上限を超えています");if(files==null)Files.createDirectories(target.getParent());else files.directory(target.getParent(),deadline);check(deadline);long written=0;
                try(InputStream input=zip.getInputStream(entry);OutputStream output=files==null?Files.newOutputStream(target,StandardOpenOption.CREATE_NEW):java.nio.channels.Channels.newOutputStream(files.create(target,deadline))) {check(deadline);byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){check(deadline);if(count>size-written)throw invalid("ZIP entry sizeを超えています");output.write(buffer,0,count);written+=count;}}
                if(written!=size)throw invalid("ZIP entryが途中です");total+=written;check(deadline);if((entry.getUnixMode()&0111)!=0&&!target.toFile().setExecutable(true,true))throw invalid("実行属性を設定できません");
            }
        }
        check(deadline);verifyContents(destination);check(deadline);
    }
    void verifyContents(Path slot) throws IOException {
        Path inventory=resolve(slot,text(payload,"inventoryPath"));if(!hash(read(inventory,4*1024*1024)).equals(digest(payload,"inventorySha256")))throw invalid("inventory hashが不一致です");
        Path descriptor=resolve(slot,text(platform.get("runtimeDescriptor"),"path"));byte[] raw=read(descriptor,1024*1024);if(!hash(raw).equals(descriptorHash))throw invalid("runtime descriptor hashが不一致です");signed(raw,read(Path.of(descriptor+".sig"),64),key);JsonNode definition=json(raw);
        if(!text(definition,"toolVersion").equals(version)||!definition.path("host").equals(platform.get("host")))throw invalid("runtime descriptorの版/host/protocolが不一致です");constant(definition,"schemaVersion",1);constant(definition.get("helper"),"protocolVersion",1);
        for(String name:List.of("licenseFiles","noticeFiles"))for(JsonNode file:payload.get(name))regular(resolve(slot,file.asText()));
        regular(resolve(slot,"java/bin/"+(platform.path("host").path("os").asText().equals("windows")?"java.exe":"java")));
        for(String name:List.of("cli","core","worker"))regular(resolve(slot,"app/lib/"+name+"-"+version+".jar"));regular(resolve(slot,"app/worker/worker-agent.jar"));
    }
    JsonNode reference() {return JSON.createObjectNode().put("toolVersion",version).put("payloadSha256",hash).put("runtimeDescriptorSha256",descriptorHash).put("manifestSha256",manifestHash);}
    static Map<String,String> tree(Bootstrap files,Path directory,long deadline) throws IOException {
        Map<String,String> values=new TreeMap<>();tree(files,directory,directory,deadline,values);return values;
    }
    private static void tree(Bootstrap files,Path root,Path path,long deadline,Map<String,String> values) throws IOException {
        check(deadline);boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
        if(files.host.os().equals("windows")){if(directory)files.directory(path,deadline);else files.file(path,deadline);}
        else if(Files.isSymbolicLink(path)||!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(root,LinkOption.NOFOLLOW_LINKS)))throw invalid("slotのlink/所有が不一致です");
        check(deadline);String name=root.relativize(path).toString().replace('\\','/');
        if(directory) {
            if(!path.equals(root))values.put(name,"directory");
            try(DirectoryStream<Path> children=Files.newDirectoryStream(path)){for(Path child:children)tree(files,root,child,deadline,values);}
        } else {regular(path);values.put(name,Files.size(path)+":"+hash(path,deadline)+":"+Files.isExecutable(path));}
        check(deadline);
    }
    static JsonNode json(byte[] raw) throws IOException {try {return JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString());}catch(RuntimeException failure){throw new IOException("JSONが不正です",failure);}}
    static byte[] read(Path path,int maximum) throws IOException {regular(path);if(Files.size(path)>maximum)throw invalid("fileが上限を超えています");try(InputStream input=Files.newInputStream(path)){byte[] raw=input.readNBytes(maximum+1);if(raw.length>maximum)throw invalid("fileが上限を超えています");return raw;}}
    static void signed(byte[] bytes,byte[] signature,PublicKey key) throws IOException {try {if(signature.length!=64)throw invalid("署名は64bytesが必要です");Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(bytes);if(!verifier.verify(signature))throw invalid("署名が一致しません");}catch(GeneralSecurityException failure){throw new IOException("署名を検証できません",failure);}}
    static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hash(Path path) throws IOException {return hash(path,Long.MAX_VALUE);}
    static String hash(Path path,long deadline) throws IOException {try(InputStream input=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){check(deadline);digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static void regular(Path path) throws IOException {if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw invalid("通常fileではありません: "+path.getFileName());}
    static Path resolve(Path root,String path) throws IOException {relative(path);Path target=root.resolve(path);for(Path p=target;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw invalid("pathにlinkがあります");return target;}
    static void relative(String path) throws IOException {if(path.length()>512||!path.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")||Arrays.stream(path.split("/")).anyMatch(s->s.equals(".")||s.equals("..")))throw invalid("相対pathが不正です");}
    static void fields(JsonNode value,String... names) throws IOException {if(value==null||!value.isObject()||value.size()!=names.length)throw invalid("必須/未知fieldが不正です");for(String name:names)if(!value.has(name))throw invalid("必須fieldがありません: "+name);}
    static String text(JsonNode value,String name) throws IOException {JsonNode v=value==null?null:value.get(name);if(v==null||!v.isTextual()||v.asText().isEmpty())throw invalid("文字列が不正です: "+name);return v.asText();}
    static long positive(JsonNode value,String name) throws IOException {JsonNode v=value==null?null:value.get(name);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw invalid("正整数が不正です: "+name);return v.longValue();}
    static void constant(JsonNode value,String name,long expected) throws IOException {if(positive(value,name)!=expected)throw invalid(name+"が未対応です");}
    static void exact(JsonNode value,String name,String expected) throws IOException {if(!text(value,name).equals(expected))throw invalid(name+"が不一致です");}
    static String choice(JsonNode value,String name,String... choices) throws IOException {String v=text(value,name);if(!List.of(choices).contains(v))throw invalid(name+"が未対応です");return v;}
    static String digest(JsonNode value,String name) throws IOException {String v=text(value,name);if(!v.matches("[0-9a-f]{64}"))throw invalid("SHA-256が不正です");return v;}
    static String version(JsonNode value,String name) throws IOException {String v=text(value,name);if(!v.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw invalid("正式版が必要です");return v;}
    static Iterable<JsonNode> array(JsonNode value,String name,int minimum,int maximum) throws IOException {JsonNode v=value==null?null:value.get(name);if(v==null||!v.isArray()||v.size()<minimum||v.size()>maximum)throw invalid("配列が不正です");return v;}
    static void https(String text) throws IOException {try {URI uri=URI.create(text);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw invalid("HTTPS URLが不正です");}catch(IllegalArgumentException e){throw new IOException("URLが不正です",e);}}
    static void check(long deadline) throws IOException {if(Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline)throw invalid("bootstrap準備期限を超えました");}
    static IOException invalid(String message) {return new IOException(message);}
}
