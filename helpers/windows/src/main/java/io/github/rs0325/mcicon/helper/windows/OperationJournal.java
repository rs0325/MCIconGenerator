package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** spawn前の意図を残す。不明な生成操作は、client終了だけではsettledにしない。 */
final class OperationJournal implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private final ObjectNode identity;
    private ObjectNode state;
    private OperationJournal(Path path,FileChannel channel,FileLock lock,ObjectNode identity,ObjectNode state){this.path=path;this.channel=channel;this.lock=lock;this.identity=identity;this.state=state;}
    static OperationJournal acquire(Path temporary,JsonNode lease,Duration budget) throws IOException {
        Path mutex=temporary.resolve("windows-operation.lock");FileChannel channel=FileChannel.open(mutex,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);long end=System.nanoTime()+budget.toNanos();
        try {
            FileLock lock=null;while(lock==null){try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock==null){if(System.nanoTime()>=end)throw new IOException("Windows操作lockがbusyです");try{Thread.sleep(10);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("Windows操作lockが中断されました");}}}
            try {
                ObjectNode identity=WindowsFiles.JSON.createObjectNode();for(String key:List.of("jobId","ownerId","descriptorSha256","hostId"))identity.set(key,lease.required(key));
                Path path=temporary.resolve("windows-operation.json");ObjectNode state;
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){state=(ObjectNode)WindowsFiles.json(path);if(!state.path("identity").equals(identity)||!Set.of("unstarted","pending","settled","released").contains(state.path("state").asText()))throw new IOException("Windows operation所有記録が不正です");}
                else {state=WindowsFiles.JSON.createObjectNode();state.set("identity",identity);state.put("state","unstarted");}
                return new OperationJournal(path,channel,lock,identity,state);
            }catch(IOException|RuntimeException failure){lock.release();throw failure;}
        }catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    boolean unstarted(){return state.path("state").asText().equals("unstarted");}
    boolean released(){return state.path("state").asText().equals("released");}
    boolean pending(){return state.path("state").asText().equals("pending");}
    JsonNode state(){return state.deepCopy();}
    void active() throws IOException {if(released())throw new IOException("release済みjobは再使用できません");}
    void settledRequired() throws IOException {active();if(pending())throw new IOException("前のWindows生成操作が未確定です");}
    void begin(String operation,String boot) throws IOException {
        settledRequired();state=WindowsFiles.JSON.createObjectNode();state.set("identity",identity);state.put("state","pending").put("operation",operation).put("boot",boot).put("intent",UUID.randomUUID().toString());WindowsFiles.write(path,state);
    }
    void settled(JsonNode evidence) throws IOException {if(!pending())throw new IOException("対応する生成意図がありません");state.put("state","settled");state.set("evidence",evidence);WindowsFiles.write(path,state);}
    void recovered(JsonNode evidence) throws IOException {active();state.put("state","settled");state.set("evidence",evidence);WindowsFiles.write(path,state);}
    void release() throws IOException {if(pending())throw new IOException("未確定の操作をreleaseできません");state.put("state","released");WindowsFiles.write(path,state);}
    public void close() throws IOException {try{lock.release();}finally{channel.close();}}
}
