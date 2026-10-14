package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.RenderAgent;
import io.github.rs0325.mcicon.worker.CanonicalContext;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.nio.file.*;

/** QAパックのblack/white readback時だけproduction FBOの色形式を観測する。 */
public final class ColorFramebufferAgent {
    private static Path output;
    private static final JsonArray samples=new JsonArray();
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        output=Paths.get(argument).getParent().resolve("color-framebuffers.json");
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                if(!name.equals("io/github/rs0325/mcicon/worker/PngOutput"))return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,name,descriptor,signature,exceptions);
                        if(!name.equals("write")||!descriptor.equals("(Ljava/nio/ByteBuffer;ILjava/nio/file/Path;)I"))return target;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode() {super.visitCode();super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/ColorFramebufferAgent","record","()V",false);}
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
        ResourcePackFaultAgent.premain(argument,instrumentation);
    }
    public static void record() {
        try {
            JsonObject state=GuiReferenceAgent.framebufferState(RenderAgent.symbols);
            state.addProperty("probeBackground",CanonicalContext.backgroundColor());samples.add(state);
            Files.write(output,new Gson().toJson(samples).getBytes("UTF-8"));
        } catch(Exception error) {throw new IllegalStateException("production readback時の色形式を確認できません",error);}
    }
}
