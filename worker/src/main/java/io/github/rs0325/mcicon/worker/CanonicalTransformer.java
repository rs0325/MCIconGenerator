package io.github.rs0325.mcicon.worker;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;

/** Fixes calendar selection, render-time clocks/randomness, and atlas frame zero. */
final class CanonicalTransformer implements ClassFileTransformer {
    private static final String CONTEXT="io/github/rs0325/mcicon/worker/CanonicalContext";
    private final Symbols symbols;
    private final boolean legacy;
    private final Map<String,String> clocks=new HashMap<String,String>();
    private final Map<String,String> randomNames=new HashMap<String,String>();
    private final String atlas,freezeMethod,randomOwner;
    volatile boolean animationHook;
    CanonicalTransformer(Symbols symbols,boolean legacy) {
        this.symbols=symbols;this.legacy=legacy;
        String util=legacy?"net.minecraft.util.Utils":"net.minecraft.Util";
        // The unobfuscated 26.x client moved Util into the util package.
        if(!legacy&&symbols.actualClass(util).equals(util))util="net.minecraft.util.Util";
        String owner=symbols.actualClass(util).replace('.','/');
        clocks.put(owner+"#"+symbols.methodName(util,legacy?"getTimeMillis":"getMillis",0),"elapsedMillis");
        clocks.put(owner+"#"+symbols.methodName(util,legacy?"getTimeNanos":"getNanos",0),"nanos");
        clocks.put(owner+"#"+symbols.methodName(util,"getEpochMillis",0),"epochMillis");
        String texture=legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlas";
        atlas=symbols.actualClass(texture).replace('.','/');
        freezeMethod=symbols.methodName(texture,legacy?"tick":"cycleAnimationFrames",0);
        randomOwner=symbols.actualClass("net.minecraft.util.RandomSource").replace('.','/');
        for(String name:Arrays.asList("nextFloat","nextDouble","nextInt","nextLong","nextBoolean")) {
            randomNames.put(symbols.methodName("net.minecraft.util.RandomSource",name,0)+"#"+descriptor(name),name);
        }
        randomNames.put(symbols.methodName("net.minecraft.util.RandomSource","nextInt",1)+"#(I)I","nextInt");
    }
    private static String descriptor(String name) {
        return "()"+(name.equals("nextFloat")?"F":name.equals("nextDouble")?"D":name.equals("nextLong")?"J":name.equals("nextBoolean")?"Z":"I");
    }
    public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
        if(domain==null||domain.getCodeSource()==null||!domain.getCodeSource().getLocation().getPath().endsWith("/client.jar"))return null;
        return transformGameClass(name,bytes);
    }
    byte[] transformGameClass(final String className,byte[] bytes) {
        String source=symbols.sourceClass(className.replace('/','.'));
        final boolean dynamicRandom=source.startsWith("net.minecraft.client.renderer.item.properties.")||source.startsWith("net.minecraft.client.renderer.item.ItemProperties")
                ||source.startsWith("net.minecraft.client.renderer.item.CompassItemPropertyFunction")||source.startsWith("net.minecraft.item.ClockItem")
                ||source.startsWith("net.minecraft.item.CompassItem")||source.startsWith("net.minecraft.world.item.ClockItem")||source.startsWith("net.minecraft.world.item.CompassItem");
        ClassReader reader=new ClassReader(bytes);
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                if(className.equals(atlas)&&name.equals(freezeMethod)&&desc.equals("()V")) {
                    target.visitCode();target.visitInsn(Opcodes.RETURN);target.visitMaxs(0,1);target.visitEnd();
                    animationHook=true;
                    System.err.println("[mcicon] atlas frame-zero hook installed: "+className+"."+name);
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9,target) {
                    public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf) {
                        String clock=clocks.get(owner+"#"+method);
                        if(opcode==Opcodes.INVOKESTATIC&&descriptor.equals("()J")&&clock!=null) {
                            super.visitMethodInsn(opcode,CONTEXT,clock,descriptor,false);return;
                        }
                        if(owner.equals("java/lang/System")&&opcode==Opcodes.INVOKESTATIC&&descriptor.equals("()J")&&(method.equals("nanoTime")||method.equals("currentTimeMillis"))) {
                            super.visitMethodInsn(opcode,CONTEXT,method.equals("nanoTime")?"nanos":"epochMillis",descriptor,false);return;
                        }
                        if(owner.equals("java/lang/Math")&&method.equals("random")&&descriptor.equals("()D")) {
                            super.visitMethodInsn(opcode,CONTEXT,"mathRandom",descriptor,false);return;
                        }
                        if(owner.equals("java/util/Date")&&method.equals("<init>")&&descriptor.equals("()V")) {
                            super.visitLdcInsn(CanonicalContext.EPOCH_MILLIS);
                            super.visitMethodInsn(opcode,owner,method,"(J)V",itf);return;
                        }
                        String randomName=owner.equals(randomOwner)?randomNames.get(method+"#"+descriptor):owner.equals("java/util/Random")?method:null;
                        if(dynamicRandom&&randomName!=null&&((descriptor.equals(descriptor(randomName))&&randomNames.containsValue(randomName))||(randomName.equals("nextInt")&&descriptor.equals("(I)I")))) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTEXT,randomName,"(Ljava/lang/Object;"+descriptor.substring(1),false);return;
                        }
                        super.visitMethodInsn(opcode,owner,method,descriptor,itf);
                        if((owner.equals("java/util/Calendar")||owner.equals("com/ibm/icu/util/Calendar"))&&method.equals("getInstance")&&opcode==Opcodes.INVOKESTATIC) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTEXT,"fixCalendar","(Ljava/lang/Object;)V",false);
                        }
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
}
