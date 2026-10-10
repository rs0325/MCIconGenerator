package io.github.rs0325.mcicon.qa;

import io.github.rs0325.mcicon.worker.*;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;

/** QA-only agent wrapper: substitute vanilla's real fallback in a requested pass. */
public final class ResourceFaultAgent {
    private static String fault;
    private static boolean legacy;
    private static final ThreadLocal<Boolean> selected=new ThreadLocal<Boolean>();
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        try(java.io.InputStream input=ResourceFaultAgent.class.getResourceAsStream("/mcicon-resource-fault.txt")) {
            fault=new String(read(input),"UTF-8").trim();
        }
        com.google.gson.JsonObject config=new com.google.gson.JsonParser().parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(argument)),"UTF-8")).getAsJsonObject();
        legacy=config.get("profile").getAsString().startsWith("legacy-");
        final Symbols s=new Symbols(config.has("mappings")?java.nio.file.Paths.get(config.get("mappings").getAsString()):null);
        final String manager=legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager";
        final boolean baked=legacy||s.hasField(manager,"missingModel")&&!s.hasField(manager,"missingItemModel");
        final String itemRenderer=legacy?"net.minecraft.client.render.entity.ItemRenderer":"net.minecraft.client.renderer.entity.ItemRenderer";
        final String owner=baked?itemRenderer:manager;
        final int arity=baked?(legacy||s.hasMethod(itemRenderer,"getModel",3)?3:4):1;
        final String method=s.methodName(owner,baked?"getModel":"getItemModel",arity);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                if(name.equals("io/github/rs0325/mcicon/worker/Bridge")) {
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                            MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                            if(!name.equals("renderTransparent"))return target;
                            return new MethodVisitor(Opcodes.ASM9,target) {
                                public void visitCode() {
                                    super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/ResourceFaultAgent","select","(Lcom/google/gson/JsonObject;)V",false);
                                }
                            };
                        }
                    },0);
                    return writer.toByteArray();
                }
                if(fault.equals("texture")&&name.equals("io/github/rs0325/mcicon/worker/ResourceGuard")) {
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                            MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                            if(!name.equals("sprite")||!desc.equals("(Ljava/lang/Object;)V"))return target;
                            return new MethodVisitor(Opcodes.ASM9,target) {
                                public void visitCode() {
                                    super.visitCode();super.visitVarInsn(Opcodes.ALOAD,0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/ResourceFaultAgent","texture","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                                    super.visitVarInsn(Opcodes.ASTORE,0);
                                }
                            };
                        }
                    },0);
                    System.err.println("[mcicon QA] missing sprite observer input substituted");
                    return writer.toByteArray();
                }
                if(!fault.equals("model"))return null;
                if(!name.equals(s.actualClass(owner).replace('.','/')))return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                        if(!name.equals(method)||Type.getArgumentTypes(desc).length!=arity||Type.getReturnType(desc).getSort()!=Type.OBJECT)return target;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitInsn(int opcode) {
                                if(opcode==Opcodes.ARETURN) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/ResourceFaultAgent","model","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST,Type.getReturnType(desc).getInternalName());
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);
                System.err.println("[mcicon QA] resource fault hook: "+owner+"."+method);
                return writer.toByteArray();
            }
        });
        // Substitute first so the production observer sees the actual fallback.
        RenderAgent.premain(argument,instrumentation);
    }
    public static void select(com.google.gson.JsonObject request) {selected.set(request.get("id").getAsString().equals("minecraft:stone"));}
    public static Object model(Object original) {
        if(!CanonicalContext.active()||!Boolean.TRUE.equals(selected.get()))return original;
        try {
            Symbols s=RenderAgent.symbols;
            Object minecraft=s.call("net.minecraft.client.Minecraft","getInstance",null);
            Object manager=s.call("net.minecraft.client.Minecraft","getModelManager",minecraft);
            String owner=legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager";
            if(legacy)return s.field(owner,"missing",manager);
            if(s.hasField(owner,"missingItemModel"))return s.field(owner,"missingItemModel",manager);
            if(s.hasField(owner,"missingModel"))return s.field(owner,"missingModel",manager);
            return s.call("net.minecraft.client.resources.model.ModelBakery$MissingModels","item",s.field(owner,"missingModels",manager));
        }catch(Exception e){throw new IllegalStateException("QA model substitution failed",e);}
    }
    public static Object texture(Object original) {
        if(!CanonicalContext.active()||!Boolean.TRUE.equals(selected.get()))return original;
        try {
            Symbols s=RenderAgent.symbols;
            Object minecraft=s.call("net.minecraft.client.Minecraft","getInstance",null);
            if(legacy) {
                Object atlas=s.call("net.minecraft.client.Minecraft","getBlocksAtlas",minecraft);
                Object name=s.call("net.minecraft.client.render.texture.MissingTextureAtlasSprite","getLocation",null);
                return s.call("net.minecraft.client.render.texture.TextureAtlas","getSprite",atlas,name);
            }
            String identifier=RenderAgent.config.get("version").getAsString().startsWith("26.")?"net.minecraft.resources.Identifier":"net.minecraft.resources.ResourceLocation";
            Object atlasId=identifier.endsWith("Identifier")||s.hasMethod(identifier,"parse",1)?s.call(identifier,"parse",null,"minecraft:textures/atlas/blocks.png"):s.construct(identifier,"minecraft:textures/atlas/blocks.png");
            Object missing=s.call("net.minecraft.client.renderer.texture.MissingTextureAtlasSprite","getLocation",null);
            if(identifier.endsWith("Identifier")) {
                Object manager=s.call("net.minecraft.client.Minecraft","getAtlasManager",minecraft);
                Object atlas=s.call("net.minecraft.client.resources.model.sprite.AtlasManager","getAtlasOrThrow",manager,atlasId);
                return s.call("net.minecraft.client.renderer.texture.TextureAtlas","getSprite",atlas,missing);
            }
            @SuppressWarnings("unchecked") java.util.function.Function<Object,Object> sprites=(java.util.function.Function<Object,Object>)s.call("net.minecraft.client.Minecraft","getTextureAtlas",minecraft,atlasId);
            return sprites.apply(missing);
        }catch(Exception e){throw new IllegalStateException("QA texture substitution failed",e);}
    }
    private static byte[] read(java.io.InputStream input) throws java.io.IOException {
        if(input==null)throw new java.io.IOException("Missing QA fault selector");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while((count=input.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();
    }
}
