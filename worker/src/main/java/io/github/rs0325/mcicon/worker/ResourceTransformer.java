package io.github.rs0325.mcicon.worker;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Observe fallback models and sprites consumed by vanilla quad rendering. */
final class ResourceTransformer implements ClassFileTransformer {
    private static final String GUARD="io/github/rs0325/mcicon/worker/ResourceGuard";
    private final Symbols symbols;
    private final boolean legacy;
    ResourceTransformer(Symbols symbols,boolean legacy) {this.symbols=symbols;this.legacy=legacy;}
    public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
        if(domain==null||domain.getCodeSource()==null||!domain.getCodeSource().getLocation().getPath().endsWith("/client.jar"))return null;
        return transformGameClass(name,bytes);
    }
    byte[] transformGameClass(String name,byte[] bytes) {
        final String source=symbols.sourceClass(name.replace('/','.'));
        final boolean quad=source.equals(legacy?"net.minecraft.client.resource.model.BakedQuad":"net.minecraft.client.renderer.block.model.BakedQuad")
            ||source.equals("net.minecraft.client.resources.model.geometry.BakedQuad$MaterialInfo");
        final boolean manager=source.equals(legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager")
            &&(legacy||symbols.hasField(source,"missingModel"));
        final boolean missing=source.equals("net.minecraft.client.renderer.item.MissingItemModel");
        final boolean geometry=source.equals("net.minecraft.client.resources.model.geometry.BakedQuad");
        final boolean itemRenderer=source.equals(legacy?"net.minecraft.client.render.entity.ItemRenderer":"net.minecraft.client.renderer.entity.ItemRenderer")
            &&(legacy||symbols.hasMethod(source,"getModel",3)||symbols.hasMethod(source,"getModel",4));
        if(!quad&&!manager&&!missing&&!geometry&&!itemRenderer)return null;
        final String spriteMethod=symbols.methodName(source,symbols.hasMethod(source,"getSprite",0)||legacy?"getSprite":"sprite",0);
        final String modelMethod=symbols.methodName(source,"getModel",1);
        final String updateMethod=symbols.methodName(source,"update",7);
        final String verticesMethod=symbols.methodName(source,symbols.hasMethod(source,"getVertices",0)||legacy?"getVertices":"vertices",0);
        final String modelDescriptor="L"+symbols.actualClass(legacy?"net.minecraft.client.resource.model.BakedModel":"net.minecraft.client.resources.model.BakedModel").replace('.','/')+";";
        final String spriteDescriptor="L"+symbols.actualClass(legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite").replace('.','/')+";";
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                MethodVisitor target=super.visitMethod(access,method,desc,signature,exceptions);
                final boolean inspectSprite=quad&&method.equals(spriteMethod)&&desc.equals("()"+spriteDescriptor);
                final boolean inspectModel=manager&&method.equals(modelMethod)&&Type.getArgumentTypes(desc).length==1&&Type.getReturnType(desc).getDescriptor().equals(modelDescriptor);
                final boolean inspectMissing=missing&&method.equals(updateMethod)&&Type.getArgumentTypes(desc).length==7&&desc.endsWith(")V");
                final boolean inspectVertices=quad&&method.equals(verticesMethod)&&desc.equals("()[I");
                final boolean inspectMaterial=geometry&&method.equals("materialInfo")&&desc.startsWith("()L");
                final boolean inspectItem=itemRenderer&&method.equals(symbols.methodName(source,"getModel",Type.getArgumentTypes(desc).length))&&Type.getReturnType(desc).getDescriptor().equals(modelDescriptor);
                if(!inspectSprite&&!inspectModel&&!inspectMissing&&!inspectVertices&&!inspectMaterial&&!inspectItem)return target;
                return new MethodVisitor(Opcodes.ASM9,target) {
                    public void visitCode() {
                        super.visitCode();
                        if(inspectMissing)super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"missingModel","()V",false);
                        if(inspectModel) {
                            // Original stack maps may discard local 0 at a return.
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"modelManager","(Ljava/lang/Object;)V",false);
                        }
                        if(inspectVertices) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"quad","(Ljava/lang/Object;)V",false);
                        }
                    }
                    public void visitInsn(int opcode) {
                        if(opcode==Opcodes.ARETURN&&(inspectSprite||inspectModel||inspectMaterial||inspectItem)) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,inspectModel?"model":inspectItem?"itemModel":inspectMaterial?"material":"sprite","(Ljava/lang/Object;)V",false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
}
