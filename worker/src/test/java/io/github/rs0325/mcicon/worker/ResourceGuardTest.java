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
    public static class FieldOnlyQuad {
        public Sprite sprite=new Sprite("minecraft:missingno");
    }
    public static class MissingTexture {
        public static Object value;
        public static Object getTexture(){throw new AssertionError("観測処理で遅延生成getterを呼ばない");}
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
    @Test void consumedQuadDoesNotRequireTheMappedGetterToExistInTheJar() throws Exception {
        for(boolean legacy:new boolean[]{true,false}) {
            symbols(legacy);Path mapping=directory.resolve("mappings.txt");
            String owner=legacy?"net.minecraft.client.resource.model.BakedQuad":"net.minecraft.client.renderer.block.model.BakedQuad";
            Files.write(mapping,(owner+" -> "+FieldOnlyQuad.class.getName()+":\n    java.lang.Object sprite -> sprite\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
            RenderAgent.symbols=new Symbols(mapping);ResourceGuard.begin();CanonicalContext.begin();
            ResourceGuard.quad(new FieldOnlyQuad());
            assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
            ResourceGuard.end();CanonicalContext.end();
        }
    }
    @Test void packedQuadAccessorsPreserveDataAndObserveMissingSprite() throws Exception {
        symbols(false);Path mapping=directory.resolve("mappings.txt");String owner="fixture/PackedQuad",parent=Type.getInternalName(FieldOnlyQuad.class),sprite=Type.getInternalName(Sprite.class);
        Files.write(mapping,("net.minecraft.client.renderer.block.model.BakedQuad -> "+FieldOnlyQuad.class.getName()+":\n"
            +"    net.minecraft.client.renderer.texture.TextureAtlasSprite sprite -> sprite\n"
            +"    org.joml.Vector3fc position(int) -> p\n    long packedUV(int) -> u\n"
            +"org.joml.Vector3fc -> java.lang.Object:\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
        Symbols symbols=new Symbols(mapping);RenderAgent.symbols=symbols;ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,owner,null,parent,null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","(L"+sprite+";)V",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);
        method.visitVarInsn(Opcodes.ALOAD,0);method.visitVarInsn(Opcodes.ALOAD,1);method.visitFieldInsn(Opcodes.PUTFIELD,parent,"sprite","L"+sprite+";");method.visitInsn(Opcodes.RETURN);method.visitMaxs(2,2);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC,"p","(I)Ljava/lang/Object;",null,null);method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD,0);method.visitFieldInsn(Opcodes.GETFIELD,parent,"sprite","L"+sprite+";");method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,2);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC,"u","(I)J",null,null);method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD,1);method.visitInsn(Opcodes.I2L);method.visitLdcInsn(17L);method.visitInsn(Opcodes.LADD);method.visitInsn(Opcodes.LRETURN);method.visitMaxs(4,2);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,false).transformGameClass(parent,writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        Sprite missing=new Sprite("minecraft:missingno"),normal=new Sprite("minecraft:block/stone");
        Object bad=type.getConstructor(Sprite.class).newInstance(missing),healthy=type.getConstructor(Sprite.class).newInstance(normal);
        Method position=type.getMethod("p",int.class),uv=type.getMethod("u",int.class);ResourceGuard.begin();
        assertSame(missing,position.invoke(bad,0));assertEquals(19L,uv.invoke(bad,2));ResourceGuard.check();
        CanonicalContext.begin();assertSame(normal,position.invoke(healthy,0));assertEquals(19L,uv.invoke(healthy,2));ResourceGuard.check();
        assertSame(missing,position.invoke(bad,0));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.end();ResourceGuard.begin();assertEquals(19L,uv.invoke(bad,2));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
    }
    @Test void specialAtlasLookupPreservesTheSpriteAndDetectsFallback() throws Exception {
        Symbols symbols=symbols(false);Path mapping=directory.resolve("mappings.txt");
        Files.write(mapping,("net.minecraft.client.renderer.texture.TextureAtlas -> fixture.Atlas:\n"
            +"    net.minecraft.client.renderer.texture.TextureAtlasSprite getSprite(java.lang.Object) -> getSprite\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
        symbols=new Symbols(mapping);RenderAgent.symbols=symbols;ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Atlas",null,"java/lang/Object",null);
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"getSprite","(Ljava/lang/Object;)L"+Type.getInternalName(Sprite.class)+";",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitTypeInsn(Opcodes.CHECKCAST,Type.getInternalName(Sprite.class));method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,1);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,false).transformGameClass("fixture/Atlas",writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        Method getter=type.getMethod("getSprite",Object.class);Sprite missing=new Sprite("minecraft:missingno");ResourceGuard.begin();
        assertSame(missing,getter.invoke(null,missing));ResourceGuard.check();
        CanonicalContext.begin();Sprite normal=new Sprite("minecraft:entity/chest/normal");
        assertSame(normal,getter.invoke(null,normal));ResourceGuard.check();
        assertSame(missing,getter.invoke(null,missing));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
    }
    @Test void legacyDirectBindDetectsTheCachedFallbackWithoutStoppingTheBind() throws Exception {
        symbols(true);Path mapping=directory.resolve("mappings.txt");
        Files.write(mapping,("net.minecraft.client.render.texture.MissingTextureAtlasSprite -> "+MissingTexture.class.getName()+":\n    java.lang.Object getTexture() -> getTexture\n    java.lang.Object texture -> value\n"
            +"net.minecraft.client.render.texture.Texture -> fixture.Texture:\n    void bind() -> bind\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
        Symbols symbols=new Symbols(mapping);RenderAgent.symbols=symbols;ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/Texture",null,"java/lang/Object",null);
        writer.visitField(Opcodes.ACC_PUBLIC,"bound","Z",null,null).visitEnd();
        MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);method.visitInsn(Opcodes.RETURN);method.visitMaxs(1,1);method.visitEnd();
        method=writer.visitMethod(Opcodes.ACC_PUBLIC,"bind","()V",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitInsn(Opcodes.ICONST_1);method.visitFieldInsn(Opcodes.PUTFIELD,"fixture/Texture","bound","Z");method.visitInsn(Opcodes.RETURN);method.visitMaxs(2,1);method.visitEnd();writer.visitEnd();
        final byte[] bytes=new ResourceTransformer(symbols,true).transformGameClass("fixture/Texture",writer.toByteArray());
        Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        Object fallback=type.getConstructor().newInstance(),normal=type.getConstructor().newInstance();MissingTexture.value=fallback;
        Method bind=type.getMethod("bind");ResourceGuard.begin();bind.invoke(fallback);ResourceGuard.check();
        CanonicalContext.begin();bind.invoke(normal);ResourceGuard.check();bind.invoke(fallback);
        assertTrue(type.getField("bound").getBoolean(fallback));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.end();ResourceGuard.begin();bind.invoke(fallback);
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        MissingTexture.value=null;
    }
    @Test void directFallbackSurvivesPassesAndClearsWhenNormalContentsAreApplied() throws Exception {
        symbols(false);Object missing=new Object(),texture=new Object(),normal=new Object();
        Thread load=new Thread(()->ResourceGuard.missingContents(missing));load.start();load.join();
        ResourceGuard.appliedTexture(texture,missing);ResourceGuard.begin();ResourceGuard.texture(texture);ResourceGuard.check();
        CanonicalContext.begin();ResourceGuard.texture(normal);ResourceGuard.check();ResourceGuard.texture(texture);
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.end();ResourceGuard.begin();ResourceGuard.texture(texture);
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.appliedTexture(texture,new Object());ResourceGuard.end();ResourceGuard.begin();
        ResourceGuard.texture(texture);ResourceGuard.check();
    }
    @Test void directTextureHooksPreserveContentsApplicationAndCachedGpuAccess() throws Exception {
        symbols(false);Path mapping=directory.resolve("mappings.txt");
        Files.write(mapping,("net.minecraft.client.renderer.texture.TextureContents -> fixture.Contents:\n    fixture.Contents createMissing() -> createMissing\n"
            +"net.minecraft.client.renderer.texture.ReloadableTexture -> fixture.Reloadable:\n    void apply(net.minecraft.client.renderer.texture.TextureContents) -> apply\n"
            +"net.minecraft.client.renderer.texture.AbstractTexture -> fixture.Abstract:\n    java.lang.Object getTextureView() -> getTextureView\n"
            +"net.minecraft.client.renderer.texture.TextureManager -> fixture.Textures:\n    fixture.Reloadable getTexture(fixture.Reloadable) -> getTexture\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
        Symbols symbols=new Symbols(mapping);RenderAgent.symbols=symbols;
        class Loader extends ClassLoader {Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}}
        Loader loader=new Loader();Class<?> contents=null,texture=null,textures=null;
        for(String owner:new String[]{"fixture/Abstract","fixture/Contents","fixture/Reloadable","fixture/Textures"}) {
            ClassWriter writer=new ClassWriter(0);String parent=owner.equals("fixture/Reloadable")?"fixture/Abstract":"java/lang/Object";
            writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,owner,null,parent,null);
            MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
            method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>","()V",false);
            method.visitInsn(Opcodes.RETURN);method.visitMaxs(1,1);method.visitEnd();
            if(owner.equals("fixture/Contents")) {
                method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"createMissing","()Lfixture/Contents;",null,null);
                method.visitCode();method.visitTypeInsn(Opcodes.NEW,owner);method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL,owner,"<init>","()V",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(2,0);method.visitEnd();
            } else if(owner.equals("fixture/Reloadable")) {
                writer.visitField(Opcodes.ACC_PUBLIC,"applied","Ljava/lang/Object;",null,null).visitEnd();
                method=writer.visitMethod(Opcodes.ACC_PUBLIC,"apply","(Lfixture/Contents;)V",null,null);
                method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitVarInsn(Opcodes.ALOAD,1);
                method.visitFieldInsn(Opcodes.PUTFIELD,owner,"applied","Ljava/lang/Object;");method.visitInsn(Opcodes.RETURN);method.visitMaxs(2,2);method.visitEnd();
            } else {
                boolean manager=owner.equals("fixture/Textures");
                method=writer.visitMethod(Opcodes.ACC_PUBLIC|(manager?Opcodes.ACC_STATIC:0),manager?"getTexture":"getTextureView",manager?"(Lfixture/Reloadable;)Lfixture/Reloadable;":"()Ljava/lang/Object;",null,null);
                method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,1);method.visitEnd();
            }
            writer.visitEnd();Class<?> type=loader.define(new ResourceTransformer(symbols,false).transformGameClass(owner,writer.toByteArray()));
            if(owner.equals("fixture/Contents"))contents=type;else if(owner.equals("fixture/Reloadable"))texture=type;else if(owner.equals("fixture/Textures"))textures=type;
        }
        Object fallback=contents.getMethod("createMissing").invoke(null),instance=texture.getConstructor().newInstance();
        texture.getMethod("apply",contents).invoke(instance,fallback);assertSame(fallback,texture.getField("applied").get(instance));
        Method lookup=textures.getMethod("getTexture",texture);ResourceGuard.begin();assertSame(instance,lookup.invoke(null,instance));ResourceGuard.check();
        CanonicalContext.begin();assertSame(instance,lookup.invoke(null,instance));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        ResourceGuard.end();ResourceGuard.begin();assertSame(instance,texture.getMethod("getTextureView").invoke(instance));
        assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
        Object healthy=contents.getConstructor().newInstance();texture.getMethod("apply",contents).invoke(instance,healthy);
        ResourceGuard.end();ResourceGuard.begin();assertSame(instance,lookup.invoke(null,instance));ResourceGuard.check();
    }
    @Test void fallbackGetterIsObservedAfterVanillaReturnsTheRetainedTexture() throws Exception {
        for(boolean legacy:new boolean[]{true,false}) {
            symbols(legacy);Path mapping=directory.resolve("mappings.txt");
            String owner=legacy?"net.minecraft.client.render.texture.MissingTextureAtlasSprite":"net.minecraft.client.renderer.texture.MissingTextureAtlasSprite";
            Files.write(mapping,(owner+" -> "+MissingTexture.class.getName()+":\n    java.lang.Object "+(legacy?"texture":"missingTexture")+" -> value\n    java.lang.Object getTexture() -> getTexture\n").getBytes("UTF-8"),StandardOpenOption.APPEND);
            Symbols symbols=new Symbols(mapping);RenderAgent.symbols=symbols;ClassWriter writer=new ClassWriter(0);
            writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"fixture/FallbackGetter",null,"java/lang/Object",null);
            MethodVisitor method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"getTexture","()Ljava/lang/Object;",null,null);
            method.visitCode();method.visitFieldInsn(Opcodes.GETSTATIC,Type.getInternalName(MissingTexture.class),"value","Ljava/lang/Object;");
            method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();
            final byte[] bytes=new ResourceTransformer(symbols,legacy).transformGameClass(Type.getInternalName(MissingTexture.class),writer.toByteArray());
            Class<?> type=new ClassLoader(){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
            Object fallback=new Object();MissingTexture.value=fallback;Method getter=type.getMethod("getTexture");
            ResourceGuard.begin();assertSame(fallback,getter.invoke(null));ResourceGuard.check();CanonicalContext.begin();
            assertSame(fallback,getter.invoke(null));
            assertEquals("MISSING_TEXTURE",assertThrows(ResourceGuard.MissingResourceException.class,ResourceGuard::check).code);
            CanonicalContext.end();ResourceGuard.end();MissingTexture.value=null;
        }
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
