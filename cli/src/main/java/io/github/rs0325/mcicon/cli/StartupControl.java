package io.github.rs0325.mcicon.cli;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** bootstrap専用の起動境界。通常CLI/APIに更新処理を持ち込まない。 */
final class StartupControl implements AutoCloseable {
    static final String PROPERTY="mcicon.bootstrap.control";
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Socket socket;
    private final boolean prepare;
    private final long deadline;
    private final CompletableFuture<Void> go=new CompletableFuture<>();
    private volatile Runnable cancel=()->{};
    private volatile boolean ready,failed;
    private Thread reader;
    private StartupControl(Socket socket,boolean prepare,long deadline){this.socket=socket;this.prepare=prepare;this.deadline=deadline;}
    static StartupControl open(ObjectNode request,String version,Path cliJar) throws IOException {
        String encoded=System.getProperty(PROPERTY);if(encoded==null)return null;if(encoded.length()>16384)throw new IOException("bootstrap制御contextが上限外です");
        JsonNode context;try{context=JSON.readTree(Base64.getUrlDecoder().decode(encoded));}catch(RuntimeException failure){throw new IOException("bootstrap制御contextが不正です",failure);}
        fields(context,"protocol","port","nonce","invocation","slot");protocol(context);if(!context.path("port").isIntegralNumber()||!context.path("port").canConvertToInt()||context.path("port").intValue()<1||context.path("port").intValue()>65535)throw new IOException("bootstrap制御protocol/portが不正です");
        for(String name:List.of("nonce","invocation"))try{UUID.fromString(context.path(name).asText());}catch(IllegalArgumentException failure){throw new IOException("bootstrap制御識別が不正です",failure);}
        verifySlot(context.path("slot"),version,cliJar);
        request.put("protocol",2).put("type","REQUEST");request.set("nonce",context.get("nonce"));request.set("invocation",context.get("invocation"));request.set("slot",context.get("slot"));request.put("pid",ProcessHandle.current().pid()).put("startedAt",ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("CLI開始識別が読めません")).toString());
        Socket socket=new Socket();boolean connected=false;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),context.get("port").intValue()),(int)remaining(deadline));write(socket,request,deadline);JsonNode response=read(socket,deadline);fields(response,"protocol","type");protocol(response);
            String type=response.path("type").asText();if(!Set.of("RUN","SWITCH","PREPARE").contains(type))throw new IOException("bootstrap制御順序が不正です");
            if(!type.equals("RUN")&&(!request.path("managed").asBoolean()||request.path("offline").asBoolean()||request.path("help").asBoolean()||!Set.of("list","render").contains(request.path("command").asText())))throw new IOException("非適格commandへのtrial指示を拒否しました");
            long budget;try{for(String field:List.of("preparationMillis","startupMillis","itemMillis"))if(!request.path(field).isIntegralNumber()||!request.path(field).canConvertToLong()||request.path(field).longValue()<=0)throw new ArithmeticException();budget=Math.addExact(Math.addExact(request.path("preparationMillis").longValue(),request.path("startupMillis").longValue()),request.path("itemMillis").longValue());budget=Math.addExact(System.nanoTime(),Duration.ofMillis(budget).toNanos());}catch(ArithmeticException failure){throw new IOException("bootstrap初期化予算が不正です",failure);}
            StartupControl result=new StartupControl(socket,type.equals("PREPARE"),budget);result.switching=type.equals("SWITCH");if(result.prepare)result.monitor();else socket.close();connected=true;return result;
        }finally{if(!connected)socket.close();}
    }
    private boolean switching;
    boolean switching(){return switching;}
    static void verifySlot(JsonNode slot,String version,Path cliJar) throws IOException {
        fields(slot,"toolVersion","payloadSha256","runtimeDescriptorSha256","manifestSha256");for(String name:List.of("payloadSha256","runtimeDescriptorSha256","manifestSha256"))if(!slot.path(name).isTextual()||!slot.get(name).textValue().matches("[0-9a-f]{64}"))throw new IOException("bootstrap slot hashが不正です");
        Path jar=cliJar.toAbsolutePath().normalize();if(!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(jar))throw new IOException("CLIは固定slot JARから起動してください");
        Path lib=jar.getParent(),app=lib.getParent(),root=app.getParent();if(!lib.getFileName().toString().equals("lib")||!app.getFileName().toString().equals("app")||!root.getFileName().toString().equals(slot.path("payloadSha256").asText())||!root.getParent().getFileName().toString().equals(version)||!slot.path("toolVersion").asText().equals(version))throw new IOException("実CLIと期待slotの版/hashが一致しません");
    }
    void cancellation(Runnable action){cancel=action;if(failed)action.run();}
    void ready() {
        if(!prepare)return;
        try {
            long end=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));ready=true;write(socket,JSON.createObjectNode().put("protocol",2).put("type","READY"),end);go.get(remaining(end),TimeUnit.MILLISECONDS);
        }catch(InterruptedException failure){Thread.currentThread().interrupt();cancel.run();throw new CompletionException(failure);}
        catch(IOException|ExecutionException|TimeoutException failure){cancel.run();throw new CompletionException(failure);}
    }
    private void monitor() {
        reader=Thread.ofPlatform().name("mcicon-bootstrap-control").start(()-> {
            try {
                // 初期化中の親切断と元予算の期限を監視し、GO待機はready側でも制限する。
                JsonNode response=read(socket,deadline);fields(response,"protocol","type");protocol(response);if(!ready||!response.path("type").asText().equals("GO"))throw new IOException("bootstrap GOの順序が不正です");go.complete(null);
            }catch(Exception failure){failed=true;cancel.run();go.completeExceptionally(failure);}
        });
    }
    private static void protocol(JsonNode node) throws IOException {if(!node.path("protocol").isIntegralNumber()||!node.path("protocol").canConvertToInt()||node.path("protocol").intValue()!=2)throw new IOException("bootstrap制御protocolが不一致です");}
    private static void fields(JsonNode node,String...names) throws IOException {if(node==null||!node.isObject())throw new IOException("bootstrap制御objectが必要です");Set<String> expected=Set.of(names);if(node.size()!=expected.size())throw new IOException("bootstrap制御fieldが不正です");for(String name:names)if(!node.has(name))throw new IOException("bootstrap制御fieldが不足しています");}
    static long remaining(long deadline) throws IOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("bootstrap制御を中断しました");long value=deadline-System.nanoTime();if(value<=0)throw new SocketTimeoutException("bootstrap制御の絶対期限を超えました");return Math.max(1,Math.min(Integer.MAX_VALUE,(value+999999)/1000000));}
    static JsonNode read(Socket socket,long deadline) throws IOException {int size=ByteBuffer.wrap(receive(socket,4,deadline)).getInt();if(size<1||size>65536)throw new IOException("bootstrap制御messageが上限外です");return JSON.readTree(receive(socket,size,deadline));}
    private static byte[] receive(Socket socket,int size,long deadline) throws IOException {byte[] body=new byte[size];int offset=0;while(offset<size){socket.setSoTimeout((int)remaining(deadline));int count=socket.getInputStream().read(body,offset,size-offset);if(count<0)throw new EOFException("bootstrap制御が閉じました");offset+=count;}remaining(deadline);return body;}
    static void write(Socket socket,JsonNode value) throws IOException {write(socket,value,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));}
    static void write(Socket socket,JsonNode value,long deadline) throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>65536)throw new IOException("bootstrap制御messageが上限外です");remaining(deadline);
        Thread guard=Thread.ofVirtual().start(()->expire(socket,deadline));IOException original=null;
        try{socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(bytes.length).array());socket.getOutputStream().write(bytes);socket.getOutputStream().flush();remaining(deadline);}
        catch(IOException failure){original=failure;throw failure;}
        finally{guard.interrupt();IOException cleanup=null;try{guard.join(1000);if(guard.isAlive())cleanup=new IOException("bootstrap送信監督を回収できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();cleanup=new InterruptedIOException("bootstrap送信監督回収を中断しました");}if(cleanup!=null){if(original!=null)original.addSuppressed(cleanup);else throw cleanup;}}
    }

    static void expire(Socket socket,long deadline) {
        try{if(Thread.currentThread().isInterrupted())return;long nanos=deadline-System.nanoTime();if(nanos>0)TimeUnit.NANOSECONDS.sleep(nanos);socket.close();}
        catch(InterruptedException stopped){}catch(IOException closed){}
    }
    @Override public void close() throws IOException {
        socket.close();if(reader!=null){try{reader.join(1000);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException("bootstrap制御reader回収を中断しました");}if(reader.isAlive())throw new IOException("bootstrap制御readerを回収できません");}
    }
}
