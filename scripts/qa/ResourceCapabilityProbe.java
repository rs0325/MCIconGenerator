package io.github.rs0325.mcicon.qa;

import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Inspect actual release bytecode, including members removed after mapping generation. */
public final class ResourceCapabilityProbe {
    private static class Members {
        final Map<String,String> fields=new HashMap<>();
        final Set<String> methods=new HashSet<>();
    }
    public static void main(String[] args) throws Exception {
        boolean legacy=Boolean.parseBoolean(args[3]);Symbols s=new Symbols(args[2].equals("-")?null:Path.of(args[2]));
        List<String> failures=new ArrayList<>();int checked=0;boolean removedGetter=false;
        try(ZipFile jar=new ZipFile(args[1])) {
            String sprite=legacy?"net.minecraft.client.render.texture.TextureAtlasSprite":"net.minecraft.client.renderer.texture.TextureAtlasSprite";
            Members sm=members(jar,s,sprite);
            if(legacy||s.hasMethod(sprite,"getName",0))requireMethod(sm,s.methodName(sprite,"getName",0),0,"getName",failures);
            else {
                requireMethod(sm,s.methodName(sprite,"contents",0),0,"contents",failures);
                requireMethod(members(jar,s,"net.minecraft.client.renderer.texture.SpriteContents"),s.methodName("net.minecraft.client.renderer.texture.SpriteContents","name",0),0,"SpriteContents.name",failures);
            }
            checked++;
            String atlas=legacy?"net.minecraft.client.render.texture.TextureAtlas":"net.minecraft.client.renderer.texture.TextureAtlas";
            Members am=members(jar,s,atlas);
            if(!am.methods.stream().anyMatch(m->m.startsWith(s.methodName(atlas,"getSprite",1)+"(")&&Type.getArgumentTypes(m.substring(m.indexOf('('))).length==1&&Type.getReturnType(m.substring(m.indexOf('('))).getDescriptor().equals(descriptor(s,sprite))))failures.add("特殊描画のatlas sprite取得メソッドがありません");
            checked++;
            String atlasManager="net.minecraft.client.resources.model.sprite.AtlasManager";
            if(jar.getEntry(s.actualClass(atlasManager).replace('.','/')+".class")!=null) {
                requireMethod(members(jar,s,atlasManager),s.methodName(atlasManager,"get",1),1,"AtlasManager.get",failures);checked++;
            }
            if(legacy) {
                String texture="net.minecraft.client.render.texture.Texture";
                requireMethod(members(jar,s,texture),s.methodName(texture,"bind",0),0,"Texture.bind",failures);
                String missingTexture="net.minecraft.client.render.texture.MissingTextureAtlasSprite";
                if(!members(jar,s,missingTexture).fields.containsKey(s.fieldName(missingTexture,"texture")))failures.add("旧版missing textureの保持フィールドがありません");
                checked+=2;
            }
            String quad=legacy?"net.minecraft.client.resource.model.BakedQuad":"net.minecraft.client.renderer.block.model.BakedQuad";
            if(jar.getEntry(s.actualClass(quad).replace('.','/')+".class")!=null) {
                Members qm=members(jar,s,quad);String field=s.fieldName(quad,"sprite");
                if(!descriptor(s,sprite).equals(qm.fields.get(field)))failures.add("Missing quad sprite field or wrong descriptor");
                if(legacy||s.hasMethod(quad,"getSprite",0))removedGetter=!qm.methods.contains(s.methodName(quad,"getSprite",0)+"()"+descriptor(s,sprite));
                checked++;
            } else {
                String material="net.minecraft.client.resources.model.geometry.BakedQuad$MaterialInfo";
                requireMethod(members(jar,s,material),s.methodName(material,"sprite",0),0,"MaterialInfo.sprite",failures);checked++;
            }
            String item=legacy?"net.minecraft.client.render.entity.ItemRenderer":"net.minecraft.client.renderer.entity.ItemRenderer";
            boolean old=legacy||s.hasMethod(item,"getModel",3)||s.hasMethod(item,"getModel",4);
            if(old) {
                Members minecraft=members(jar,s,"net.minecraft.client.Minecraft");
                requireMethod(minecraft,s.methodName("net.minecraft.client.Minecraft","getInstance",0),0,"Minecraft.getInstance",failures);
                requireMethod(minecraft,s.methodName("net.minecraft.client.Minecraft","getModelManager",0),0,"Minecraft.getModelManager",failures);
                String manager=legacy?"net.minecraft.client.resource.model.ModelManager":"net.minecraft.client.resources.model.ModelManager";
                if(!members(jar,s,manager).fields.containsKey(s.fieldName(manager,legacy?"missing":"missingModel")))failures.add("Missing fallback model field");
                checked+=2;
            }
            String missing="net.minecraft.client.renderer.item.MissingItemModel";
            if(jar.getEntry(s.actualClass(missing).replace('.','/')+".class")!=null) {
                requireMethod(members(jar,s,missing),s.methodName(missing,"update",7),7,"MissingItemModel.update",failures);checked++;
            }
        }
        System.out.println(new Gson().toJson(Map.of("version",args[0],"checkedClasses",checked,"mappedQuadGetterRemoved",removedGetter,"failures",failures)));
        if(!failures.isEmpty())System.exit(1);
    }
    private static String descriptor(Symbols s,String name){return "L"+s.actualClass(name).replace('.','/')+";";}
    private static Members members(ZipFile jar,Symbols s,String name) throws Exception {
        ZipEntry entry=jar.getEntry(s.actualClass(name).replace('.','/')+".class");
        if(entry==null)throw new java.io.IOException("Required class missing: "+name);
        Members result=new Members();
        try(java.io.InputStream input=jar.getInputStream(entry)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                public FieldVisitor visitField(int access,String name,String desc,String signature,Object value){result.fields.put(name,desc);return null;}
                public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){result.methods.add(name+desc);return null;}
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return result;
    }
    private static void requireMethod(Members members,String name,int arity,String label,List<String> failures) {
        boolean found=members.methods.stream().anyMatch(m->m.startsWith(name+"(")&&Type.getArgumentTypes(m.substring(name.length())).length==arity);
        if(!found)failures.add("Missing method: "+label+"/"+arity);
    }
}
