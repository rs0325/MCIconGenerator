package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.RenderAgent;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;

/** 既定stackが取得するresource・cacheと既存欠損観測を読み取りだけで記録する。 */
public final class SpecialResourceAgent {
    private static final String SELF="io/github/rs0325/mcicon/qa/SpecialResourceAgent";
    private static Symbols symbols;
    private static boolean legacy;
    private static boolean pearl;
    private static final ThreadLocal<String> selected=new ThreadLocal<String>();
    private static final Map<Object,Integer> identities=new IdentityHashMap<Object,Integer>();
    private static final Map<Object,Object> gpuOwners=new IdentityHashMap<Object,Object>();
    private static final Map<Object,String> textures=new IdentityHashMap<Object,String>();
    private static final Map<String,Integer> events=new TreeMap<String,Integer>();
    private static int nextIdentity;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        JsonObject config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),StandardCharsets.UTF_8)).getAsJsonObject();
        symbols=new Symbols(config.has("mappings")?Paths.get(config.get("mappings").getAsString()):null);
        legacy=config.get("profile").getAsString().startsWith("legacy-");pearl=config.get("profile").getAsString().equals("gui-26.3");
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                final String source=symbols.sourceClass(name.replace('/','.'));
                final boolean bridge=name.equals("io/github/rs0325/mcicon/worker/Bridge");
                final boolean guard=name.equals("io/github/rs0325/mcicon/worker/ResourceGuard");
                final boolean manager=source.equals(legacy?"net.minecraft.client.render.texture.TextureManager":"net.minecraft.client.renderer.texture.TextureManager");
                final boolean atlas=source.equals(legacy?"net.minecraft.client.render.texture.TextureAtlas":"net.minecraft.client.renderer.texture.TextureAtlas")||source.equals("net.minecraft.client.resources.model.sprite.AtlasManager");
                final boolean resources=source.equals(legacy?"net.minecraft.resource.manager.FallbackResourceManager":"net.minecraft.server.packs.resources.FallbackResourceManager");
                final boolean gpuPass=name.equals("com/mojang/renderpearl/frontend/FrontendRenderPass");
                final boolean spriteLoader=source.equals("net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader");
                final boolean texture=source.equals(legacy?"net.minecraft.client.render.texture.AbstractTexture":"net.minecraft.client.renderer.texture.AbstractTexture");
                if(!bridge&&!guard&&!manager&&!atlas&&!resources&&!texture&&!spriteLoader&&!gpuPass)return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                        final boolean begin=bridge&&method.equals("renderTransparent")&&desc.equals("(Lcom/google/gson/JsonObject;)Lcom/google/gson/JsonObject;");
                        final boolean end=guard&&method.equals("end")&&desc.equals("()V");
                        final String identifier="L"+symbols.actualClass(legacy?"net.minecraft.resource.Identifier":config.get("version").getAsString().startsWith("26.")?"net.minecraft.resources.Identifier":"net.minecraft.resources.ResourceLocation").replace('.','/')+";";
                        final String textureType="L"+symbols.actualClass(legacy?"net.minecraft.client.render.texture.Texture":"net.minecraft.client.renderer.texture.AbstractTexture").replace('.','/')+";";
                        final String spriteType="L"+symbols.actualClass(legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite").replace('.','/')+";";
                        final String resourceType="L"+symbols.actualClass(legacy?"net.minecraft.resource.Resource":"net.minecraft.server.packs.resources.Resource").replace('.','/')+";";
                        final boolean observe=guard&&(Arrays.asList("texture","sprite","missingContents").contains(method)&&desc.equals("(Ljava/lang/Object;)V")||method.equals("appliedTexture")&&desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                        final boolean get=manager&&method.equals(symbols.methodName(source,legacy?"get":"getTexture",1))&&desc.equals("("+identifier+")"+textureType);
                        final boolean bind=manager&&legacy&&method.equals(symbols.methodName(source,"bind",1))&&desc.equals("("+identifier+")V");
                        final String spriteArgument=source.endsWith("AtlasManager")?"Lnet/minecraft/client/resources/model/sprite/SpriteId;":identifier;
                        final boolean sprite=atlas&&method.equals(symbols.methodName(source,source.endsWith("AtlasManager")?"get":"getSprite",1))&&(desc.equals("("+spriteArgument+")"+spriteType)||legacy&&desc.equals("(Ljava/lang/String;)"+spriteType));
                        final boolean resource=resources&&method.equals(symbols.methodName(legacy?"net.minecraft.resource.manager.ResourceManager":source,"getResource",1))&&desc.equals("("+identifier+")"+(legacy?resourceType:"Ljava/util/Optional;"));
                        final boolean uniform=gpuPass&&method.equals("setUniform")&&desc.equals("(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V");
                        final String contentsType="L"+symbols.actualClass("net.minecraft.client.renderer.texture.SpriteContents").replace('.','/')+";";
                        final boolean loadSprite=spriteLoader&&method.equals(symbols.methodName(source,"lambda$create$0",3))&&desc.equals("(Ljava/util/"+(pearl?"Set":"Collection")+";"+identifier+resourceType+")"+contentsType);
                        final String gpuTexture="L"+(pearl?"com/mojang/renderpearl/api/":"com/mojang/blaze3d/")+"textures/GpuTexture";
                        final boolean accessTexture=texture&&(method.equals(symbols.methodName(source,"getId",0))&&desc.equals("()I")||method.equals(symbols.methodName(source,"getTexture",0))&&desc.equals("()"+gpuTexture+";")||method.equals(symbols.methodName(source,"getTextureView",0))&&desc.equals("()"+gpuTexture+"View;"));
                        if(!begin&&!end&&!observe&&!get&&!bind&&!sprite&&!resource&&!accessTexture&&!loadSprite&&!uniform)return target;
                        System.err.println("[mcicon special hook] "+source+"."+method+desc);
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode() {
                                super.visitCode();
                                if(uniform){super.visitVarInsn(Opcodes.ALOAD,1);super.visitVarInsn(Opcodes.ALOAD,2);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"gpu","(Ljava/lang/String;Ljava/lang/Object;)V",false);}
                                if(begin){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"begin","(Lcom/google/gson/JsonObject;)V",false);}
                                if(end)super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"end","()V",false);
                                if(observe){super.visitLdcInsn("guard:"+method);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"object","(Ljava/lang/String;Ljava/lang/Object;)V",false);}
                                if(accessTexture){super.visitLdcInsn("access:"+method);super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"object","(Ljava/lang/String;Ljava/lang/Object;)V",false);}
                                if(get||bind){super.visitVarInsn(Opcodes.ALOAD,0);super.visitVarInsn(Opcodes.ALOAD,1);super.visitLdcInsn(get?"get":"bind");super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"cache","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V",false);}
                                if(loadSprite){super.visitLdcInsn("sprite-load");super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"identifier","(Ljava/lang/String;Ljava/lang/Object;)V",false);}
                                if(sprite||resource){super.visitLdcInsn(sprite?"atlas":"resource");super.visitVarInsn(Opcodes.ALOAD,1);super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"identifier","(Ljava/lang/String;Ljava/lang/Object;)V",false);}
                            }
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
        RenderAgent.premain(argument,instrumentation);
    }
    public static synchronized void begin(JsonObject request) {selected.set(request.get("id").getAsString());events.clear();}
    public static synchronized void end() {
        JsonObject result=new JsonObject();result.addProperty("id",selected.get());result.add("events",new Gson().toJsonTree(events));
        System.err.println("[mcicon special pass] "+result);events.clear();selected.remove();
    }
    private static int identity(Object value) {if(value==null)return 0;Integer id=identities.get(value);if(id==null){id=++nextIdentity;identities.put(value,id);}return id;}
    private static void record(String event) {if(selected.get()!=null)events.put(event,events.containsKey(event)?events.get(event)+1:1);}
    public static synchronized void identifier(String kind,Object value) {
        String name=String.valueOf(value);
        if(kind.equals("resource")&&name.startsWith("minecraft:textures/entity/")||kind.equals("sprite-load")&&name.startsWith("minecraft:entity/"))System.err.println("[mcicon special resource] "+kind+" "+name);
        record(kind+" "+name);
    }
    public static synchronized void cache(Object manager,Object id,String operation) {
        try {
            String owner=legacy?"net.minecraft.client.render.texture.TextureManager":"net.minecraft.client.renderer.texture.TextureManager";
            Map<?,?> cache=(Map<?,?>)symbols.field(owner,legacy?"textures":"byPath",manager);
            Object value=cache.get(id);if(value!=null)textures.put(value,String.valueOf(id));
            record(operation+" "+id+" cache="+(value!=null)+" object="+identity(value));
        } catch(Exception error) {throw new IllegalStateException("QA texture cacheを読み取れません",error);}
    }
    public static synchronized void gpu(String sampler,Object view) {
        Object owner=gpuOwners.get(view);String resource=textures.get(owner);
        record("gpu "+sampler+" view="+identity(view)+" texture="+identity(owner)+(resource==null?"":" resource="+resource));
    }
    public static synchronized void object(String operation,Object value) {
        String name=textures.get(value);
        if(pearl&&value!=null&&(operation.equals("guard:texture")||operation.startsWith("access:"))) {
            try {Object view=symbols.field("net.minecraft.client.renderer.texture.AbstractTexture","textureView",value);if(view!=null)gpuOwners.put(view,value);}
            catch(Exception error) {throw new IllegalStateException("QA GPU cache対応を読み取れません",error);}
        }
        if(selected.get()==null)return;
        if(operation.equals("guard:sprite")&&value!=null) {
            try {
                String owner=legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite";
                name=String.valueOf(legacy||symbols.hasMethod(owner,"getName",0)?symbols.call(owner,"getName",value):symbols.call("net.minecraft.client.renderer.texture.SpriteContents","name",symbols.call(owner,"contents",value)));
            } catch(Exception error) {throw new IllegalStateException("QA sprite名を読み取れません",error);}
        }
        record(operation+" object="+identity(value)+(name==null?"":" resource="+name));
    }
}
