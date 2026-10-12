package io.github.rs0325.mcicon.worker;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** インベントリ描画のモデル・atlas sprite・旧版の直接bindを観測する。 */
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
        final boolean atlas=source.equals(legacy?"net.minecraft.client.render.texture.TextureAtlas":"net.minecraft.client.renderer.texture.TextureAtlas");
        final boolean atlasManager=source.equals("net.minecraft.client.resources.model.sprite.AtlasManager");
        final boolean texture=source.equals(legacy?"net.minecraft.client.render.texture.Texture":"net.minecraft.client.renderer.texture.AbstractTexture");
        final boolean textureManager=!legacy&&source.equals("net.minecraft.client.renderer.texture.TextureManager");
        final boolean contents=source.equals("net.minecraft.client.renderer.texture.TextureContents");
        final boolean reloadable=source.equals("net.minecraft.client.renderer.texture.ReloadableTexture");
        final boolean missingTexture=source.equals(legacy?"net.minecraft.client.render.texture.MissingTextureAtlasSprite":"net.minecraft.client.renderer.texture.MissingTextureAtlasSprite");
        if(!quad&&!manager&&!missing&&!geometry&&!itemRenderer&&!atlas&&!atlasManager&&!texture&&!textureManager&&!contents&&!reloadable&&!missingTexture)return null;
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
                final boolean inspectQuadData=quad&&(method.equals(verticesMethod)&&desc.equals("()[I")
                    ||method.equals(symbols.methodName(source,"position",1))&&desc.equals("(I)L"+symbols.actualClass("org.joml.Vector3fc").replace('.','/')+";")
                    ||method.equals(symbols.methodName(source,"packedUV",1))&&desc.equals("(I)J"));
                final boolean inspectMaterial=geometry&&method.equals("materialInfo")&&desc.startsWith("()L");
                final boolean inspectItem=itemRenderer&&method.equals(symbols.methodName(source,"getModel",Type.getArgumentTypes(desc).length))&&Type.getReturnType(desc).getDescriptor().equals(modelDescriptor);
                final boolean inspectAtlas=(atlas||atlasManager)&&method.equals(symbols.methodName(source,atlas?"getSprite":"get",1))&&Type.getArgumentTypes(desc).length==1&&Type.getReturnType(desc).getDescriptor().equals(spriteDescriptor);
                final boolean inspectTexture=texture&&Type.getArgumentTypes(desc).length==0&&(method.equals(symbols.methodName(source,"bind",0))&&desc.equals("()V")
                    ||!legacy&&(method.equals(symbols.methodName(source,"getId",0))&&desc.equals("()I")
                    ||method.equals(symbols.methodName(source,"getTexture",0))&&Type.getReturnType(desc).getSort()==Type.OBJECT
                    ||method.equals(symbols.methodName(source,"getTextureView",0))&&Type.getReturnType(desc).getSort()==Type.OBJECT));
                final boolean inspectTextureReturn=(textureManager&&method.equals(symbols.methodName(source,"getTexture",1))&&Type.getArgumentTypes(desc).length==1
                    ||missingTexture&&method.equals(symbols.methodName(source,"getTexture",0))&&Type.getArgumentTypes(desc).length==0)&&Type.getReturnType(desc).getSort()==Type.OBJECT;
                final boolean inspectContents=contents&&method.equals(symbols.methodName(source,"createMissing",0))&&desc.equals("()L"+name+";");
                final boolean inspectApply=reloadable&&method.equals(symbols.methodName(source,"apply",1))&&desc.equals("(L"+symbols.actualClass("net.minecraft.client.renderer.texture.TextureContents").replace('.','/')+";)V");
                if(!inspectSprite&&!inspectModel&&!inspectMissing&&!inspectQuadData&&!inspectMaterial&&!inspectItem&&!inspectAtlas&&!inspectTexture&&!inspectTextureReturn&&!inspectContents&&!inspectApply)return target;
                return new MethodVisitor(Opcodes.ASM9,target) {
                    public void visitCode() {
                        super.visitCode();
                        if(inspectMissing)super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"missingModel","()V",false);
                        if(inspectModel) {
                            // Original stack maps may discard local 0 at a return.
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"modelManager","(Ljava/lang/Object;)V",false);
                        }
                        if(inspectQuadData) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"quad","(Ljava/lang/Object;)V",false);
                        }
                        if(inspectApply) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitVarInsn(Opcodes.ALOAD,1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"appliedTexture","(Ljava/lang/Object;Ljava/lang/Object;)V",false);
                        }
                        if(inspectTexture) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,"texture","(Ljava/lang/Object;)V",false);
                        }
                    }
                    public void visitInsn(int opcode) {
                        if(opcode==Opcodes.ARETURN&&(inspectSprite||inspectModel||inspectMaterial||inspectItem||inspectAtlas||inspectTextureReturn||inspectContents)) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,GUARD,inspectModel?"model":inspectItem?"itemModel":inspectMaterial?"material":inspectTextureReturn?"texture":inspectContents?"missingContents":"sprite","(Ljava/lang/Object;)V",false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
}
