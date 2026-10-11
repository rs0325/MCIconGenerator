package io.github.rs0325.mcicon.worker;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Records vanilla fallback resources only inside a requested inventory pass. */
public final class ResourceGuard {
    private static final ThreadLocal<String> failure=new ThreadLocal<String>();
    private static final ThreadLocal<Object> manager=new ThreadLocal<Object>();
    private static final Set<Object> missingContents=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object,Boolean>()));
    private static final Set<Object> missingTextures=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object,Boolean>()));
    private ResourceGuard() {}
    static void begin() {failure.remove();manager.remove();}
    static void end() {failure.remove();manager.remove();}
    public static void missingModel() {
        if(CanonicalContext.active())failure.set("MISSING_MODEL");
    }
    public static void modelManager(Object value) {if(CanonicalContext.active())manager.set(value);}
    public static void model(Object value) {
        if(!CanonicalContext.active())return;
        inspectModel(value,manager.get());
    }
    public static void itemModel(Object value) {
        if(!CanonicalContext.active())return;
        try {
            Symbols s=RenderAgent.symbols;
            Object minecraft=s.call("net.minecraft.client.Minecraft","getInstance",null);
            inspectModel(value,s.call("net.minecraft.client.Minecraft","getModelManager",minecraft));
        } catch(Exception e) {throw new IllegalStateException("Cannot inspect selected vanilla item model",e);}
    }
    private static void inspectModel(Object value,Object selectedManager) {
        try {
            Symbols s=RenderAgent.symbols;
            boolean legacy=RenderAgent.config.get("profile").getAsString().startsWith("legacy-");
            String owner=legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager";
            Object missing=s.field(owner,legacy?"missing":"missingModel",selectedManager);
            if(value==missing)missingModel();
        } catch(Exception e) {throw new IllegalStateException("Cannot inspect resolved vanilla model",e);}
    }
    public static void sprite(Object value) {
        if(!CanonicalContext.active()||value==null)return;
        try {
            Symbols s=RenderAgent.symbols;
            boolean legacy=RenderAgent.config.get("profile").getAsString().startsWith("legacy-");
            String owner=legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite";
            Object name;
            if(legacy||s.hasMethod(owner,"getName",0))name=s.call(owner,"getName",value);
            else name=s.call("net.minecraft.client.renderer.texture.SpriteContents","name",s.call(owner,"contents",value));
            if("minecraft:missingno".equals(String.valueOf(name))&&failure.get()==null)failure.set("MISSING_TEXTURE");
        } catch(Exception e) {throw new IllegalStateException("Cannot inspect drawn vanilla sprite",e);}
    }
    /** バニラが生成した代替内容を追跡する。reload用スレッドからも呼ばれる。 */
    public static void missingContents(Object value) {missingContents.add(value);}
    /** 適用内容を記録し、正常なreloadでは以前の欠損状態を解除する。 */
    public static void appliedTexture(Object texture,Object contents) {
        if(missingContents.contains(contents))missingTextures.add(texture);else missingTextures.remove(texture);
    }
    /** 描画pass内で取得・bindしたテクスチャの代替状態を検査する。 */
    public static void texture(Object value) {
        if(!CanonicalContext.active())return;
        try {
            Symbols s=RenderAgent.symbols;
            boolean missing=missingTextures.contains(value);
            if(RenderAgent.config.get("profile").getAsString().startsWith("legacy-"))missing=value==s.field("net.minecraft.client.render.texture.MissingTextureAtlasSprite","texture",null);
            else if(s.hasField("net.minecraft.client.renderer.texture.MissingTextureAtlasSprite","missingTexture"))missing=value==s.field("net.minecraft.client.renderer.texture.MissingTextureAtlasSprite","missingTexture",null);
            if(missing&&failure.get()==null)failure.set("MISSING_TEXTURE");
        } catch(Exception e) {throw new IllegalStateException("バニラのbind済みテクスチャを検査できません",e);}
    }
    public static void quad(Object value) {
        if(!CanonicalContext.active())return;
        try {
            Symbols s=RenderAgent.symbols;
            boolean legacy=RenderAgent.config.get("profile").getAsString().startsWith("legacy-");
            String owner=legacy?"net.minecraft.client.resource.model.BakedQuad":"net.minecraft.client.renderer.block.model.BakedQuad";
            // Official mappings include getters removed from some release JARs.
            sprite(s.field(owner,"sprite",value));
        } catch(Exception e) {throw new IllegalStateException("Cannot inspect consumed vanilla quad",e);}
    }
    public static void material(Object value) {
        if(!CanonicalContext.active())return;
        try {sprite(RenderAgent.symbols.call("net.minecraft.client.resources.model.geometry.BakedQuad$MaterialInfo","sprite",value));}
        catch(Exception e) {throw new IllegalStateException("Cannot inspect consumed vanilla material",e);}
    }
    static void check() throws MissingResourceException {
        if(failure.get()!=null)throw new MissingResourceException(failure.get());
    }
    static final class MissingResourceException extends Exception {
        final String code;
        MissingResourceException(String code) {super("Vanilla selected a fallback resource during inventory rendering");this.code=code;}
    }
}
