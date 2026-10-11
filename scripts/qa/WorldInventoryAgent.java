package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** 1.21.4のワールド・画面操作と撮影のみを行う。描画・固定化処理は呼ばない。 */
public final class WorldInventoryAgent {
    private static final String MC="net.minecraft.client.Minecraft";
    private static final String CREATE="net.minecraft.client.gui.screens.worldselection.CreateWorldScreen";
    private static final String STATE="net.minecraft.client.gui.screens.worldselection.WorldCreationUiState";
    private static final String INVENTORY="net.minecraft.world.entity.player.Inventory";
    private static Symbols s;
    private static JsonObject config,report=new JsonObject();
    private static JsonArray captures=new JsonArray();
    private static Object minecraft;
    private static Path output;
    private static int stage,frames,index=-1;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),"UTF-8")).getAsJsonObject();
        s=new Symbols(Paths.get(config.get("mappings").getAsString()));
        output=Paths.get(config.get("output").getAsString());
        report.addProperty("version","1.21.4");report.addProperty("productionRendererUsed",false);
        report.addProperty("canonicalHooksUsed",false);report.addProperty("renderMethodsTransformed",false);
        report.add("captures",captures);
        final String owner=s.actualClass(MC).replace('.','/'),method=s.methodName(MC,"runTick",1);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                if(!name.equals(owner))return null;
                ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                        if(!name.equals(method)||!desc.equals("(Z)V"))return target;
                        return new MethodVisitor(Opcodes.ASM9,target) {
                            public void visitInsn(int opcode) {
                                if(opcode==Opcodes.RETURN)super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/WorldInventoryAgent","frame","()V",false);
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);
                System.err.println("[mcicon QA world] callback only: "+owner+"."+method+"(Z)V");
                return writer.toByteArray();
            }
        });
    }
    public static void frame() {
        try {
            if(minecraft==null)minecraft=s.call(MC,"getInstance",null);
            Object screen=s.field(MC,"screen",minecraft);
            if(stage==0) {
                if(!Boolean.TRUE.equals(s.call(MC,"isGameLoadFinished",minecraft))||s.call(MC,"getOverlay",minecraft)!=null||!s.type("net.minecraft.client.gui.screens.TitleScreen").isInstance(screen))return;
                if(++frames<60)return;
                s.call(CREATE,"openFresh",null,minecraft,screen);stage=1;frames=0;return;
            }
            if(stage==1) {
                if(!s.type(CREATE).isInstance(screen))return;
                Object state=s.call(CREATE,"getUiState",screen);
                s.call(STATE,"setName",state,"MCIcon QA");s.call(STATE,"setSeed",state,"0");
                s.call(STATE,"setAllowCommands",state,true);
                s.call(STATE,"setDifficulty",state,s.field("net.minecraft.world.Difficulty","PEACEFUL",null));
                Object flat=s.field("net.minecraft.world.level.levelgen.presets.WorldPresets","FLAT",null);
                boolean selected=false;
                for(Object entry:(Iterable<?>)s.call(STATE,"getNormalPresetList",state)) {
                    Object holder=s.call(STATE+"$WorldTypeEntry","preset",entry);
                    Optional<?> key=(Optional<?>)s.call("net.minecraft.core.Holder","unwrapKey",holder);
                    if(key.isPresent()&&key.get().equals(flat)){s.call(STATE,"setWorldType",state,entry);selected=true;break;}
                }
                if(!selected)throw new IllegalStateException("flat presetが見つかりません");
                s.call(CREATE,"onCreate",screen);stage=2;frames=0;return;
            }
            Object player=s.field(MC,"player",minecraft),level=s.field(MC,"level",minecraft);
            if(stage==2) {
                if(player==null||level==null||screen!=null||s.call(MC,"getOverlay",minecraft)!=null)return;
                if(++frames<60)return;
                Object mouse=s.field(MC,"mouseHandler",minecraft);
                s.setField("net.minecraft.client.MouseHandler","xpos",mouse,0.0);
                s.setField("net.minecraft.client.MouseHandler","ypos",mouse,0.0);
                s.call(MC,"setScreen",minecraft,s.construct("net.minecraft.client.gui.screens.inventory.InventoryScreen",player));
                stage=3;frames=0;return;
            }
            if(stage!=3)return;
            if(!s.type("net.minecraft.client.gui.screens.inventory.InventoryScreen").isInstance(screen))throw new IllegalStateException("inventory画面が閉じられました");
            if(++frames<30)return;
            Object inventory=s.call("net.minecraft.world.entity.player.Player","getInventory",player);
            Object stack=s.call(INVENTORY,"getItem",inventory,9);
            if(index>=0) {
                String actual=String.valueOf(s.call("net.minecraft.core.Registry","getKey",s.field("net.minecraft.core.registries.BuiltInRegistries","ITEM",null),s.call("net.minecraft.world.item.ItemStack","getItem",stack)));
                if(!actual.equals(config.getAsJsonArray("ids").get(index).getAsString()))throw new IllegalStateException("スロットのアイテムが不一致: "+actual);
            }
            String id=index<0?"empty":config.getAsJsonArray("ids").get(index).getAsString();
            Object renderer=s.field(MC,"gameRenderer",minecraft);
            Object light=s.call("net.minecraft.client.renderer.GameRenderer","lightTexture",renderer);
            Object lightImage=s.call("net.minecraft.client.Screenshot","takeScreenshot",null,s.field("net.minecraft.client.renderer.LightTexture","target",light));
            int fullBright;
            try{fullBright=((Number)s.call("com.mojang.blaze3d.platform.NativeImage","getPixel",lightImage,15,0)).intValue();}
            finally{s.call("com.mojang.blaze3d.platform.NativeImage","close",lightImage);}
            Object target=s.call(MC,"getMainRenderTarget",minecraft);
            Object image=s.call("net.minecraft.client.Screenshot","takeScreenshot",null,target);
            Path path=output.resolve(id.replace(':','_')+"-screen.png");
            try{s.call("com.mojang.blaze3d.platform.NativeImage","writeToFile",image,path);}finally{s.call("com.mojang.blaze3d.platform.NativeImage","close",image);}
            JsonObject capture=new JsonObject();capture.addProperty("id",id);capture.addProperty("screen",path.getFileName().toString());
            int left=((Number)s.field("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen","leftPos",screen)).intValue();
            int top=((Number)s.field("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen","topPos",screen)).intValue();
            capture.addProperty("xGui",left+8);capture.addProperty("yGui",top+84);
            capture.addProperty("count",((Number)s.call("net.minecraft.world.item.ItemStack","getCount",stack)).intValue());
            capture.addProperty("fullBrightArgb",fullBright);
            capture.addProperty("gameTime",((Number)s.call("net.minecraft.world.level.Level","getGameTime",level)).longValue());
            captures.add(capture);writeReport();System.err.println("[mcicon QA world] captured "+id);
            index++;frames=0;
            if(index==config.getAsJsonArray("ids").size()) {report.addProperty("completed",true);writeReport();stage=4;s.call(MC,"stop",minecraft);return;}
            final Object next=stack(config.getAsJsonArray("ids").get(index).getAsString());
            s.call(INVENTORY,"setItem",inventory,9,next);
            // server側も同じ通常stackにし、同期によるスロットの巻き戻りを防ぐ。
            final UUID uuid=(UUID)s.call("net.minecraft.world.entity.Entity","getUUID",player);
            final Object server=s.call(MC,"getSingleplayerServer",minecraft);
            s.call("net.minecraft.util.thread.BlockableEventLoop","execute",server,new Runnable(){public void run(){try {
                Object list=s.call("net.minecraft.server.MinecraftServer","getPlayerList",server);
                Object serverPlayer=s.call("net.minecraft.server.players.PlayerList","getPlayer",list,uuid);
                s.call(INVENTORY,"setItem",s.call("net.minecraft.world.entity.player.Player","getInventory",serverPlayer),9,s.call("net.minecraft.world.item.ItemStack","copy",next));
            }catch(Exception e){throw new RuntimeException(e);}}});
        }catch(Throwable e) {
            e.printStackTrace(System.err);report.addProperty("error",e.toString());
            try{writeReport();}catch(Exception ignored){}System.exit(1);
        }
    }
    private static Object stack(String id) throws Exception {
        Object items=s.field("net.minecraft.core.registries.BuiltInRegistries","ITEM",null);
        Object item=s.call("net.minecraft.core.Registry","getValue",items,s.call("net.minecraft.resources.ResourceLocation","parse",null,id));
        return s.call("net.minecraft.world.item.Item","getDefaultInstance",item);
    }
    private static void writeReport() throws IOException {Files.write(output.resolve("capture.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes("UTF-8"));}
}
