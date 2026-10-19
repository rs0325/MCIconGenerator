package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 実CLI parserの判断を受け取る。更新取得・trial・rollbackは実行しない。 */
final class StartupControl implements AutoCloseable {
    static final String PROPERTY="mcicon.bootstrap.control";
    private final ServerSocket listener;
    private final ObjectNode context;
    private final List<String> arguments;
    StartupControl(JsonNode slot,String invocation,List<String> arguments) throws IOException {
        this.arguments=List.copyOf(arguments);listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        context=Seed.JSON.createObjectNode().put("protocol",2).put("port",listener.getLocalPort()).put("nonce",UUID.randomUUID().toString()).put("invocation",invocation);context.set("slot",slot);
    }
    String property() throws IOException {return "-D"+PROPERTY+"="+Base64.getUrlEncoder().withoutPadding().encodeToString(Seed.JSON.writeValueAsBytes(context));}
    JsonNode request(Process child) throws IOException {return request(child,Duration.ofSeconds(30));}
    JsonNode request(Process child,Duration timeout) throws IOException {
        long deadline=System.nanoTime()+timeout.toNanos();Socket accepted=null;
        while(accepted==null) {
            remaining(deadline);listener.setSoTimeout((int)Math.min(100,remaining(deadline)));
            try{accepted=listener.accept();}catch(SocketTimeoutException pending){if(!child.isAlive()) {if(child.exitValue()==2)return null;throw Seed.invalid("REQUEST前にCLIが終了しました");}}
        }
        try(Socket socket=accepted) {
            JsonNode request=read(socket,deadline);validate(request,child);
            write(socket,Seed.JSON.createObjectNode().put("protocol",2).put("type","RUN"),deadline);return request;
        }
    }
    void validate(JsonNode value,Process child) throws IOException {
        Seed.fields(value,"protocol","type","nonce","invocation","slot","pid","startedAt","args","command","managed","offline","help","preparationMillis","startupMillis","itemMillis");Seed.constant(value,"protocol",2);Seed.exact(value,"type","REQUEST");
        if(!value.path("nonce").equals(context.get("nonce"))||!value.path("invocation").equals(context.get("invocation"))||!value.path("slot").equals(context.get("slot"))||!value.path("args").equals(Seed.JSON.valueToTree(arguments)))throw Seed.invalid("CLI REQUESTの起動参照が一致しません");
        if(Seed.positive(value,"pid")!=child.pid()||!child.info().startInstant().orElseThrow(()->Seed.invalid("CLI開始識別が読めません")).toString().equals(Seed.text(value,"startedAt")))throw Seed.invalid("CLI REQUESTのprocessが一致しません");
        Seed.choice(value,"command","help","version","root","cache","versions","extract","doctor","list","render");for(String name:List.of("managed","offline","help"))if(!value.path(name).isBoolean())throw Seed.invalid("CLI REQUESTのbooleanが不正です");
        for(String name:List.of("preparationMillis","startupMillis","itemMillis"))Seed.positive(value,name);
    }
    static long remaining(long deadline) throws IOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("bootstrap制御を中断しました");long nanos=deadline-System.nanoTime();if(nanos<=0)throw new SocketTimeoutException("bootstrap制御の絶対期限を超えました");return Math.max(1,Math.min(Integer.MAX_VALUE,(nanos+999999)/1000000));}
    static JsonNode read(Socket socket,long deadline) throws IOException {
        byte[] header=receive(socket,4,deadline);int size=ByteBuffer.wrap(header).getInt();if(size<1||size>65536)throw Seed.invalid("bootstrap制御messageが上限外です");return Seed.json(receive(socket,size,deadline));
    }
    private static byte[] receive(Socket socket,int size,long deadline) throws IOException {
        byte[] bytes=new byte[size];int offset=0;while(offset<size){socket.setSoTimeout((int)remaining(deadline));int count=socket.getInputStream().read(bytes,offset,size-offset);if(count<0)throw new EOFException("bootstrap制御が閉じました");offset+=count;}remaining(deadline);return bytes;
    }
    static void write(Socket socket,JsonNode value) throws IOException {write(socket,value,System.nanoTime()+TimeUnit.SECONDS.toNanos(30));}
    static void write(Socket socket,JsonNode value,long deadline) throws IOException {
        byte[] bytes=Seed.JSON.writeValueAsBytes(value);if(bytes.length>65536)throw Seed.invalid("bootstrap制御messageが上限外です");remaining(deadline);
        Thread guard=Thread.ofVirtual().start(()->expire(socket,deadline));IOException original=null;
        try{socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(bytes.length).array());socket.getOutputStream().write(bytes);socket.getOutputStream().flush();remaining(deadline);}
        catch(IOException failure){original=failure;throw failure;}
        finally{guard.interrupt();IOException cleanup=null;try{guard.join(1000);if(guard.isAlive())cleanup=new IOException("bootstrap送信監督を回収できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();cleanup=new InterruptedIOException("bootstrap送信監督回収を中断しました");}if(cleanup!=null){if(original!=null)original.addSuppressed(cleanup);else throw cleanup;}}
    }

    static void expire(Socket socket,long deadline) {
        try{if(Thread.currentThread().isInterrupted())return;long nanos=deadline-System.nanoTime();if(nanos>0)TimeUnit.NANOSECONDS.sleep(nanos);socket.close();}
        catch(InterruptedException stopped){}catch(IOException closed){}
    }
    @Override public void close() throws IOException {listener.close();}
}
