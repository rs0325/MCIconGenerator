package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.*;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** QA-only normal-screen fixture. Never uses WorkerRenderer.render or PNG alpha reconstruction. */
public final class GuiReferenceAgent {
    private static final String SELF="io/github/rs0325/mcicon/qa/GuiReferenceAgent";
    private static final BlockingQueue<JsonObject> requests=new LinkedBlockingQueue<JsonObject>(128);
    private static Symbols symbols;
    private static boolean legacy,earlyLegacy,fixed,classic,immediate,deferred,extracted,latest,pearl,ready,drawn;
    private static volatile boolean capturing;
    private static volatile JsonObject pending;
    private static JsonObject fixture;
    private static Object minecraft;
    private static WorkerRenderer catalog;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        JsonObject config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),"UTF-8")).getAsJsonObject();
        try(InputStream input=GuiReferenceAgent.class.getResourceAsStream("/mcicon-gui-reference.json")) {
            fixture=new JsonParser().parse(new InputStreamReader(input,"UTF-8")).getAsJsonObject();
        }
        symbols=new Symbols(config.has("mappings")?Paths.get(config.get("mappings").getAsString()):null);
        String profile=config.get("profile").getAsString();
        legacy=profile.equals("legacy-1.13")||profile.equals("legacy-1.14");
        earlyLegacy=profile.equals("legacy-1.13");
        fixed=profile.equals("gui-fixed");classic=profile.equals("gui-classic");pearl=profile.equals("gui-26.3");
        immediate=profile.equals("gui-1.21.5");deferred=profile.equals("gui-deferred");
        extracted=config.get("version").getAsString().startsWith("26.");latest=profile.equals("gui-26.2")||pearl;
        if(!legacy&&!fixed&&!classic&&!immediate&&!deferred&&!latest&&!profile.equals("gui-1.21.4"))throw new IllegalArgumentException("対応する参照fixtureではありません: "+profile);
        Files.write(Paths.get(argument).getParent().resolve("game/options.txt"),"soundCategory_master:0.0\nguiScale:4\nmaxFps:30\nfullscreen:false\nonboardAccessibility:false\n".getBytes("UTF-8"));
        if(fixture.has("resourcePack"))ResourcePackFaultAgent.installPack(Paths.get(argument).getParent().resolve("game"));
        final String title=legacy?"net.minecraft.client.gui.screen.TitleScreen":"net.minecraft.client.gui.screens.TitleScreen";
        final String screen=legacy?"net.minecraft.client.gui.screen.Screen":"net.minecraft.client.gui.screens.Screen";
        final boolean flatScreen=legacy||fixed&&symbols.hasMethod(screen,"render",3);
        final String screenMethod=symbols.methodName(extracted?title:screen,extracted?"extractRenderState":"render",flatScreen?3:4);
        final String graphics=fixed||classic?"com.mojang.blaze3d.vertex.PoseStack":extracted?"net.minecraft.client.gui.GuiGraphicsExtractor":"net.minecraft.client.gui.GuiGraphics";
        final String screenDesc=flatScreen?"(IIF)V":"(L"+symbols.actualClass(graphics).replace('.','/')+";IIF)V";
        final String renderer=legacy?"net.minecraft.client.render.GameRenderer":"net.minecraft.client.renderer.GameRenderer";
        final int renderArguments=pearl?0:symbols.hasMethod(renderer,"render",3)?3:2;
        final String renderMethod=symbols.methodName(renderer,"render",renderArguments);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                boolean bridge=name.equals("io/github/rs0325/mcicon/worker/Bridge");
                boolean main=name.equals(symbols.actualClass("net.minecraft.client.main.Main").replace('.','/'));
                boolean screenClass=name.equals(symbols.actualClass(title).replace('.','/'));
                boolean renderClass=name.equals(symbols.actualClass(renderer).replace('.','/'));
                if(!bridge&&!main&&!screenClass&&!renderClass)return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                        if(bridge&&(name.equals("start")||name.equals("frame"))&&desc.equals("()V")) {
                            target.visitCode();target.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,name,"()V",false);target.visitInsn(Opcodes.RETURN);target.visitMaxs(0,0);target.visitEnd();return null;
                        }
                        if(screenClass&&name.equals(screenMethod)&&desc.equals(screenDesc)) {
                            target.visitCode();if(flatScreen)target.visitInsn(Opcodes.ACONST_NULL);else target.visitVarInsn(Opcodes.ALOAD,1);
                            target.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"screen","(Ljava/lang/Object;)V",false);target.visitInsn(Opcodes.RETURN);target.visitMaxs(1,flatScreen?4:5);target.visitEnd();
                            System.err.println("[mcicon QA] native screen fixture installed: "+name+desc);return null;
                        }
                        if(main&&name.equals("main")&&desc.equals("([Ljava/lang/String;)V"))return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode(){super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"arguments","([Ljava/lang/String;)V",false);}
                        };
                        if(renderClass&&name.equals(renderMethod)&&Type.getReturnType(desc).getSort()==Type.VOID&&Type.getArgumentTypes(desc).length==renderArguments)return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitInsn(int opcode){if(opcode==Opcodes.RETURN)super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"capture","()V",false);super.visitInsn(opcode);}
                        };
                        return target;
                    }
                },0);
                return writer.toByteArray();
            }
        });
        RenderAgent.premain(argument,instrumentation);
    }
    private static boolean animationHook() throws Exception {
        Object hooks=RenderAgent.class.getField("canonical").get(null);
        java.lang.reflect.Field field=hooks.getClass().getDeclaredField("animationHook");field.setAccessible(true);return field.getBoolean(hooks);
    }
    public static void arguments(String[] args) {
        for(int i=0;i+1<args.length;i++)if(args[i].equals("--width"))args[i+1]="1280";else if(args[i].equals("--height"))args[i+1]="960";
    }
    public static void start() {
        Thread reader=new Thread(new Runnable(){public void run(){try {
            BufferedReader input=new BufferedReader(new InputStreamReader(System.in,"UTF-8"));String line;
            while((line=input.readLine())!=null){if(line.length()>1024*1024)throw new IOException("Oversized request");requests.put(new JsonParser().parse(line).getAsJsonObject());}
            JsonObject stop=new JsonObject();stop.addProperty("command","shutdown");requests.put(stop);
        }catch(Exception e){e.printStackTrace(System.err);System.exit(1);}}},"mcicon-qa-reader");reader.setDaemon(true);reader.start();
    }
    public static void frame() {
        try {
            if(!ready) {
                minecraft=symbols.call("net.minecraft.client.Minecraft","getInstance",null);
                if(legacy&&!earlyLegacy&&symbols.field("net.minecraft.client.Minecraft","overlay",minecraft)!=null)return;
                if((extracted||symbols.hasMethod("net.minecraft.client.Minecraft","isGameLoadFinished",0))&&!Boolean.TRUE.equals(symbols.call("net.minecraft.client.Minecraft","isGameLoadFinished",minecraft)))return;
                if(!latest&&(extracted||symbols.hasMethod("net.minecraft.client.Minecraft","getOverlay",0))&&symbols.call("net.minecraft.client.Minecraft","getOverlay",minecraft)!=null)return;
                if(latest&&symbols.call("net.minecraft.client.gui.Gui","overlay",symbols.field("net.minecraft.client.Minecraft","gui",minecraft))!=null)return;
                // Share only registry/bootstrap initialization, never export target/view/render methods.
                catalog=legacy?new LegacyRenderer(symbols,minecraft):fixed?new FixedRenderer(symbols,minecraft):classic?new ClassicRenderer(symbols,minecraft):latest?new LatestRenderer(symbols,minecraft):deferred?new DeferredRenderer(symbols,minecraft):immediate?new GpuImmediateRenderer(symbols,minecraft):new ModernRenderer(symbols,minecraft);
                if(fixture.has("resourcePack"))ResourcePackFaultAgent.reportPack();
                JsonObject hello=new JsonObject();hello.addProperty("type","ready");hello.addProperty("protocolVersion",1);hello.addProperty("renderer","QA native screen / "+catalog.environment());hello.addProperty("animationFrameZero",animationHook());hello.addProperty("contextProfile","canonical-v1");emit(hello);ready=true;
            }
            if(pending!=null)return;
            JsonObject request=requests.poll(20,TimeUnit.MILLISECONDS);if(request==null)return;
            String command=request.get("command").getAsString();
            if(command.equals("render")){if(request.get("size").getAsInt()!=64)throw new IllegalArgumentException("Native reference scale is 64px");pending=request;drawn=false;capturing=false;return;}
            JsonObject response=new JsonObject();if(request.has("requestId"))response.add("requestId",request.get("requestId"));response.addProperty("type","result");
            if(command.equals("catalog"))response.add("entries",catalog.catalog());
            else if(command.equals("shutdown")){emit(response);symbols.call("net.minecraft.client.Minecraft","stop",minecraft);return;}
            else throw new IllegalArgumentException("Unknown QA command");emit(response);
        }catch(Throwable e){e.printStackTrace(System.err);System.exit(1);}
    }
    public static void screen(Object graphics) {
        if(pending==null||capturing)return;
        try {
            CanonicalContext.begin();
            int color=0xff000000|fixture.get("background").getAsInt();
            Object stack=stack(pending.get("id").getAsString());
            int x=fixture.get("x").getAsInt(),y=fixture.get("y").getAsInt();
            if(legacy||fixed||classic) {
                String component=legacy?"net.minecraft.client.gui.GuiElement":"net.minecraft.client.gui.GuiComponent";
                if(classic)symbols.call("com.mojang.blaze3d.systems.RenderSystem","setShaderColor",null,1f,1f,1f,1f);
                if(graphics==null)symbols.call(component,"fill",null,0,0,320,240,color);
                else symbols.call(component,"fill",null,graphics,0,0,320,240,color);
                String lighting=earlyLegacy?"net.minecraft.client.render.platform.Lighting":"com.mojang.blaze3d.platform.Lighting";
                String gl=earlyLegacy?"net.minecraft.client.render.platform.GlStateManager":symbols.hasMethod("com.mojang.blaze3d.platform.GlStateManager","enableDepthTest",0)?"com.mojang.blaze3d.platform.GlStateManager":"com.mojang.blaze3d.systems.RenderSystem";
                symbols.call(lighting,symbols.hasMethod(lighting,"turnOnGui",0)?"turnOnGui":"setupFor3DItems",null);
                // InventoryMenuScreen enables depth before each slot. The world GUI
                // also inherits culling from GameRenderer.renderWorld.
                symbols.call(gl,"enableDepthTest",null);
                symbols.call(gl,"enableCull",null);
                Object renderer=symbols.call("net.minecraft.client.Minecraft","getItemRenderer",minecraft);
                String itemRenderer=legacy?"net.minecraft.client.render.entity.ItemRenderer":"net.minecraft.client.renderer.entity.ItemRenderer";
                if(!legacy&&symbols.hasMethod(itemRenderer,"renderGuiItem","com.mojang.blaze3d.vertex.PoseStack","net.minecraft.world.item.ItemStack","int","int"))symbols.call(itemRenderer,"renderGuiItem",renderer,graphics,stack,x,y);
                else symbols.call(itemRenderer,"renderGuiItem",renderer,stack,x,y);
                if(symbols.hasMethod(lighting,"turnOff",0))symbols.call(lighting,"turnOff",null);
            } else {
                String owner=extracted?"net.minecraft.client.gui.GuiGraphicsExtractor":"net.minecraft.client.gui.GuiGraphics";
                symbols.call(owner,"fill",graphics,0,0,320,240,color);
                symbols.call(owner,extracted?"item":"renderItem",graphics,stack,x,y);
                if(!deferred&&!latest)symbols.call(owner,"flush",graphics);
            }
            if(fixture.has("framebufferProbe"))recordFramebuffer();
            drawn=true;
        }catch(Throwable e){fail(e);}
    }
    private static void recordFramebuffer() throws Exception {
        Path path=Paths.get(fixture.get("captureFolder").getAsString()).resolve("framebuffer.json");
        if(Files.exists(path))return;
        JsonObject state=framebufferState(symbols);
        Files.createDirectories(path.getParent());Files.write(path,new Gson().toJson(state).getBytes("UTF-8"));
    }
    /** 色形式を読み取り、触れたtexture bindingを復帰するQA観測。 */
    public static JsonObject framebufferState(Symbols symbols) throws Exception {
        String gl="org.lwjgl.opengl.GL11",gl30="org.lwjgl.opengl.GL30";
        JsonObject state=new JsonObject();
        state.addProperty("framebuffer",((Number)symbols.call(gl,"glGetInteger",null,36006)).intValue());
        state.addProperty("srgbEnabled",(Boolean)symbols.call(gl,"glIsEnabled",null,36281));
        String[] channels={"red","green","blue","alpha"};
        for(int i=0;i<channels.length;i++) {
            state.addProperty(channels[i]+"Bits",((Number)symbols.call(gl30,"glGetFramebufferAttachmentParameteri",null,36160,36064,0x8212+i)).intValue());
        }
        state.addProperty("colorEncoding",((Number)symbols.call(gl30,"glGetFramebufferAttachmentParameteri",null,36160,36064,33296)).intValue());
        int objectType=((Number)symbols.call(gl30,"glGetFramebufferAttachmentParameteri",null,36160,36064,36048)).intValue();
        state.addProperty("attachmentObjectType",objectType);
        if(objectType==5890) {
            int texture=((Number)symbols.call(gl30,"glGetFramebufferAttachmentParameteri",null,36160,36064,36049)).intValue();
            int binding=((Number)symbols.call(gl,"glGetInteger",null,32873)).intValue();
            try {
                symbols.call(gl,"glBindTexture",null,3553,texture);
                state.addProperty("internalFormat",((Number)symbols.call(gl,"glGetTexLevelParameteri",null,3553,0,4099)).intValue());
            } finally {symbols.call(gl,"glBindTexture",null,3553,binding);}
            state.addProperty("textureBindingRestored",((Number)symbols.call(gl,"glGetInteger",null,32873)).intValue()==binding);
        }
        state.addProperty("glError",((Number)symbols.call(gl,"glGetError",null)).intValue());
        return state;
    }
    private static Object stack(String id) throws Exception {
        if(legacy) {
            boolean early=symbols.hasField("net.minecraft.item.Item","REGISTRY");
            String registry=early?"net.minecraft.util.registry.IdRegistry":"net.minecraft.util.registry.Registry";
            Object items=early?symbols.field("net.minecraft.item.Item","REGISTRY",null):symbols.field(registry,"ITEM",null);
            for(Object item:(Iterable<?>)items)if(id.equals(String.valueOf(symbols.call(registry,"getKey",items,item))))return symbols.call("net.minecraft.item.Item","defaultItem",item);
            throw new IllegalArgumentException("Unknown item "+id);
        }
        Object items=symbols.field(extracted||symbols.hasField("net.minecraft.core.registries.BuiltInRegistries","ITEM")?"net.minecraft.core.registries.BuiltInRegistries":"net.minecraft.core.Registry","ITEM",null);
        String identifier=extracted||symbols.hasClass("net.minecraft.resources.Identifier")?"net.minecraft.resources.Identifier":"net.minecraft.resources.ResourceLocation";
        Object location=extracted||symbols.hasMethod(identifier,"parse",1)?symbols.call(identifier,"parse",null,id):symbols.construct(identifier,id);
        Object item=symbols.call("net.minecraft.core.Registry",extracted||symbols.hasMethod("net.minecraft.core.Registry","getValue",1)?"getValue":"get",items,location);
        return symbols.call("net.minecraft.world.item.Item","getDefaultInstance",item);
    }
    public static void capture() {
        if(!drawn||capturing||pending==null)return;
        capturing=true;
        try {
            Object target=legacy?symbols.call("net.minecraft.client.Minecraft","getRenderTarget",minecraft):latest?symbols.call("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",symbols.field("net.minecraft.client.Minecraft","gameRenderer",minecraft)):symbols.call("net.minecraft.client.Minecraft","getMainRenderTarget",minecraft);
            if(immediate||deferred||latest)symbols.call("net.minecraft.client.Screenshot","takeScreenshot",null,target,new Consumer<Object>(){public void accept(Object image){save(image);}});
            else save(legacy?symbols.call("net.minecraft.client.Screenshot","take",null,1280,960,target):symbols.hasMethod("net.minecraft.client.Screenshot","takeScreenshot",3)?symbols.call("net.minecraft.client.Screenshot","takeScreenshot",null,1280,960,target):symbols.call("net.minecraft.client.Screenshot","takeScreenshot",null,target));
        }catch(Throwable e){fail(e);}finally{CanonicalContext.end();}
    }
    private static synchronized void save(Object nativeImage) {
        try {
            String owner=earlyLegacy?"net.minecraft.client.render.platform.NativeImage":"com.mojang.blaze3d.platform.NativeImage";
            Path folder=Paths.get(fixture.get("captureFolder").getAsString());Files.createDirectories(folder);
            String id=pending.get("id").getAsString().replace(':','_');Path full=folder.resolve(id+"-screen.png");
            try{symbols.call(owner,legacy?"write":"writeToFile",nativeImage,legacy&&!earlyLegacy?full.toFile():full);}finally{symbols.call(owner,"close",nativeImage);}
            BufferedImage image=ImageIO.read(full.toFile());
            if(image.getWidth()!=1280||image.getHeight()!=960)throw new IOException("Unexpected native screen dimensions "+image.getWidth()+"x"+image.getHeight());
            int x=fixture.get("x").getAsInt()*4,y=fixture.get("y").getAsInt()*4;
            BufferedImage crop=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);crop.setRGB(0,0,64,64,image.getRGB(x,y,64,64,null,0,64),0,64);
            Path path=Paths.get(pending.get("path").getAsString());ImageIO.write(crop,"PNG",path.toFile());
            JsonObject result=new JsonObject();result.add("requestId",pending.get("requestId"));result.addProperty("type","result");JsonObject payload=new JsonObject();payload.addProperty("path",path.toString());payload.addProperty("visiblePixels",4096);result.add("result",payload);emit(result);pending=null;
        }catch(Throwable e){fail(e);}
    }
    private static synchronized void fail(Throwable e) {
        e.printStackTrace(System.err);CanonicalContext.end();
        if(pending==null){System.exit(1);return;}
        JsonObject result=new JsonObject();result.add("requestId",pending.get("requestId"));result.addProperty("type","error");result.addProperty("code","QA_REFERENCE_FAILED");result.addProperty("message",e.toString());emit(result);pending=null;
    }
    private static void emit(JsonObject result){RenderAgent.protocol.println(new Gson().toJson(result));RenderAgent.protocol.flush();}
}
