package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.CanonicalContext;
import io.github.rs0325.mcicon.worker.RenderAgent;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.nio.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;

/** 実readback完了後に例外を注入し、所有資源と描画状態の復帰を観測する。 */
public final class NativeFailureAgent {
    private static final String SELF="io/github/rs0325/mcicon/qa/NativeFailureAgent";
    private static String fault;
    private static boolean latest,reading;
    private static Thread renderThread;
    private static JsonObject pass;
    private static Object minecraft,target,color,depth,buffer,mapping,fence;
    private static int framebuffer,colorId,depthId,atlasEnds;
    private static final Set<Object> closed=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
    private static final Deque<Call> calls=new ArrayDeque<Call>();
    private static final class Call {
        final String owner,name;final Object target;final Object[] args;
        Call(String owner,String name,Object target,Object[] args){this.owner=owner;this.name=name;this.target=target;this.args=args;}
    }
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        try(java.io.InputStream in=NativeFailureAgent.class.getResourceAsStream("/mcicon-native-fault.txt")) {
            java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();int b;while((b=in.read())!=-1)data.write(b);fault=data.toString("UTF-8").trim();
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                boolean symbols=name.equals("io/github/rs0325/mcicon/worker/Symbols");
                boolean renderer=name.equals("io/github/rs0325/mcicon/worker/ModernRenderer")||name.equals("io/github/rs0325/mcicon/worker/LatestRenderer");
                boolean guard=name.equals("io/github/rs0325/mcicon/worker/ResourceGuard");
                boolean atlas=name.equals("net/minecraft/client/gui/render/GuiItemAtlas");
                if(!symbols&&!renderer&&!guard&&!atlas)return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                        final boolean call=symbols&&method.equals("call")&&desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                        final boolean construct=symbols&&method.equals("construct")&&desc.equals("(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
                        final boolean begin=renderer&&method.equals("render")&&desc.equals("(Lcom/google/gson/JsonObject;)Lcom/google/gson/JsonObject;");
                        final boolean end=guard&&method.equals("end")&&desc.equals("()V");
                        final boolean atlasEnd=atlas&&method.equals("endFrame")&&desc.equals("()V");
                        if(!call&&!construct&&!begin&&!end&&!atlasEnd)return target;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode() {
                                super.visitCode();
                                if(begin){super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"begin","(Lcom/google/gson/JsonObject;)V",false);}
                                if(end)super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"finish","()V",false);
                                if(call||construct) {
                                    super.visitVarInsn(Opcodes.ALOAD,1);
                                    if(call){super.visitVarInsn(Opcodes.ALOAD,2);super.visitVarInsn(Opcodes.ALOAD,3);super.visitVarInsn(Opcodes.ALOAD,4);}
                                    else {super.visitLdcInsn("<init>");super.visitInsn(Opcodes.ACONST_NULL);super.visitVarInsn(Opcodes.ALOAD,2);}
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"enter","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V",false);
                                }
                            }
                            public void visitInsn(int opcode) {
                                if(opcode==Opcodes.ARETURN&&(call||construct))super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"returned","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                                if(opcode==Opcodes.ARETURN&&begin)super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"finish","()V",false);
                                if(opcode==Opcodes.RETURN&&atlasEnd)super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"atlasEnd","()V",false);
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
        RenderAgent.premain(argument,instrumentation);
    }
    public static void begin(JsonObject request) {
        renderThread=Thread.currentThread();latest=RenderAgent.config.get("profile").getAsString().equals("gui-26.3");
        pass=new JsonObject();pass.addProperty("id",request.get("id").getAsString());pass.addProperty("background",CanonicalContext.backgroundColor());pass.addProperty("injected",false);
        target=color=depth=buffer=mapping=fence=null;framebuffer=colorId=depthId=atlasEnds=0;closed.clear();calls.clear();
        try {reading=true;Symbols s=RenderAgent.symbols;minecraft=s.call("net.minecraft.client.Minecraft","getInstance",null);pass.add("before",snapshot());}
        catch(Exception error){throw new IllegalStateException("QA開始状態を読めません",error);}finally{reading=false;}
    }
    public static void enter(String owner,String name,Object target,Object[] args) {if(pass!=null&&!reading&&Thread.currentThread()==renderThread)calls.push(new Call(owner,name,target,args));}
    public static Object returned(Object value) {
        if(pass==null||reading||Thread.currentThread()!=renderThread)return value;
        Call call=calls.pop();
        try {
            if(call.owner.equals("com.mojang.blaze3d.pipeline.TextureTarget")&&call.name.equals("<init>")) {
                target=value;
                if(!latest){Symbols s=RenderAgent.symbols;framebuffer=(Integer)s.field("com.mojang.blaze3d.pipeline.RenderTarget","frameBufferId",value);colorId=(Integer)s.field("com.mojang.blaze3d.pipeline.RenderTarget","colorTextureId",value);depthId=(Integer)s.field("com.mojang.blaze3d.pipeline.RenderTarget","depthBufferId",value);pass.addProperty("targetFramebuffer",framebuffer);}
            }
            if(call.owner.equals("com.mojang.blaze3d.pipeline.RenderTarget")&&call.target==target) {
                if(call.name.equals("getColorTexture"))color=value;
                if(call.name.equals("getDepthTexture"))depth=value;
                if(call.name.equals("destroyBuffers"))pass.addProperty("targetDestroyed",true);
            }
            if(call.name.equals("createBuffer"))buffer=value;
            if(call.name.equals("createFence"))fence=value;
            if(latest&&call.name.equals("copyTextureToBuffer"))pass.addProperty("copyUsesOwnedTargetAndBuffer",call.args[0]==color&&call.args[1]==buffer);
            if(latest&&call.name.equals("submit"))pass.addProperty("submitted",true);
            if(latest&&call.name.equals("awaitCompletion")&&call.target==fence)pass.addProperty("fenceCompleted",Boolean.TRUE.equals(value));
            if(call.name.equals("map")&&call.target==buffer)mapping=value;
            if(call.name.equals("close"))closed.add(call.target);
            if(call.owner.equals("net.minecraft.client.gui.render.GuiRenderer")&&call.name.equals("endFrame"))pass.addProperty("guiEnded",true);
            if(call.owner.equals("net.minecraft.client.renderer.DynamicGpuData")&&call.name.equals("reset"))pass.addProperty("uniformsReset",true);
            boolean readback=!latest&&call.owner.equals("org.lwjgl.opengl.GL11")&&call.name.equals("glReadPixels")||latest&&call.owner.equals("com.mojang.renderpearl.api.buffers.GpuBufferSlice$MappedView")&&call.name.equals("data");
            if(readback) {
                ByteBuffer pixels=(ByteBuffer)(latest?value:call.args[call.args.length-1]);pass.addProperty("nativeReadbackCompleted",true);pass.addProperty("bufferDirect",pixels.isDirect());pass.addProperty("bufferBytes",pixels.remaining());
                if(!latest){pass.addProperty("framebufferLiveAtReadback",(Boolean)gl("org.lwjgl.opengl.GL30","glIsFramebuffer",new Class<?>[]{int.class},framebuffer));pass.addProperty("boundReadFramebuffer",glInt(36010));}
                boolean white=pass.get("background").getAsFloat()==1f;
                if(pass.get("id").getAsString().equals("minecraft:stone")&&fault.equals(white?"white":"black")) {
                    pass.addProperty("injected",true);throw new IllegalStateException("MCIcon QA: native readback failure ("+fault+")");
                }
            }
        } catch(IllegalStateException error){throw error;}catch(Exception error){throw new IllegalStateException("QA資源を読めません",error);}
        return value;
    }
    public static void atlasEnd(){if(pass!=null&&Thread.currentThread()==renderThread)atlasEnds++;}
    private static Object gl(String owner,String name,Class<?>[] types,Object...args) throws Exception {return Class.forName(owner).getMethod(name,types).invoke(null,args);}
    private static int glInt(int parameter) throws Exception {return (Integer)gl("org.lwjgl.opengl.GL11","glGetInteger",new Class<?>[]{int.class},parameter);}
    private static JsonArray matrix(Object value) throws Exception {float[] values=new float[16];value.getClass().getMethod("get",float[].class).invoke(value,(Object)values);JsonArray result=new JsonArray();for(float f:values)result.add(new JsonPrimitive(f));return result;}
    private static JsonObject snapshot() throws Exception {
        Symbols s=RenderAgent.symbols;JsonObject result=new JsonObject();IntBuffer viewport=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();gl("org.lwjgl.opengl.GL11","glGetIntegerv",new Class<?>[]{int.class,IntBuffer.class},2978,viewport);JsonArray vp=new JsonArray();for(int i=0;i<4;i++)vp.add(new JsonPrimitive(viewport.get(i)));result.add("viewport",vp);
        result.addProperty("drawFramebuffer",glInt(36006));result.addProperty("readFramebuffer",glInt(36010));
        String stateClass=latest?"com.mojang.renderpearl.backend.opengl.GlStateManager":"com.mojang.blaze3d.platform.GlStateManager";
        if(latest){result.addProperty("cachedDrawFramebuffer",(Integer)s.field(stateClass,"writeFbo",null));result.addProperty("cachedReadFramebuffer",(Integer)s.field(stateClass,"readFbo",null));}
        else {for(String kind:Arrays.asList("DRAW","READ")){Object cache=s.field(stateClass,kind+"_FRAMEBUFFER",null);result.addProperty(kind.equals("DRAW")?"cachedDrawFramebuffer":"cachedReadFramebuffer",(Integer)s.field(stateClass+"$FramebufferState","binding",cache));}Object shadow=s.field(stateClass+"$Viewport","INSTANCE",null);JsonArray shadowViewport=new JsonArray();for(String field:Arrays.asList("x","y","width","height"))shadowViewport.add(new JsonPrimitive((Integer)s.field(stateClass+"$Viewport",field,shadow)));result.add("cachedViewport",shadowViewport);}
        Object current;
        if(latest) {
            Object renderer=s.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);current=s.field("net.minecraft.client.renderer.GameRenderer","mainRenderTarget",renderer);
            Object game=s.call("net.minecraft.client.renderer.GameRenderer","gameRenderState",renderer);Object window=s.field("net.minecraft.client.renderer.state.GameRenderState","windowRenderState",game);JsonArray dimensions=new JsonArray();for(String field:Arrays.asList("width","height","guiScale"))dimensions.add(new JsonPrimitive((Number)s.field("net.minecraft.client.renderer.state.WindowRenderState",field,window)));result.add("window",dimensions);
        } else {current=s.field("net.minecraft.client.Minecraft","mainRenderTarget",minecraft);result.add("projection",matrix(s.call("com.mojang.blaze3d.systems.RenderSystem","getProjectionMatrix",null)));result.add("modelView",matrix(s.call("com.mojang.blaze3d.systems.RenderSystem","getModelViewStack",null)));result.addProperty("projectionType",String.valueOf(s.call("com.mojang.blaze3d.systems.RenderSystem","getProjectionType",null)));}
        result.addProperty("mainTargetIdentity",System.identityHashCode(current));return result;
    }
    public static void finish() {
        if(pass==null)return;
        try {
            reading=true;JsonObject after=snapshot();pass.add("after",after);
            Symbols symbols=RenderAgent.symbols;String stateClass=latest?"com.mojang.renderpearl.backend.opengl.GlStateManager":"com.mojang.blaze3d.platform.GlStateManager";
            symbols.call(stateClass,"_glBindFramebuffer",null,36160,0);pass.addProperty("officialBindZeroRestored",glInt(36006)==0&&glInt(36010)==0);
            gl("org.lwjgl.opengl.GL30","glBindFramebuffer",new Class<?>[]{int.class,int.class},36009,after.get("drawFramebuffer").getAsInt());gl("org.lwjgl.opengl.GL30","glBindFramebuffer",new Class<?>[]{int.class,int.class},36008,after.get("readFramebuffer").getAsInt());
            symbols.call(stateClass,"_glBindFramebuffer",null,36009,after.get("drawFramebuffer").getAsInt());symbols.call(stateClass,"_glBindFramebuffer",null,36008,after.get("readFramebuffer").getAsInt());
            pass.addProperty("officialCacheSynchronized",after.get("drawFramebuffer").equals(after.get("cachedDrawFramebuffer"))&&after.get("readFramebuffer").equals(after.get("cachedReadFramebuffer"))&&(latest||after.get("viewport").equals(after.get("cachedViewport"))));
            if(target!=null&&!latest){pass.addProperty("framebufferReleased",!(Boolean)gl("org.lwjgl.opengl.GL30","glIsFramebuffer",new Class<?>[]{int.class},framebuffer));pass.addProperty("colorReleased",!(Boolean)gl("org.lwjgl.opengl.GL11","glIsTexture",new Class<?>[]{int.class},colorId));pass.addProperty("depthReleased",!(Boolean)gl("org.lwjgl.opengl.GL11","glIsTexture",new Class<?>[]{int.class},depthId));}
            if(latest){Symbols s=RenderAgent.symbols;pass.addProperty("mappingClosed",mapping!=null&&closed.contains(mapping));pass.addProperty("fenceClosed",fence!=null&&closed.contains(fence));pass.addProperty("bufferClosed",buffer!=null&&closed.contains(buffer)&&(Boolean)s.call("com.mojang.renderpearl.api.buffers.GpuBuffer","isClosed",buffer));pass.addProperty("colorReleased",color!=null&&(Boolean)s.call("com.mojang.renderpearl.api.textures.GpuTexture","isClosed",color));pass.addProperty("depthReleased",depth!=null&&(Boolean)s.call("com.mojang.renderpearl.api.textures.GpuTexture","isClosed",depth));pass.addProperty("atlasEndCalls",atlasEnds);Object uniforms=s.call("com.mojang.blaze3d.systems.RenderSystem","getDynamicUniforms",null);boolean reset=true;for(String field:Arrays.asList("transforms","terrain")){Object storage=s.field("net.minecraft.client.renderer.DynamicGpuData",field,uniforms);reset=reset&&((Integer)s.field("net.minecraft.client.renderer.DynamicGpuDataStorageMapped","nextBlock",storage))==0;}pass.addProperty("uniformBlocksReset",reset);}
            System.err.println("[mcicon native pass] "+pass);
        } catch(Exception error){throw new IllegalStateException("QA復帰状態を読めません",error);}finally{reading=false;pass=null;calls.clear();}
    }
}
