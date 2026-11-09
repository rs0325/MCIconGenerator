package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

/** 所有job内の診断専用ログ。回収権限や成功判定の証拠には使わない。 */
public final class RuntimePhaseLog {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path file;
    private final ObjectNode context;
    private boolean created,failed;

    /**
     * 固定ラベルと検証済みjob/attemptから、actor別のJSONLを作る。
     * directoryは検証済みjobのtmp。引数・path・例外文言をラベルに渡さない。
     * UUID以外の識別子は記録せず、ログ保存失敗は固定stderr行で知らせて元操作を維持する。
     */
    public RuntimePhaseLog(Path directory,String actor,String operation,String attempt) {
        file=directory.resolve("phase-"+UUID.randomUUID()+".jsonl");
        ProcessHandle current=ProcessHandle.current();
        context=JSON.createObjectNode().put("schema",1).put("actor",actor).put("operation",operation).put("actorPid",current.pid());
        current.info().startInstant().ifPresent(value->context.put("actorStartedEpochMillis",value.toEpochMilli()));
        String job=directory.getParent().getFileName().toString();if(uuid(job))context.put("jobId",job);if(uuid(attempt))context.put("attempt",attempt);
    }
    /** pathを含まないログ名を返す。 */
    public String fileName(){return file.getFileName().toString();}
    /** actor内のmonotonic時刻・絶対期限・予算を記録する。Long.MAX_VALUEは期限なし。 */
    public void event(String phase,long deadline,long budget){event(phase,deadline,budget,null);}
    /** native/helperの実PID・開始identityも記録する。Processやそのargvは保存しない。 */
    public synchronized void event(String phase,long deadline,long budget,Process process) {
        if(failed)return;
        try {
            long now=System.nanoTime();ObjectNode value=context.deepCopy().put("phase",phase).put("nanoTime",now).put("epochMillis",Instant.now().toEpochMilli()).put("budgetNanos",Math.max(0,budget));
            if(deadline==Long.MAX_VALUE){value.putNull("deadlineNanos");value.putNull("remainingNanos");}
            else value.put("deadlineNanos",deadline).put("remainingNanos",Math.max(0,deadline-now));
            if(process!=null){value.put("processPid",process.pid());process.info().startInstant().ifPresent(started->value.put("processStartedEpochMillis",started.toEpochMilli()));}
            Files.writeString(file,value+"\n",StandardCharsets.UTF_8,StandardOpenOption.WRITE,created?StandardOpenOption.APPEND:StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);created=true;
        }catch(IOException|SecurityException failure){failed=true;System.err.println("MCICON_PHASE_LOG_UNAVAILABLE");}
    }
    private static boolean uuid(String value){try{return value!=null&&UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException failure){return false;}}
}
