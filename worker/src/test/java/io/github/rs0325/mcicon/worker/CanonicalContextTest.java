package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class CanonicalContextTest {
    @Test void renderClockAndRandomResetWithoutChangingWorkerClock() {
        assertFalse(CanonicalContext.active());
        long real=CanonicalContext.epochMillis();
        assertTrue(Math.abs(real-System.currentTimeMillis())<1000);
        Random original=new Random(44),reference=new Random(44);
        assertEquals(reference.nextInt(100),CanonicalContext.nextInt(original,100));
        CanonicalContext.begin();
        try {
            assertEquals(1000,CanonicalContext.elapsedMillis());
            assertEquals(CanonicalContext.EPOCH_MILLIS,CanonicalContext.epochMillis());
            double first=CanonicalContext.mathRandom();
            int second=CanonicalContext.nextInt(original,100);
            CanonicalContext.begin();
            assertEquals(first,CanonicalContext.mathRandom());
            assertEquals(second,CanonicalContext.nextInt(original,100));
        } finally {CanonicalContext.end();}
        assertFalse(CanonicalContext.active());
        assertEquals(reference.nextInt(100),CanonicalContext.nextInt(original,100));
    }
    @Test void rewrittenDateCalendarAndRandomExecuteValidBytecode() throws Exception {
        final byte[] transformed=new CanonicalTransformer(new Symbols(null),false).transformGameClass("net/minecraft/client/renderer/item/properties/numeric/Time",fixture());
        Class<?> type=new ClassLoader() {
            Class<?> define() {return defineClass("fixture.CalendarCalls",transformed,0,transformed.length);}
        }.define();
        assertEquals(CanonicalContext.EPOCH_MILLIS,((Date)type.getMethod("date").invoke(null)).getTime());
        assertEquals(CanonicalContext.EPOCH_MILLIS,((Calendar)type.getMethod("calendar").invoke(null)).getTimeInMillis());
        Method random=type.getMethod("random",Random.class);
        CanonicalContext.begin();
        try {
            int first=(Integer)random.invoke(null,new Random(55));
            CanonicalContext.begin();
            assertEquals(first,(Integer)random.invoke(null,new Random(999)));
            assertEquals(CanonicalContext.EPOCH_MILLIS,type.getMethod("time").invoke(null));
        } finally {CanonicalContext.end();}
        assertTrue((Long)type.getMethod("time").invoke(null)>CanonicalContext.EPOCH_MILLIS);
    }
    @Test void preservesNativeSeededModelRandomnessOutsideDynamicProperties() throws Exception {
        final byte[] bytes=new CanonicalTransformer(new Symbols(null),false).transformGameClass("net/minecraft/client/renderer/block/ModelBlockRenderer",fixture());
        Class<?> type=new ClassLoader() {Class<?> define(){return defineClass("fixture.CalendarCalls",bytes,0,bytes.length);}}.define();
        CanonicalContext.begin();
        try {
            Random source=new Random(42),reference=new Random(42);
            assertEquals(reference.nextInt(100),type.getMethod("random",Random.class).invoke(null,source));
            assertEquals(reference.nextInt(100),type.getMethod("random",Random.class).invoke(null,source));
        } finally {CanonicalContext.end();}
    }
    @Test void neverTransformsDependencyOrWorkerClasses() throws Exception {
        CanonicalTransformer transformer=new CanonicalTransformer(new Symbols(null),false);
        assertNull(transformer.transform(null,"fixture/CalendarCalls",null,null,fixture()));
    }
    @Test void atlasTickBodyIsRemovedButClassStillVerifies() throws Exception {
        ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"net/minecraft/client/renderer/texture/TextureAtlas",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"cycleAnimationFrames","()V",null,null);
        method.visitCode();method.visitTypeInsn(Opcodes.NEW,"java/lang/AssertionError");method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/AssertionError","<init>","()V",false);method.visitInsn(Opcodes.ATHROW);method.visitMaxs(2,0);method.visitEnd();writer.visitEnd();
        CanonicalTransformer transformer=new CanonicalTransformer(new Symbols(null),false);
        final byte[] bytes=transformer.transformGameClass("net/minecraft/client/renderer/texture/TextureAtlas",writer.toByteArray());
        Class<?> type=new ClassLoader() {Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        type.getMethod("cycleAnimationFrames").invoke(null);
        assertTrue(transformer.animationHook);
    }
    private byte[] fixture() {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/CalendarCalls",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"date","()Ljava/util/Date;",null,null);
        method.visitCode();method.visitTypeInsn(Opcodes.NEW,"java/util/Date");method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/util/Date","<init>","()V",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(0,0);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"calendar","()Ljava/util/Calendar;",null,null);
        method.visitCode();method.visitMethodInsn(Opcodes.INVOKESTATIC,"java/util/Calendar","getInstance","()Ljava/util/Calendar;",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(0,0);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"random","(Ljava/util/Random;)I",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitIntInsn(Opcodes.BIPUSH,100);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/Random","nextInt","(I)I",false);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(0,0);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"time","()J",null,null);
        method.visitCode();method.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","currentTimeMillis","()J",false);method.visitInsn(Opcodes.LRETURN);method.visitMaxs(0,0);method.visitEnd();
        writer.visitEnd();return writer.toByteArray();
    }
}
