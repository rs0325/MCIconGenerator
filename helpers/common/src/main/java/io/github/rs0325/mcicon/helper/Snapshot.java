package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** OS helper内部で入力を固定し、完了PNGだけを元jobへ返す。公開libraryのSPIではない。 */
public final class Snapshot {
    private static final String CACHE="/mcicon/cache/";
    private final Path source,staged,run;
    /** guest内の相対PNGをhost stagingへ取得する。未完了・所有不明では例外にする。 */
    public interface Fetcher {void fetch(Path relative,Path target) throws IOException;}
    private Fetcher fetcher;
    private Snapshot(Path source,Path staged,Path run){this.source=source;this.staged=staged;this.run=run;}
    /** 元cache/agent hashとworker参照を照合し、未使用stagedへ必要fileだけを複製する。 */
    public static Snapshot create(Path source,Path agent,Path staged,List<String> arguments,String agentHash) throws IOException {
        source=source.toAbsolutePath().normalize();agent=agent.toAbsolutePath().normalize();
        if(!source.toRealPath().equals(source)||!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)||!agent.startsWith(source)||!agent.toRealPath().equals(agent)||!hash(agent).equals(agentHash))throw new IOException("worker snapshotの元cache/agentが一致しません");
        Path run=source.relativize(agent.getParent());if(run.getNameCount()!=1||!run.toString().startsWith("worker-"))throw new IOException("worker job directoryが一致しません");
        Snapshot result=new Snapshot(source,staged,run);Set<Path> inputs=new LinkedHashSet<>();Map<Path,String> references=new HashMap<>();Set<Path> directories=new HashSet<>();inputs.add(source.relativize(agent));references.put(source.relativize(agent),agentHash);
        String classpath=argument(arguments,"-cp"),assets=argument(arguments,"--assetsDir"),index=argument(arguments,"--assetIndex"),game=argument(arguments,"--gameDir");
        if(!index.matches("[A-Za-z0-9._-]+")||index.equals(".")||index.equals(".."))throw new IOException("asset index名が不正です");
        for(String part:classpath.split(":"))inputs.add(result.relative(part));
        for(String argument:arguments) {
            if(argument.startsWith("-Djava.library.path=")) {Path nativePath=result.relative(argument.substring("-Djava.library.path=".length()));result.directory(inputs,nativePath);directories.add(nativePath);}
            if(argument.startsWith("-javaagent:/mcicon/worker.jar=")) {
                Path config=result.relative(argument.substring("-javaagent:/mcicon/worker.jar=".length()));if(!config.equals(run.resolve("agent.json")))throw new IOException("agent設定がjob外です");inputs.add(config);
                byte[] configBytes=Files.readAllBytes(result.original(config));if(configBytes.length>1024*1024)throw new IOException("agent設定が上限を超えています");references.put(config,hash(configBytes));JsonNode value=HelperProtocol.JSON.readTree(configBytes);if(value.has("mappings"))inputs.add(result.relative(value.path("mappings").asText()));
            }
        }
        if(!references.containsKey(run.resolve("agent.json")))throw new IOException("job固有agent設定がありません");
        Path gamePath=result.relative(game);if(!gamePath.equals(run.resolve("game")))throw new IOException("game directoryがjob外です");inputs.add(gamePath.resolve("options.txt"));directories.add(gamePath);
        Path assetsPath=result.relative(assets);if(!assetsPath.equals(Path.of("assets")))throw new IOException("assets directoryが一致しません");
        Path indexPath=assetsPath.resolve("indexes").resolve(index+".json");inputs.add(indexPath);Path indexFile=result.original(indexPath);
        if(Files.size(indexFile)>64L*1024*1024)throw new IOException("asset indexが上限を超えています");byte[] indexBytes=Files.readAllBytes(indexFile);references.put(indexPath,hash(indexBytes));JsonNode objects=HelperProtocol.JSON.readTree(indexBytes).path("objects");if(!objects.isObject())throw new IOException("asset index objectsがありません");
        for(var fields=objects.fields();fields.hasNext();) {
            var field=fields.next();if(field.getKey().endsWith(".ogg"))continue;JsonNode value=field.getValue();String hash=value.path("hash").asText();
            if(!hash.matches("[0-9a-f]{40}")||!value.path("size").canConvertToLong()||value.path("size").asLong()<0)throw new IOException("asset参照が不正です");
            Path path=assetsPath.resolve("objects").resolve(hash.substring(0,2)).resolve(hash);if(Files.size(result.original(path))!=value.path("size").asLong())throw new IOException("asset sizeが一致しません");inputs.add(path);
        }
        long bytes=0;for(Path input:inputs)bytes=Math.addExact(bytes,Files.size(result.original(input)));if(Files.getFileStore(staged.getParent()).getUsableSpace()<bytes)throw new IOException("入力snapshotの空き容量が不足しています");
        if(Files.exists(staged,LinkOption.NOFOLLOW_LINKS))throw new IOException("job snapshotが既に存在します");Files.createDirectory(staged);for(Path directory:directories)Files.createDirectories(staged.resolve(directory));var manifest=HelperProtocol.JSON.createArrayNode();
        for(Path input:inputs) {
            checkInterrupted();Path original=result.original(input),target=staged.resolve(input);Files.createDirectories(target.getParent());copy(original,target,references.get(input));
            ObjectNode entry=manifest.addObject();entry.put("path",input.toString().replace('\\','/'));entry.put("bytes",Files.size(target));entry.put("sha256",hash(target));
        }
        ObjectNode record=HelperProtocol.JSON.createObjectNode();record.put("schemaVersion",1);record.set("files",manifest);write(staged.getParent().resolve("snapshot.json"),record);return result;
    }
    public Path cache(){return staged;}
    /** 転送開始前にguest取得処理を登録する。未設定なら既存host stagingを直接読む。 */
    public void fetcher(Fetcher value){fetcher=value;}
    Path output(String path) throws IOException {Path relative=relative(path);if(!relative.startsWith(run)||!relative.toString().endsWith(".png"))throw new IOException("PNG出力がjob外です");return relative;}
    void publish(Path relative) throws IOException {
        if(fetcher!=null)fetcher.fetch(relative,staged.resolve(relative));
        if(!relative.startsWith(run))throw new IOException("PNG出力がjob外です");Path from=staged.resolve(relative);if(!from.toRealPath().equals(from)||!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)||Files.size(from)>64L*1024*1024)throw new IOException("PNG出力を安全に読めません");
        Path to=source.resolve(relative);if(!to.getParent().toRealPath().equals(to.getParent())||Files.isSymbolicLink(to))throw new IOException("元jobの出力先が変化しました");copy(from,to,null);
    }
    private Path relative(String value) throws IOException {
        if(!value.startsWith(CACHE))throw new IOException("workerの参照がcache外です");Path path;
        try {path=Path.of(value.substring(CACHE.length()));}catch(InvalidPathException failure){throw new IOException("workerのpathが不正です",failure);}
        if(path.isAbsolute()||path.toString().isEmpty()||!path.normalize().equals(path)||path.startsWith(".."))throw new IOException("workerの相対pathが不正です");return path;
    }
    private Path original(Path relative) throws IOException {
        Path file=source.resolve(relative);if(!file.startsWith(source)||!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("入力snapshotにlink/非fileがあります");return file;
    }
    private void directory(Set<Path> inputs,Path relative) throws IOException {
        Path directory=source.resolve(relative);if(!directory.toRealPath().equals(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("native directoryが不正です");
        try(var paths=Files.walk(directory)) {for(Path path:paths.toList()){if(Files.isSymbolicLink(path))throw new IOException("native入力にlinkがあります");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))inputs.add(source.relativize(path));}}
    }
    private static String argument(List<String> arguments,String name) throws IOException {int index=arguments.indexOf(name);if(index<0||index+1>=arguments.size())throw new IOException("worker引数がありません: "+name);return arguments.get(index+1);}
    private static void checkInterrupted() throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("snapshot同期が中断されました");}
    static void copy(Path from,Path to,String expectedHash) throws IOException {
        Path temporary=Files.createTempFile(to.getParent(),"copy-",".part");
        try {
            try(InputStream input=Files.newInputStream(from);OutputStream output=Files.newOutputStream(temporary)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1){checkInterrupted();output.write(buffer,0,count);}}
            String copiedHash=hash(temporary);if(expectedHash!=null&&!expectedHash.equals(copiedHash))throw new IOException("snapshot参照が初期照合後に変化しました");
            if(Files.size(from)!=Files.size(temporary)||!hash(from).equals(copiedHash))throw new IOException("snapshot同期中に入力が変化しました");checkInterrupted();Files.move(temporary,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally {Files.deleteIfExists(temporary);}
    }
    private static void write(Path file,JsonNode value) throws IOException {Path temporary=Files.createTempFile(file.getParent(),"state-",".json");try{Files.write(temporary,HelperProtocol.JSON.writeValueAsBytes(value));Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static String hash(Path path) throws IOException {
        try {var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1){checkInterrupted();digest.update(bytes,0,count);}}return HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException failure){throw new AssertionError(failure);}
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException failure){throw new AssertionError(failure);}}
}
