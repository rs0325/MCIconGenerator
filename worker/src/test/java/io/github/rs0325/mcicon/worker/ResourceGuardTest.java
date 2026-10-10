package io.github.rs0325.mcicon.worker;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceGuardTest {
    @TempDir Path directory;
    public static class Sprite {
        private final String name;
        public Sprite(String name){this.name=name;}
        public String getName(){return name;}
        public Contents contents(){return new Contents(name);}
    }
    public static class Contents {
        private final String name;
        Contents(String name){this.name=name;}
        public String name(){return name;}
    }
    public static class Manager {
        public Object missingModel=new Object();
        public Object missing=missingModel;
    }
    @AfterEach void cleanup() {CanonicalContext.end();ResourceGuard.end();RenderAgent.symbols=null;RenderAgent.config=null;}
    private Symbols symbols(boolean legacy) throws Exception {
        String sprite=legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite";
        String manager=legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager";
        String quad=legacy?"net.minecraft.client.resource.model.BakedQuad":"net.minecraft.client.renderer.block.model.BakedQuad";
        String mapping=sprite+" -> "+Sprite.class.getName()+":\n"
            +(legacy?"    java.lang.String getName() -> getName\n":"    net.minecraft.client.renderer.texture.SpriteContents contents() -> contents\n")
            +"net.minecraft.client.renderer.texture.SpriteContents -> "+Contents.class.getName()+":\n    java.lang.String name() -> name\n"
            +manager+" -> "+Manager.class.getName()+":\n    java.lang.Object "+(legacy?"missing":"missingModel")+" -> "+(legacy?"missing":"missingModel")+"\n    java.lang.Object getModel(java.lang.Object) -> getModel\n"
            +quad+" -> fixture.Quad:\n    "+sprite+" "+(legacy?"getSprite":"sprite")+"() -> sprite\n"
            +"net.minecraft.client.renderer.item.MissingItemModel -> fixture.Missing:\n    void update(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object,int) -> update\n";
        mapping+=(legacy?"net.minecraft.client.resource.model.BakedModel":"net.minecraft.client.resources.model.BakedModel")+" -> java.lang.Object:\n";
        Path path=directory.resolve("mappings.txt");Files.write(path,mapping.getBytes("UTF-8"));
        RenderAgent.symbols=new Symbols(path);RenderAgent.config=new JsonObject();RenderAgent.config.addProperty("profile",legacy?"legacy-1.13":"gui-deferred");
        return RenderAgent.symbols;
    }
    @Test void fallbackIsScopedToTheRequestedPassAndModelTakesPriority() throws Exception {
        symbols(false);
        ResourceGuard.begin();
        ResourceGuard.sprite(new Sprite("minecraft:missingno"));ResourceGuard.missingModel();ResourceGuard.check();
        CanonicalContext.begin();
        ResourceGuard.sprite(new Sprite("minecraft:block/stone"));ResourceGuard.check();
        ResourceGuard.sprite(new Sprite("minecraft:missingno"));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        Manager manager=new Manager();ResourceGuard.modelManager(manager);ResourceGuard.model(new Object());
        ResourceGuard.model(manager.missingModel);
        assertEquals("MISSING_MODEL",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.end();ResourceGuard.begin();ResourceGuard.check();
    }
    @Test void quadReturnHookPreservesSpriteAndExecutesForLegacyAndRecordGetters() throws Exception {
        for(boolean legacy:new boolean[]{true,false}) {
            Symbols symbols=symbols(legacy);
            byte[] bytes=new ResourceTransformer(symbols,legacy).transformGameClass("fixture/Quad",quadFixture());
            Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
            Sprite sprite=new Sprite("minecraft:missingno");Object quad=type.getConstructor(Sprite.class).newInstance(sprite);
            Method getter=type.getMethod("sprite");ResourceGuard.begin();
            assertSame(sprite,getter.invoke(quad));ResourceGuard.check();
            CanonicalContext.begin();assertSame(sprite,getter.invoke(quad));
            assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
            CanonicalContext.end();ResourceGuard.end();
        }
    }
    @Test void missingItemModelEntryHookProducesModelDiagnostic() throws Exception {
        Symbols symbols=symbols(false);ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Missing",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"update","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V",null,null);
        method.visitCode();method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,7);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,false).transformGameClass("fixture/Missing",writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        CanonicalContext.begin();ResourceGuard.begin();
        type.getMethod("update",Object.class,Object.class,Object.class,Object.class,Object.class,Object.class,int.class).invoke(null,null,null,null,null,null,null,0);
        assertEquals("MISSING_MODEL",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
    }
    @Test void modelReturnHookVerifiesWhenOriginalStackMapDiscardsReceiver() throws Exception {
        Symbols symbols=symbols(false);String owner=Type.getInternalName(Manager.class);ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PUBLIC,"missingModel","Ljava/lang/Object;",null,null).visitEnd();
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);method.visitInsn(Opcodes.RETURN);method.visitMaxs(1,1);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC,"getModel","(Ljava/lang/Object;)Ljava/lang/Object;",null,null);
        Label nonnull=new Label(),end=new Label();method.visitCode();method.visitVarInsn(Opcodes.ALOAD,1);method.visitVarInsn(Opcodes.ASTORE,2);
        method.visitVarInsn(Opcodes.ALOAD,2);method.visitJumpInsn(Opcodes.IFNONNULL,nonnull);method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,owner,"missingModel","Ljava/lang/Object;");method.visitJumpInsn(Opcodes.GOTO,end);
        method.visitLabel(nonnull);method.visitFrame(Opcodes.F_FULL,3,new Object[]{Opcodes.TOP,Opcodes.TOP,"java/lang/Object"},0,new Object[0]);method.visitVarInsn(Opcodes.ALOAD,2);
        method.visitLabel(end);method.visitFrame(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Object"});method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,3);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,false).transformGameClass(owner,writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        Object instance=type.getConstructor().newInstance(),value=new Object();
        assertSame(value,type.getMethod("getModel",Object.class).invoke(instance,value));
        assertNull(type.getMethod("getModel",Object.class).invoke(instance,new Object[]{null}));
    }
    @Test void sameObfuscatedNameWithAnotherReturnTypeIsNotInspected() throws Exception {
        Symbols symbols=symbols(false);ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Quad",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"sprite","()Ljava/lang/String;",null,null);
        method.visitCode();method.visitLdcInsn("unrelated");method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,false).transformGameClass("fixture/Quad",writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        ResourceGuard.begin();CanonicalContext.begin();
        assertEquals("unrelated",type.getMethod("sprite").invoke(null));ResourceGuard.check();
    }
    private byte[] quadFixture() {
        String sprite=Type.getInternalName(Sprite.class);ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Quad",null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PRIVATE,"value","L"+sprite+";",null,null).visitEnd();
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(L"+sprite+";)V",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        method.visitVarInsn(Opcodes.ALOAD,0);method.visitVarInsn(Opcodes.ALOAD,1);method.visitFieldInsn(Opcodes.PUTFIELD,"fixture/Quad","value","L"+sprite+";");method.visitInsn(Opcodes.RETURN);method.visitMaxs(2,2);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC,"sprite","()L"+sprite+";",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,"fixture/Quad","value","L"+sprite+";");method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,1);method.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
}
