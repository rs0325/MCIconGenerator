package io.github.rs0325.mcicon.worker;

import com.google.gson.*;
import org.objectweb.asm.*;
import java.io.*;
import java.lang.instrument.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;

/** Injects an application-owned callback; the client JAR is never modified on disk. */
public final class RenderAgent {
    public static Symbols symbols;
    public static JsonObject config;
    public static PrintStream protocol;
    public static CanonicalTransformer canonical;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        protocol=System.out;
        System.setOut(System.err);
        config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),StandardCharsets.UTF_8)).getAsJsonObject();
        symbols=new Symbols(config.has("mappings")?Paths.get(config.get("mappings").getAsString()):null);
        canonical=new CanonicalTransformer(symbols,config.has("profile")&&config.get("profile").getAsString().startsWith("legacy-"));
        instrumentation.addTransformer(canonical);
        instrumentation.addTransformer(new ResourceTransformer(symbols,config.has("profile")&&config.get("profile").getAsString().startsWith("legacy-")));
        final String owner=config.has("hookClass")?config.get("hookClass").getAsString():"net.minecraft.client.Minecraft";
        final String target=symbols.actualClass(owner).replace('.','/');
        final String descriptor=config.has("hookDescriptor")?config.get("hookDescriptor").getAsString():"(Z)V";
        final String hook=symbols.methodName(owner,config.has("tickMethod")?config.get("tickMethod").getAsString():"runTick",Type.getArgumentTypes(descriptor).length);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                if(!target.equals(name)) return null;
                ClassReader reader=new ClassReader(bytes);
                ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                        MethodVisitor mv=super.visitMethod(access,method,desc,signature,exceptions);
                        if(!method.equals(hook)||!desc.equals(descriptor)) return mv;
                        return new MethodVisitor(Opcodes.ASM9,mv) {
                            public void visitInsn(int opcode) {
                                if(opcode==Opcodes.RETURN) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/worker/Bridge","frame","()V",false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);
                System.err.println("[mcicon] render-thread hook installed: "+target+"."+hook);
                return writer.toByteArray();
            }
        });
        Bridge.start();
    }
}
