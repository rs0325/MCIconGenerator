package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
                boolean legacy=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().startsWith("legacy-");
                if(legacy&&RenderAgent.config.get("profile").getAsString().equals("legacy-1.14")&&RenderAgent.symbols.field("net.minecraft.client.Minecraft","overlay",minecraft)!=null)return;
                if(!legacy) {
                    if(RenderAgent.symbols.hasMethod("net.minecraft.client.Minecraft","isGameLoadFinished",0)||RenderAgent.config.get("version").getAsString().startsWith("26.")) {
                        if(!Boolean.TRUE.equals(RenderAgent.symbols.call("net.minecraft.client.Minecraft","isGameLoadFinished",minecraft)))return;
                    } else if(RenderAgent.symbols.call("net.minecraft.client.Minecraft","getOverlay",minecraft)!=null)return;
                }
                boolean latest=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().matches("gui-26\\.[23]");
                boolean gpuImmediate=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("gui-1.21.5");
                boolean classic=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("gui-classic");
                boolean fixed=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("gui-fixed");
                boolean deferred=RenderAgent.config.has("profile")&&RenderAgent.config.get("profile").getAsString().equals("gui-deferred");
                renderer=deferred?new DeferredRenderer(RenderAgent.symbols,minecraft):fixed?new FixedRenderer(RenderAgent.symbols,minecraft):classic?new ClassicRenderer(RenderAgent.symbols,minecraft):gpuImmediate?new GpuImmediateRenderer(RenderAgent.symbols,minecraft):legacy?new LegacyRenderer(RenderAgent.symbols,minecraft):latest?new LatestRenderer(RenderAgent.symbols,minecraft):new ModernRenderer(RenderAgent.symbols,minecraft);
                JsonObject hello=new JsonObject(); hello.addProperty("type","ready"); hello.addProperty("protocolVersion",1);
                hello.addProperty("renderer",renderer.environment()); hello.addProperty("animationFrameZero",RenderAgent.canonical.animationHook); hello.addProperty("contextProfile","fixed-v1"); emit(hello); ready=true;
            }
            int requestsInFrame=0;
            while(true) {
            JsonObject request=requests.poll(250,TimeUnit.MILLISECONDS);
            if(request==null)return;
            JsonObject result=new JsonObject();
            if(request.has("requestId")) result.add("requestId",request.get("requestId"));
            try {
                String command=request.get("command").getAsString();
                if(command.equals("catalog")) result.add("entries",renderer.catalog());
                else if(command.equals("render")) {
                    result.add("result",renderTransparent(request));
                }
                else if(command.equals("shutdown")) { result.addProperty("type","result");emit(result); RenderAgent.symbols.call("net.minecraft.client.Minecraft","stop",minecraft); return; }
                else throw new IllegalArgumentException("Unknown command: "+command);
                result.addProperty("type","result");
            } catch(Exception e) {
                e.printStackTrace(System.err); result.addProperty("type","error"); result.addProperty("code",e instanceof ResourceGuard.MissingResourceException?((ResourceGuard.MissingResourceException)e).code:"RENDER_FAILED");
                result.addProperty("message",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));
            }
            emit(result);
            // Let the native frame present and reclaim deferred GPU resources.
            if(++requestsInFrame>=16)return;
            }
        } catch(Throwable e) { e.printStackTrace(System.err); System.exit(1); }
    }
    private static JsonObject renderTransparent(JsonObject request) throws Exception {
        Path output=Paths.get(request.get("path").getAsString());
        ResourceGuard.begin();
        try {
            CanonicalContext.beginProbe(0f);renderer.render(request);
            ResourceGuard.check();
            java.awt.image.BufferedImage black=CanonicalContext.probeImage();
            CanonicalContext.end();CanonicalContext.beginProbe(1f);renderer.render(request);
            ResourceGuard.check();
            java.awt.image.BufferedImage white=CanonicalContext.probeImage();
            CanonicalContext.end();
            int visible=PngOutput.compose(black,white,output);
            JsonObject result=new JsonObject();result.addProperty("path",output.toString());result.addProperty("visiblePixels",visible);return result;
        } finally {CanonicalContext.end();ResourceGuard.end();}
    }
    private static void emit(JsonObject result) { RenderAgent.protocol.println(gson.toJson(result)); RenderAgent.protocol.flush(); }
}
