package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class HardwareDiagnosticsTransformerTest {
    @Test void diagnosticCommandsKeepTheirEmptyResultContractWithoutEnteringTheirBody() throws Exception {
        for(String resultType:new String[]{"Ljava/util/List;","Ljava/util/ArrayList;"}) {
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"oshi/util/ExecutingCommand",null,"java/lang/Object",null);
            String[] descriptors={"(Ljava/lang/String;)"+resultType,"([Ljava/lang/String;)"+resultType,"([Ljava/lang/String;[Ljava/lang/String;)"+resultType};
            for(String desc:descriptors) {
                MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"runNative",desc,null,null);
                method.visitCode();
                method.visitTypeInsn(Opcodes.NEW,"java/lang/AssertionError");
                method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","()V",false);
                method.visitInsn(Opcodes.ATHROW);
                method.visitMaxs(0,0);method.visitEnd();
            }
            MethodVisitor marker=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"marker","()I",null,null);
            marker.visitCode();marker.visitIntInsn(Opcodes.BIPUSH,42);marker.visitInsn(Opcodes.IRETURN);marker.visitMaxs(0,0);marker.visitEnd();
            if(resultType.equals("Ljava/util/ArrayList;")) {
                MethodVisitor first=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"getFirstAnswer","()Ljava/lang/String;",null,null);
                first.visitCode();first.visitLdcInsn("lspci -vnnm");
                first.visitMethodInsn(Opcodes.INVOKESTATIC,"oshi/util/ExecutingCommand","runNative",descriptors[0],false);
                first.visitVarInsn(Opcodes.ASTORE,0);first.visitVarInsn(Opcodes.ALOAD,0);
                Label nonnull=new Label();first.visitJumpInsn(Opcodes.IFNONNULL,nonnull);
                first.visitInsn(Opcodes.ACONST_NULL);first.visitInsn(Opcodes.ARETURN);
                first.visitLabel(nonnull);first.visitVarInsn(Opcodes.ALOAD,0);first.visitInsn(Opcodes.ICONST_0);
                first.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/ArrayList","get","(I)Ljava/lang/Object;",false);
                first.visitTypeInsn(Opcodes.CHECKCAST,"java/lang/String");first.visitInsn(Opcodes.ARETURN);
                first.visitMaxs(0,0);first.visitEnd();
            }
            writer.visitEnd();
            byte[] original=writer.toByteArray();
            Class<?> unchanged=new Loader().define(original);
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->unchanged.getMethod("runNative",String.class).invoke(null,"lspci -vnnm"));
            byte[] changed=new HardwareDiagnosticsTransformer().transform(null,"oshi/util/ExecutingCommand",null,null,original);
            Class<?> command=new Loader().define(changed);
            for(Class<?>[] params:new Class<?>[][]{{String.class},{String[].class},{String[].class,String[].class}}) {
                Object[] arguments=new Object[params.length];
                for(int i=0;i<params.length;i++)arguments[i]=params[i]==String.class?"lspci -vnnm":new String[]{"lspci","-vnnm"};
                @SuppressWarnings("unchecked") List<String> result=(List<String>)command.getMethod("runNative",params).invoke(null,arguments);
                if(resultType.equals("Ljava/util/ArrayList;"))assertNull(result);
                else {
                    assertTrue(result.isEmpty());result.add("変更可能");
                    assertEquals(1,result.size());
                }
            }
            if(resultType.equals("Ljava/util/ArrayList;"))assertNull(command.getMethod("getFirstAnswer").invoke(null));
            assertEquals(42,command.getMethod("marker").invoke(null));
            assertNull(new HardwareDiagnosticsTransformer().transform(null,"other/ExecutingCommand",null,null,original));
        }
    }
    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) {return defineClass(null,bytes,0,bytes.length);}
    }
}
