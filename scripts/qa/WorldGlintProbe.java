package io.github.rs0325.mcicon.qa;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

/** 時計の不足・過剰・descriptor相違を元bytecodeの合成fixtureで拒否する。 */
public final class WorldGlintProbe {
    static byte[] fixture(String descriptor,int count) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"GlintFixture",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"glint",descriptor,null,null);method.visitCode();
        for(int i=0;i<count;i++){method.visitMethodInsn(Opcodes.INVOKESTATIC,"ClockFixture","millis","()J",false);method.visitInsn(Opcodes.POP2);}
        if(descriptor.endsWith(";")){method.visitInsn(Opcodes.ACONST_NULL);method.visitInsn(Opcodes.ARETURN);}else method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        for(String descriptor:new String[]{"(Ljava/lang/Object;Ljava/lang/Runnable;I)V","(F)V","(F)Lorg/joml/Matrix4f;"}) {
            int expected=descriptor.startsWith("(Ljava/")?2:1;
            for(int count=0;count<=3;count++) {
                boolean accepted=false;
                try {WorldInventoryAgent.fixGlint(fixture(descriptor,count),"glint",descriptor,"ClockFixture","millis",expected);accepted=true;}
                catch(IllegalStateException rejected){}
                if(accepted!=(count==expected))throw new AssertionError("時計置換数: "+count+"/"+expected);
            }
            try {WorldInventoryAgent.fixGlint(fixture(descriptor,expected),"glint","()V","ClockFixture","millis",expected);throw new AssertionError("descriptor不一致の受理");}
            catch(IllegalStateException rejected){}
        }
        ClassWriter shader=new ClassWriter(ClassWriter.COMPUTE_MAXS);shader.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"ShaderFixture",null,"java/lang/Object",null);
        MethodVisitor constructor=shader.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);constructor.visitCode();constructor.visitVarInsn(Opcodes.ALOAD,0);constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);constructor.visitInsn(Opcodes.RETURN);constructor.visitMaxs(0,0);constructor.visitEnd();
        MethodVisitor apply=shader.visitMethod(Opcodes.ACC_PUBLIC,"apply","()V",null,null);apply.visitCode();Label end=new Label();apply.visitJumpInsn(Opcodes.GOTO,end);apply.visitLabel(end);apply.visitFrame(Opcodes.F_FULL,0,new Object[0],0,new Object[0]);apply.visitInsn(Opcodes.RETURN);apply.visitMaxs(0,0);apply.visitEnd();shader.visitEnd();
        final byte[] observed=WorldInventoryAgent.observeShader(shader.toByteArray(),"apply");
        Class<?> checked=new ClassLoader(WorldGlintProbe.class.getClassLoader()){Class<?> define(){return defineClass("ShaderFixture",observed,0,observed.length);}}.define();
        checked.getMethod("apply").invoke(checked.newInstance());
        System.out.println("Shader.applyの空locals StackMap保持・実class検証: PASS");
        java.lang.reflect.Field scope=WorldInventoryAgent.class.getDeclaredField("observingItem"),thread=WorldInventoryAgent.class.getDeclaredField("observingThread"),held=WorldInventoryAgent.class.getDeclaredField("applyingShader"),stage=WorldInventoryAgent.class.getDeclaredField("stage");
        for(java.lang.reflect.Field field:new java.lang.reflect.Field[]{scope,thread,held,stage})field.setAccessible(true);
        Object target=new Object();WorldInventoryAgent.beginShader(target);if(held.get(null)!=null)throw new AssertionError("GUI外のshader保持");
        scope.setBoolean(null,true);thread.set(null,Thread.currentThread());stage.setInt(null,3);WorldInventoryAgent.beginShader(target);
        Thread foreign=new Thread(()->{WorldInventoryAgent.beginShader(new Object());WorldInventoryAgent.observeAppliedShader();});foreign.start();foreign.join(1000);
        if(foreign.isAlive()||held.get(null)!=target)throw new AssertionError("別threadのshader誤採用");
        WorldInventoryAgent.endGui();if(held.get(null)!=null||thread.get(null)!=null)throw new AssertionError("GUI終了時のshader残存");
        scope.setBoolean(null,true);thread.set(null,Thread.currentThread());stage.setInt(null,4);WorldInventoryAgent.beginShader(target);if(held.get(null)!=null)throw new AssertionError("失敗後stageのshader保持");
        WorldInventoryAgent.endGui();stage.setInt(null,0);
        System.out.println("target GUI/thread/stage限定とshader終了回収: PASS");

        if(WorldInventoryAgent.glintMillis()!=1000L)throw new AssertionError("固定値");
        System.out.println("glint時計の3形式・不足/過剰/descriptor拒否: PASS");
        if(args.length==1) {
            Path cache=Paths.get(args[0]);Path configuration=Files.createTempFile("mcicon-glint-probe-",".json");
            Instrumentation instrumentation=(Instrumentation)Proxy.newProxyInstance(WorldGlintProbe.class.getClassLoader(),new Class<?>[]{Instrumentation.class},(proxy,method,parameters)->null);
            try {
                for(String version:new String[]{"1.13","1.14.3","1.14.4","1.16.5","1.19.4","1.20.6","1.21.4","1.21.5","1.21.11","26.1.2","26.2","26.3"}) {
                    Path folder=cache.resolve("versions").resolve(version);JsonObject config=new JsonObject();
                    config.addProperty("version",version);config.addProperty("output",configuration.getParent().toString());config.addProperty("client",folder.resolve("client.jar").toString());config.addProperty("fixedGlintMillis",1000);
                    if(Files.exists(folder.resolve("mappings.txt")))config.addProperty("mappings",folder.resolve("mappings.txt").toString());
                    Files.write(configuration,config.toString().getBytes("UTF-8"));WorldInventoryAgent.premain(configuration.toString(),instrumentation);
                    System.out.println(version+" 元clientのclock descriptor/置換数: PASS");
                }
            } finally {Files.delete(configuration);}
        }
    }
}
