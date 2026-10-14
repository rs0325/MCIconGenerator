package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.RenderAgent;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;

/** 16px針jobの元GUI atlas描画入口で、scaleとslot寸法だけを採取する。 */
public final class NeedleSamplingAgent {
    private static Symbols symbols;
    private static String version;
    private static final ThreadLocal<String> selected=new ThreadLocal<String>();
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        JsonObject config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),"UTF-8")).getAsJsonObject();
        version=config.get("version").getAsString();symbols=new Symbols(config.has("mappings")?Paths.get(config.get("mappings").getAsString()):null);
        final boolean deferred=version.startsWith("26.")||version.startsWith("1.21.")&&Integer.parseInt(version.substring(5))>=6;
        final String renderer="net.minecraft.client.gui.render.GuiRenderer",atlas="net.minecraft.client.gui.render.GuiItemAtlas";
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                final boolean bridge=name.equals("io/github/rs0325/mcicon/worker/Bridge");
                final boolean old=deferred&&!version.startsWith("26.")&&name.equals(symbols.actualClass(renderer).replace('.','/'));
                final boolean modern=version.startsWith("26.")&&name.equals(symbols.actualClass(atlas).replace('.','/'));
                if(!bridge&&!old&&!modern)return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                        final boolean begin=bridge&&method.equals("renderTransparent")&&desc.equals("(Lcom/google/gson/JsonObject;)Lcom/google/gson/JsonObject;");
                        Type[] args=Type.getArgumentTypes(desc);
                        final boolean observeOld=old&&method.equals(symbols.methodName(renderer,"renderItemToAtlas",5))&&args.length==5&&args[2].getSort()==Type.INT&&args[3].getSort()==Type.INT&&args[4].getSort()==Type.INT&&Type.getReturnType(desc).getSort()==Type.VOID;
                        final boolean observeModern=modern&&method.equals("drawToSlot")&&args.length==4&&args[0].getSort()==Type.INT&&args[1].getSort()==Type.INT&&args[2].getSort()==Type.BOOLEAN&&args[3].getSort()==Type.OBJECT&&Type.getReturnType(desc).getSort()==Type.VOID;
                        if(!begin&&!observeOld&&!observeModern)return target;
                        System.err.println("[mcicon needle sampling hook] "+name+"."+method+desc);
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode() {
                                super.visitCode();
                                if(begin){super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/NeedleSamplingAgent","begin","(Lcom/google/gson/JsonObject;)V",false);}
                                else if(observeOld){super.visitVarInsn(Opcodes.ILOAD,5);super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/NeedleSamplingAgent","slot","(I)V",false);}
                                else {super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/NeedleSamplingAgent","atlas","(Ljava/lang/Object;)V",false);}
                            }
                            public void visitInsn(int opcode) {
                                if(begin&&(opcode==Opcodes.ARETURN||opcode==Opcodes.ATHROW))super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/NeedleSamplingAgent","end","()V",false);
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
        String id=request.get("id").getAsString();
        if(!id.equals("minecraft:clock")&&!id.equals("minecraft:compass")&&!id.equals("minecraft:recovery_compass"))throw new IllegalArgumentException("針sampling QAの対象外です");
        if(request.get("size").getAsInt()!=16)throw new IllegalArgumentException("針sampling QAは16pxだけです");
        selected.set(id);
    }
    public static void end(){selected.remove();}
    public static void atlas(Object atlas) throws Exception {if(selected.get()!=null)slot(((Number)symbols.field("net.minecraft.client.gui.render.GuiItemAtlas","slotTextureSize",atlas)).intValue());}
    public static void slot(int pixels) throws Exception {
        String id=selected.get();if(id==null)return;
        Object minecraft=symbols.call("net.minecraft.client.Minecraft","getInstance",null);Number scale;int width,height;
        if(version.startsWith("26.")) {
            Object renderer=symbols.field("net.minecraft.client.Minecraft","gameRenderer",minecraft);
            Object state=symbols.call("net.minecraft.client.renderer.GameRenderer",version.startsWith("26.1")?"getGameRenderState":"gameRenderState",renderer);
            Object window=symbols.field("net.minecraft.client.renderer.state.GameRenderState","windowRenderState",state);
            scale=(Number)symbols.field("net.minecraft.client.renderer.state.WindowRenderState","guiScale",window);
            width=((Number)symbols.field("net.minecraft.client.renderer.state.WindowRenderState","width",window)).intValue();height=((Number)symbols.field("net.minecraft.client.renderer.state.WindowRenderState","height",window)).intValue();
        } else {
            Object window=symbols.call("net.minecraft.client.Minecraft","getWindow",minecraft);
            scale=(Number)symbols.call("com.mojang.blaze3d.platform.Window","getGuiScale",window);
            width=((Number)symbols.call("com.mojang.blaze3d.platform.Window","getGuiScaledWidth",window)).intValue();height=((Number)symbols.call("com.mojang.blaze3d.platform.Window","getGuiScaledHeight",window)).intValue();
        }
        JsonObject observation=new JsonObject();observation.addProperty("version",version);observation.addProperty("id",id);observation.addProperty("guiScale",scale.doubleValue());observation.addProperty("slotTexturePixels",pixels);observation.addProperty("guiWidth",width);observation.addProperty("guiHeight",height);observation.addProperty("beforeAtlasDraw",true);
        System.err.println("[mcicon needle sampling] "+observation);
        if(scale.doubleValue()!=1||pixels!=16||width!=16||height!=16)throw new IllegalStateException("元GUI atlasの16px中心対応が事前条件と一致しません");
    }
}
