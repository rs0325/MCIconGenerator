package io.github.rs0325.mcicon.qa;

import io.github.rs0325.mcicon.worker.RenderAgent;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** ゲーム起動前にQA専用の破損リソースパックを選択する。描画・欠損観測は変更しない。 */
public final class ResourcePackFaultAgent {
    private static String resource;
    private static boolean reported;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        Path game=Paths.get(argument).getParent().resolve("game");
        Path packs=game.resolve("resourcepacks");Files.createDirectories(packs);
        try(InputStream input=ResourcePackFaultAgent.class.getResourceAsStream("/mcicon-resource-pack.zip")) {
            Files.copy(input,packs.resolve("mcicon-qa.zip"));
        }
        try(InputStream input=ResourcePackFaultAgent.class.getResourceAsStream("/mcicon-resource-pack-options.txt")) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
            while((count=input.read(buffer))!=-1)out.write(buffer,0,count);
            Files.write(game.resolve("options.txt"),out.toByteArray(),StandardOpenOption.APPEND);
        }
        System.err.println("[mcicon QA] 破損リソースパックを配置: mcicon-qa.zip");
        try(InputStream input=ResourcePackFaultAgent.class.getResourceAsStream("/mcicon-resource-path.txt")) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();int value;
            while((value=input.read())!=-1)out.write(value);
            resource=new String(out.toByteArray(),"UTF-8");
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                if(!name.equals("io/github/rs0325/mcicon/worker/Bridge"))return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                        if(!name.equals("renderTransparent"))return target;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitCode(){super.visitCode();super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/ResourcePackFaultAgent","reportPack","()V",false);}
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
        RenderAgent.premain(argument,instrumentation);
    }
    public static void reportPack() {
        if(reported)return;reported=true;
        try {
            Symbols s=RenderAgent.symbols;boolean legacy=RenderAgent.config.get("profile").getAsString().startsWith("legacy-");
            String identifier=legacy?"net.minecraft.resource.Identifier":RenderAgent.config.get("version").getAsString().startsWith("26.")?"net.minecraft.resources.Identifier":"net.minecraft.resources.ResourceLocation";
            Object id=s.hasMethod(identifier,"parse",1)||identifier.equals("net.minecraft.resources.Identifier")?s.call(identifier,"parse",null,resource):s.construct(identifier,resource);
            Object minecraft=s.call("net.minecraft.client.Minecraft","getInstance",null);
            Object manager=s.call("net.minecraft.client.Minecraft","getResourceManager",minecraft);
            Object value=s.call(legacy?"net.minecraft.resource.manager.ResourceManager":"net.minecraft.server.packs.resources.ResourceManager","getResource",manager,id);
            if(value instanceof java.util.Optional)value=((java.util.Optional<?>)value).get();
            try {
                String owner=legacy?"net.minecraft.resource.Resource":"net.minecraft.server.packs.resources.Resource";
                String method=legacy||s.hasMethod(owner,"getSourceName",0)?"getSourceName":"sourcePackId";
                Object pack=s.call(owner,method,value);
                System.err.println("[mcicon QA] resource source: "+resource+" = "+pack);
            } finally {if(value instanceof AutoCloseable)((AutoCloseable)value).close();}
        } catch(Exception e) {throw new IllegalStateException("QAリソースの提供パックを確認できません",e);}
    }
}
