package io.github.rs0325.mcicon.qa;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import io.github.rs0325.mcicon.worker.shaded.asm.*;

/** ゲームJVMが外部processを要求したら、fork前にQAを明示失敗させる。 */
public final class SpawnGuard {
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                if(!"java/lang/ProcessBuilder".equals(name))return null;
                ClassReader reader=new ClassReader(bytes);
                ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                        if(!method.equals("start")||!desc.endsWith(")Ljava/lang/Process;"))return target;
                        target.visitCode();
                        target.visitTypeInsn(Opcodes.NEW,"java/lang/AssertionError");
                        target.visitInsn(Opcodes.DUP);
                        target.visitLdcInsn("MCICON_QA_EXTERNAL_PROCESS_REQUEST");
                        target.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","(Ljava/lang/Object;)V",false);
                        target.visitInsn(Opcodes.DUP);
                        target.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/lang/AssertionError","printStackTrace","()V",false);
                        target.visitInsn(Opcodes.ATHROW);
                        target.visitMaxs(0,0);target.visitEnd();
                        return null;
                    }
                },0);
                return writer.toByteArray();
            }
        },true);
        instrumentation.retransformClasses(ProcessBuilder.class);
        System.err.println("[mcicon QA] external process guard installed");
    }
}
