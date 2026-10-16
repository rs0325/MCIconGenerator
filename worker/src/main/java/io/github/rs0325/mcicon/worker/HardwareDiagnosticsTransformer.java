package io.github.rs0325.mcicon.worker;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** 描画に不要なOSHIの外部コマンド診断を止め、そのprocess生成を避ける。 */
final class HardwareDiagnosticsTransformer implements ClassFileTransformer {
    public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
        if(!"oshi/util/ExecutingCommand".equals(name))return null;
        ClassReader reader=new ClassReader(bytes);
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                String result=Type.getReturnType(desc).getDescriptor();
                if(!method.equals("runNative")||(access&Opcodes.ACC_STATIC)==0||!result.equals("Ljava/util/List;")&&!result.equals("Ljava/util/ArrayList;"))return target;
                target.visitCode();
                // OSHI 1.1の呼出元は、空Listではなくnullを失敗として扱う。
                if(result.equals("Ljava/util/ArrayList;"))target.visitInsn(Opcodes.ACONST_NULL);
                else {
                    target.visitTypeInsn(Opcodes.NEW,"java/util/ArrayList");
                    target.visitInsn(Opcodes.DUP);
                    target.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/util/ArrayList","<init>","()V",false);
                }
                target.visitInsn(Opcodes.ARETURN);
                target.visitMaxs(0,0);
                target.visitEnd();
                return null;
            }
        },0);
        return writer.toByteArray();
    }
}
