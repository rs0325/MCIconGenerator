package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class Bridge {
    private static final BlockingQueue<JsonObject> requests=new LinkedBlockingQueue<JsonObject>(128);
    private static final Gson gson=new Gson();
    private static boolean ready=false;
    private static WorkerRenderer renderer;
    public static void start() {
        Thread reader=new Thread(new Runnable() { public void run() {
            try {
                BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
                String line;
                while((line=input.readLine())!=null) {
                    if(line.length()>1024*1024) throw new IOException("Protocol request too large");
                    requests.put(new JsonParser().parse(line).getAsJsonObject());
                }
                JsonObject shutdown=new JsonObject();shutdown.addProperty("requestId","stdin-closed");shutdown.addProperty("command","shutdown");
                requests.put(shutdown);
            } catch(Exception e) { e.printStackTrace(System.err); }
        }},"mcicon-protocol-reader");
        reader.setDaemon(true); reader.start();
    }
    public static void frame() {
        try {
            Object minecraft=RenderAgent.symbols.call("net.minecraft.client.Minecraft","getInstance",null);
            if(!ready) {
                boolean legacy=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("legacy-1.13");
                if(!legacy&&!Boolean.TRUE.equals(RenderAgent.symbols.call("net.minecraft.client.Minecraft","isGameLoadFinished",minecraft))) return;
                boolean latest=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("gui-26.3");
                renderer=legacy?new LegacyRenderer(RenderAgent.symbols,minecraft):latest?new LatestRenderer(RenderAgent.symbols,minecraft):new ModernRenderer(RenderAgent.symbols,minecraft);
                JsonObject hello=new JsonObject(); hello.addProperty("type","ready"); hello.addProperty("protocolVersion",1);
                hello.addProperty("renderer",renderer.environment()); emit(hello); ready=true;
            }
            JsonObject request=requests.poll();
            if(request==null)return;
            JsonObject result=new JsonObject();
            if(request.has("requestId")) result.add("requestId",request.get("requestId"));
            try {
                String command=request.get("command").getAsString();
                if(command.equals("catalog")) result.add("entries",renderer.catalog());
                else if(command.equals("render")) result.add("result",renderer.render(request));
                else if(command.equals("shutdown")) { result.addProperty("type","result");emit(result); RenderAgent.symbols.call("net.minecraft.client.Minecraft","stop",minecraft); return; }
                else throw new IllegalArgumentException("Unknown command: "+command);
                result.addProperty("type","result");
            } catch(Exception e) {
                e.printStackTrace(System.err); result.addProperty("type","error"); result.addProperty("code","RENDER_FAILED");
                result.addProperty("message",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));
            }
            emit(result);
        } catch(Throwable e) { e.printStackTrace(System.err); System.exit(1); }
    }
    private static void emit(JsonObject result) { RenderAgent.protocol.println(gson.toJson(result)); RenderAgent.protocol.flush(); }
}
