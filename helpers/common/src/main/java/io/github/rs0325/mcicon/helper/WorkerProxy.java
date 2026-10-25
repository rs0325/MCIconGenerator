package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** OS helper内部のstdio転送。元line bytesを保ち、PNG公開を応答より先に行う。 */
public final class WorkerProxy {
    record Pending(String id,String path,Path relative) {}
    private WorkerProxy() {}
    /** OS側の絶対回収予算内で停止し、終了不明では例外にする。 */
    @FunctionalInterface public interface Stopper {void stop(Process process) throws IOException;}
    /** helper所有のSystem.in/outと子stdioを転送する。失敗時の有限停止をstopperへ依頼する。 */
    public static int transfer(Process process,Snapshot snapshot,Stopper stopper) throws IOException {
        Queue<Pending> pending=new ConcurrentLinkedQueue<>();AtomicReference<IOException> failure=new AtomicReference<>();
        Thread.ofPlatform().daemon().name("mcicon-worker-input").start(()-> {
            OutputStream output=process.getOutputStream();
            try(BufferedInputStream input=new BufferedInputStream(System.in)) {
                Set<String> ids=new HashSet<>();byte[] line;
                while((line=line(input,1024*1024))!=null) {
                    JsonNode request=HelperProtocol.JSON.readTree(line);if(request==null||!request.isObject())throw new IOException("worker要求はobjectが必要です");String id=request.path("requestId").asText(),command=request.path("command").asText();
                    if(id.isEmpty()||!ids.add(id)||!Set.of("render","catalog","shutdown").contains(command))throw new IOException("worker要求のID/commandが不正です");
                    String path=command.equals("render")?request.path("path").asText():null;Path relative=path==null?null:snapshot.output(path);pending.add(new Pending(id,path,relative));output.write(line);output.flush();
                }
            }catch(IOException error){failure.set(error);try{stopper.stop(process);}catch(IOException cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}}
            finally {try{output.close();}catch(IOException error){failure.compareAndSet(null,error);try{stopper.stop(process);}catch(IOException cleanup){IOException original=failure.get();if(cleanup!=original)original.addSuppressed(cleanup);}}}
        });
        IOException error=null;int exit=1;
        try {
            relay(new BufferedInputStream(process.getInputStream()),System.out,pending,snapshot);
            if(failure.get()!=null)throw failure.get();exit=process.waitFor();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();error=new InterruptedIOException("worker輸送が中断されました");}
        catch(IOException failed){error=failed;}
        finally {if(process.isAlive())try{stopper.stop(process);}catch(IOException cleanup){if(error==null)error=cleanup;else error.addSuppressed(cleanup);}}
        IOException inputFailure=failure.get();if(inputFailure!=null&&inputFailure!=error){if(error!=null)inputFailure.addSuppressed(error);error=inputFailure;}
        if(error!=null)throw error;return exit;
    }
    static void relay(InputStream input,OutputStream output,Queue<Pending> pending,Snapshot snapshot) throws IOException {
        byte[] line;boolean ready=false;
        while((line=line(input,16*1024*1024))!=null) {
            JsonNode response=HelperProtocol.JSON.readTree(line);if(response==null||!response.isObject())throw new IOException("worker応答はobjectが必要です");String type=response.path("type").asText();
            if(type.equals("ready")) {if(ready||response.path("protocolVersion").asInt()!=1)throw new IOException("worker readyが一致しません");ready=true;}
            else {
                if(!ready)throw new IOException("worker readyより先に応答しました");
                Pending request=pending.poll();if(request==null||!request.id().equals(response.path("requestId").asText())||!Set.of("result","error").contains(type))throw new IOException("worker応答の順序/IDが一致しません");
                if(type.equals("result")&&request.relative()!=null) {
                    if(!request.path().equals(response.path("result").path("path").asText()))throw new IOException("worker応答のPNG pathが一致しません");snapshot.publish(request.relative());
                }
            }
            output.write(line);output.flush();
        }
        if(!pending.isEmpty())throw new IOException("worker応答が未完了です");
    }
    private static byte[] line(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1) {if(line.size()>=limit)throw new IOException("worker protocol lineが上限を超えています");line.write(value);if(value=='\n')return line.toByteArray();}
        if(line.size()!=0)throw new IOException("worker protocol lineが途中で終了しました");return null;
    }
}
