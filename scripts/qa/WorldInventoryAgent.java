package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import io.github.rs0325.mcicon.worker.Symbols;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.nio.file.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** ワールド・画面操作、撮影と描画状態の観測を行う。production描画・固定化処理は呼ばない。 */
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
    private static boolean legacy,fixed,classic,modern;
    private static int guiLightmapUnit=1;
    private static Boolean guiLightmapEnabled;
    private static Integer guiLightmapShaderTexture;
    public static void premain(String argument,Instrumentation instrumentation) throws Exception {
        config=new JsonParser().parse(new String(Files.readAllBytes(Paths.get(argument)),"UTF-8")).getAsJsonObject();
        s=new Symbols(Paths.get(config.get("mappings").getAsString()));
        output=Paths.get(config.get("output").getAsString());
        report.addProperty("version",config.get("version").getAsString());report.addProperty("productionRendererUsed",false);
        report.addProperty("canonicalHooksUsed",false);report.addProperty("fullBrightPackedLight",15728880);report.addProperty("renderMethodsTransformed",config.has("fixedGlintMillis"));
        report.add("captures",captures);
        legacy=config.get("version").getAsString().matches("1\\.(?:13(?:\\.[12])?|14(?:\\.[123])?)");
        fixed=config.get("version").getAsString().matches("1\\.(?:14\\.4|15(?:\\.[12])?|16(?:\\.[1-5])?)");
        classic=config.get("version").getAsString().matches("1\\.(?:17(?:\\.1)?|18(?:\\.[12])?|19(?:\\.[1-4])?)");
        modern=config.get("version").getAsString().matches("1\\.(?:20(?:\\.[1-5])?|21(?:\\.[1-3])?)");
        if(legacy||fixed||classic||modern){report.remove("fullBrightPackedLight");report.addProperty("renderMethodsTransformed",true);report.addProperty("renderStateObservedOnly",true);}
        final String owner=s.actualClass(MC).replace('.','/'),method=s.methodName(MC,legacy?"m_0520165":"runTick",1);
        final String itemClass=legacy?"net.minecraft.client.render.entity.ItemRenderer":"net.minecraft.client.renderer.entity.ItemRenderer";
        final String itemOwner=s.actualClass(itemClass).replace('.','/');
        final boolean guiPose=classic&&s.hasMethod(itemClass,"renderGuiItem","com.mojang.blaze3d.vertex.PoseStack","net.minecraft.world.item.ItemStack","int","int","net.minecraft.client.resources.model.BakedModel");
        final String guiMethod=s.methodName(itemClass,legacy?"renderGuiItemModel":"renderGuiItem",guiPose?5:4);
        final boolean pose=(fixed||classic||modern)&&s.hasMethod(itemClass,"render",8);
        guiLightmapUnit=pose?2:1;
        final String drawMethod=s.methodName(itemClass,legacy?"renderItem":"render",pose?8:2);
        final String stackType=descriptor(legacy?"net.minecraft.item.ItemStack":"net.minecraft.world.item.ItemStack");
        final String modelType=descriptor(legacy?"net.minecraft.client.resource.model.BakedModel":"net.minecraft.client.resources.model.BakedModel");
        final String transformType=s.hasClass("net.minecraft.world.item.ItemDisplayContext")?"net.minecraft.world.item.ItemDisplayContext":"net.minecraft.client.renderer.block.model.ItemTransforms$TransformType";
        final String drawDescriptor="("+stackType+(pose?descriptor(transformType)+"Z"+descriptor("com.mojang.blaze3d.vertex.PoseStack")+descriptor("net.minecraft.client.renderer.MultiBufferSource")+"II":"")+modelType+")V",guiDescriptor="("+(guiPose?descriptor("com.mojang.blaze3d.vertex.PoseStack"):"")+stackType+"II"+modelType+")V";
        final String guiOwner=modern?s.actualClass("net.minecraft.client.gui.GuiGraphics").replace('.','/'):itemOwner;
        final String modernGuiMethod=s.methodName("net.minecraft.client.gui.GuiGraphics","renderItem",7);
        final String modernGuiDescriptor="("+descriptor("net.minecraft.world.entity.LivingEntity")+descriptor("net.minecraft.world.level.Level")+stackType+"IIII)V";
        final String modernDrawDescriptor=drawDescriptor.replace(")V",descriptor("net.minecraft.world.level.Level")+descriptor("net.minecraft.world.entity.LivingEntity")+"I)V");
        final String glintOwner=s.actualClass("net.minecraft.client.renderer.RenderStateShard").replace('.','/');
        final String glintMethod=s.methodName("net.minecraft.client.renderer.RenderStateShard","setupGlintTexturing",1);
        final String timeOwner=s.actualClass("net.minecraft.Util").replace('.','/'),timeMethod=s.methodName("net.minecraft.Util","getMillis",0);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
                if((legacy||fixed||classic||modern)&&name.equals(guiOwner)) {
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                            MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                            if(!name.equals(modern?modernGuiMethod:guiMethod)||!desc.equals(modern?modernGuiDescriptor:guiDescriptor))return target;
                            return new MethodVisitor(Opcodes.ASM9,target) {
                                public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf) {
                                    if(owner.equals(itemOwner)&&name.equals(drawMethod)&&(desc.equals(drawDescriptor)||modern&&desc.equals(modernDrawDescriptor))) {
                                        super.visitVarInsn(Opcodes.ALOAD,modern?3:guiPose?2:1);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,"io/github/rs0325/mcicon/qa/WorldInventoryAgent","observeGui","(Ljava/lang/Object;)V",false);
                                    }
                                    super.visitMethodInsn(opcode,owner,name,desc,itf);
                                }
                            };
                        }
                    },0);return writer.toByteArray();
                }
                if(config.has("fixedGlintMillis")&&name.equals(glintOwner)) {
                    final int[] calls={0};ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                            MethodVisitor target=super.visitMethod(access,name,desc,signature,exceptions);
                            if(!name.equals(glintMethod)||!desc.equals("(F)V"))return target;
                            return new MethodVisitor(Opcodes.ASM9,target) {
                                public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf) {
                                    if(opcode==Opcodes.INVOKESTATIC&&owner.equals(timeOwner)&&name.equals(timeMethod)&&desc.equals("()J")) {
                                        super.visitLdcInsn(config.get("fixedGlintMillis").getAsLong());calls[0]++;return;
                                    }
                                    super.visitMethodInsn(opcode,owner,name,desc,itf);
                                }
                            };
                        }
                    },0);
                    report.addProperty("glintClockCallSites",calls[0]);report.addProperty("fixedGlintMillis",config.get("fixedGlintMillis").getAsLong());
                    return writer.toByteArray();
                }
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
            if(legacy){frameLegacy();return;}
            if(minecraft==null)minecraft=s.call(MC,"getInstance",null);
            Object screen=s.field(MC,"screen",minecraft);
            if(stage==0) {
                if((!fixed&&!classic&&s.hasMethod(MC,"isGameLoadFinished",0)&&!Boolean.TRUE.equals(s.call(MC,"isGameLoadFinished",minecraft)))||s.call(MC,"getOverlay",minecraft)!=null||!s.type("net.minecraft.client.gui.screens.TitleScreen").isInstance(screen))return;
                if(++frames<60)return;
                if(fixed||(classic&&s.hasMethod(MC,"createLevel",4))){createDirectWorld();return;}
                if(config.has("frameZeroResources")) {
                    JsonObject sources=new JsonObject();Object manager=s.call(MC,"getResourceManager",minecraft);
                    for(JsonElement e:config.getAsJsonArray("frameZeroResources")) {
                        String id=e.getAsString();Object location=location(id);
                        Object resource=((Optional<?>)s.call("net.minecraft.server.packs.resources.ResourceManager","getResource",manager,location)).get();
                        sources.addProperty(id,String.valueOf(s.call("net.minecraft.server.packs.resources.Resource","sourcePackId",resource)));
                    }
                    report.add("frameZeroResourceSources",sources);writeReport();
                }
                s.call(CREATE,"openFresh",null,minecraft,screen);stage=1;frames=0;return;
            }
            if(stage==1) {
                if(!s.type(CREATE).isInstance(screen))return;
                if(classic&&!s.hasMethod(CREATE,"getUiState",0)){configureClassicWorld(screen);s.call(CREATE,"onCreate",screen);stage=2;frames=0;return;}
                Object state=s.call(CREATE,"getUiState",screen);
                s.call(STATE,"setName",state,"MCIcon QA");s.call(STATE,"setSeed",state,"0");
                s.call(STATE,s.hasMethod(STATE,"setAllowCheats",1)?"setAllowCheats":"setAllowCommands",state,true);
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
            Object inventory=inventory(player);
            Object stack=s.call(INVENTORY,"getItem",inventory,9);
            if(index>=0) {
                String actual=String.valueOf(s.call("net.minecraft.core.Registry","getKey",items(),s.call("net.minecraft.world.item.ItemStack","getItem",stack)));
                if(!actual.equals(config.getAsJsonArray("ids").get(index).getAsString()))throw new IllegalStateException("スロットのアイテムが不一致: "+actual);
            }
            String id=index<0?"empty":config.getAsJsonArray("ids").get(index).getAsString();
            Object renderer=s.field(MC,"gameRenderer",minecraft);
            Object light=fixed?s.field("net.minecraft.client.renderer.GameRenderer","lightTexture",renderer):s.call("net.minecraft.client.renderer.GameRenderer","lightTexture",renderer);
            int fullBright;
            if(s.hasField("net.minecraft.client.renderer.LightTexture","lightPixels")) {
                Object pixels=s.field("net.minecraft.client.renderer.LightTexture","lightPixels",light);
                fullBright=((Number)s.call("com.mojang.blaze3d.platform.NativeImage","getPixelRGBA",pixels,15,15)).intValue();
                report.addProperty("lightmapProbe","cpu-lightPixels");
            } else {
                Object lightImage=s.call("net.minecraft.client.Screenshot","takeScreenshot",null,s.field("net.minecraft.client.renderer.LightTexture","target",light));
                try{fullBright=((Number)s.call("com.mojang.blaze3d.platform.NativeImage",s.hasMethod("com.mojang.blaze3d.platform.NativeImage","getPixel",2)?"getPixel":"getPixelRGBA",lightImage,15,0)).intValue();}
                finally{s.call("com.mojang.blaze3d.platform.NativeImage","close",lightImage);}
                report.addProperty("lightmapProbe","gpu-target");
            }
            Object target=s.call(MC,"getMainRenderTarget",minecraft);
            Object image=s.hasMethod("net.minecraft.client.Screenshot","takeScreenshot",3)?s.call("net.minecraft.client.Screenshot","takeScreenshot",null,1280,960,target):s.call("net.minecraft.client.Screenshot","takeScreenshot",null,target);
            Path path=output.resolve(id.replace(':','_')+"-screen.png");
            try{s.call("com.mojang.blaze3d.platform.NativeImage","writeToFile",image,path);}finally{s.call("com.mojang.blaze3d.platform.NativeImage","close",image);}
            JsonObject capture=new JsonObject();capture.addProperty("id",id);capture.addProperty("screen",path.getFileName().toString());
            int left=((Number)s.field("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen","leftPos",screen)).intValue();
            int top=((Number)s.field("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen","topPos",screen)).intValue();
            capture.addProperty("xGui",left+8);capture.addProperty("yGui",top+84);
            capture.addProperty("count",((Number)s.call("net.minecraft.world.item.ItemStack","getCount",stack)).intValue());
            capture.addProperty("hasFoil",(Boolean)s.call("net.minecraft.world.item.ItemStack","hasFoil",stack));
            capture.addProperty("fullBrightArgb",fullBright);
            if(fixed||classic||modern){observeLightTexture(light,fullBright,capture);}
            capture.addProperty("gameTime",((Number)s.call("net.minecraft.world.level.Level","getGameTime",level)).longValue());
            captures.add(capture);writeReport();System.err.println("[mcicon QA world] captured "+id);
            index++;frames=0;guiLightmapEnabled=null;guiLightmapShaderTexture=null;
            if(index==config.getAsJsonArray("ids").size()) {report.addProperty("completed",true);writeReport();stage=4;s.call(MC,"stop",minecraft);return;}
            final Object next=stack(config.getAsJsonArray("ids").get(index).getAsString());
            s.call(INVENTORY,"setItem",inventory,9,next);
            // server側も同じ通常stackにし、同期によるスロットの巻き戻りを防ぐ。
            final UUID uuid=(UUID)s.call("net.minecraft.world.entity.Entity","getUUID",player);
            final Object server=s.call(MC,"getSingleplayerServer",minecraft);
            s.call("net.minecraft.util.thread.BlockableEventLoop","execute",server,new Runnable(){public void run(){try {
                Object list=s.call("net.minecraft.server.MinecraftServer","getPlayerList",server);
                Object serverPlayer=s.call("net.minecraft.server.players.PlayerList","getPlayer",list,uuid);
                s.call(INVENTORY,"setItem",inventory(serverPlayer),9,s.call("net.minecraft.world.item.ItemStack","copy",next));
            }catch(Exception e){throw new RuntimeException(e);}}});
        }catch(Throwable e) {
            e.printStackTrace(System.err);report.addProperty("error",e.toString());
            try{writeReport();}catch(Exception ignored){}System.exit(1);
        }
    }
    private static void frameLegacy() throws Exception {
        String playerType="net.minecraft.entity.living.player.PlayerEntity",inventoryType="net.minecraft.inventory.Inventory";
        String inventoryScreen="net.minecraft.client.gui.screen.inventory.menu.SurvivalInventoryScreen";
        String imageType=config.get("version").getAsString().startsWith("1.13")?"net.minecraft.client.render.platform.NativeImage":"com.mojang.blaze3d.platform.NativeImage";
        if(minecraft==null)minecraft=s.call(MC,"getInstance",null);
        Object screen=s.field(MC,"screen",minecraft);
        if(stage==0) {
            if(!s.type("net.minecraft.client.gui.screen.TitleScreen").isInstance(screen)||++frames<60)return;
            Object settings=s.construct("net.minecraft.world.WorldSettings",0L,s.field("net.minecraft.world.GameMode","SURVIVAL",null),false,false,s.field("net.minecraft.world.gen.WorldGeneratorType","FLAT",null));
            s.call("net.minecraft.world.WorldSettings","enableCommands",settings);
            stage=2;frames=0;
            s.call(MC,"startGame",minecraft,"mcicon-qa","MCIcon QA",settings);return;
        }
        Object player=s.field(MC,"player",minecraft),world=s.field(MC,"world",minecraft);
        if(stage==2) {
            if(player==null||world==null||screen!=null||++frames<60)return;
            Object mouse=s.field(MC,"mouseHandler",minecraft);
            s.setField("net.minecraft.client.MouseHandler","xpos",mouse,0.0);
            s.setField("net.minecraft.client.MouseHandler","ypos",mouse,0.0);
            s.call(MC,"openScreen",minecraft,s.construct(inventoryScreen,player));stage=3;frames=0;return;
        }
        if(stage!=3)return;
        if(!s.type(inventoryScreen).isInstance(screen))throw new IllegalStateException("inventory画面が閉じられました");
        if(++frames<30)return;
        Object inventory=s.field(playerType,"inventory",player),stack=s.call(inventoryType,"getItem",inventory,9);
        boolean unified=!config.get("version").getAsString().equals("1.13");
        String registryType=unified?"net.minecraft.util.registry.Registry":"net.minecraft.util.registry.IdRegistry";
        Object items=s.field(unified?registryType:"net.minecraft.item.Item",unified?"ITEM":"REGISTRY",null);
        if(index>=0) {
            String actual=String.valueOf(s.call(registryType,"getKey",items,s.call("net.minecraft.item.ItemStack","getItem",stack)));
            if(!actual.equals(config.getAsJsonArray("ids").get(index).getAsString()))throw new IllegalStateException("スロットのアイテムが不一致: "+actual);
        }
        String id=index<0?"empty":config.getAsJsonArray("ids").get(index).getAsString();
        Object light=s.field("net.minecraft.client.render.GameRenderer","lightMap",s.field(MC,"gameRenderer",minecraft));
        Object pixels=s.field("net.minecraft.client.render.LightMap","pixels",light);
        int fullBright=((Number)s.call(imageType,"getPixelRGBA",pixels,15,15)).intValue();
        Object texture=s.field("net.minecraft.client.render.LightMap","texture",light);
        ByteBuffer gpu=readLightTexture(texture,"net.minecraft.client.render.texture.Texture","getGlId");
        Object image=s.call("net.minecraft.client.Screenshot","take",null,1280,960,s.call(MC,"getRenderTarget",minecraft));
        Path path=output.resolve(id.replace(':','_')+"-screen.png");
        try{s.call(imageType,"write",image,path);}finally{s.call(imageType,"close",image);}
        JsonObject capture=new JsonObject();capture.addProperty("id",id);capture.addProperty("screen",path.getFileName().toString());
        String menu="net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen";
        capture.addProperty("xGui",((Number)s.field(menu,"x",screen)).intValue()+8);
        capture.addProperty("yGui",((Number)s.field(menu,"y",screen)).intValue()+84);
        capture.addProperty("count",((Number)s.call("net.minecraft.item.ItemStack","getSize",stack)).intValue());
        capture.addProperty("hasFoil",(Boolean)s.call("net.minecraft.item.ItemStack","hasEnchantmentGlint",stack));
        capture.addProperty("fullBrightArgb",fullBright);
        if(index>=0) {
            if(guiLightmapEnabled==null)throw new IllegalStateException("GUIのlightmap状態を観測できませんでした");
            capture.addProperty("guiLightmapTextureEnabled",guiLightmapEnabled);
            capture.addProperty("guiLightmapTextureUnit",1);
        }
        JsonArray cpuRgba=new JsonArray(),gpuRgba=new JsonArray();
        for(int channel=0;channel<4;channel++){cpuRgba.add((fullBright>>(channel*8))&255);gpuRgba.add(gpu.get((15*16+15)*4+channel)&255);}
        capture.add("fullBrightCpuRgba",cpuRgba);capture.add("fullBrightGpuRgba",gpuRgba);
        capture.addProperty("gameTime",((Number)s.call("net.minecraft.world.World","getTime",world)).longValue());
        report.addProperty("lightmapProbe","cpu-pixels-and-gpu-texture");
        captures.add(capture);writeReport();System.err.println("[mcicon QA world] captured "+id);
        index++;frames=0;guiLightmapEnabled=null;
        if(index==config.getAsJsonArray("ids").size()){report.addProperty("completed",true);writeReport();stage=4;s.call(MC,"stop",minecraft);return;}
        String nextId=config.getAsJsonArray("ids").get(index).getAsString();Object item=null;
        for(Object value:(Iterable<?>)items)if(String.valueOf(s.call(registryType,"getKey",items,value)).equals(nextId)){item=value;break;}
        if(item==null)throw new IllegalStateException("レジストリに対象アイテムがありません: "+nextId);
        final Object next=s.call("net.minecraft.item.Item","defaultItem",item);
        s.call(inventoryType,"setItem",inventory,9,next);
        final UUID uuid=(UUID)s.call("net.minecraft.entity.Entity","getUuid",player);
        final Object server=s.call(MC,"getServer",minecraft);
        final java.util.concurrent.Callable<Object> update=new java.util.concurrent.Callable<Object>() {public Object call() throws Exception {
            Object list=s.call("net.minecraft.server.MinecraftServer","getPlayerManager",server);
            Object serverPlayer=s.call("net.minecraft.server.PlayerManager","get",list,uuid);
            s.call("net.minecraft.inventory.Inventory","setItem",s.field("net.minecraft.entity.living.player.PlayerEntity","inventory",serverPlayer),9,s.call("net.minecraft.item.ItemStack","copy",next));
            return null;
        }};
        if(config.get("version").getAsString().startsWith("1.13"))s.call("net.minecraft.server.MinecraftServer","execute",server,update);
        else ((java.util.concurrent.Executor)server).execute(new Runnable() {public void run() {
            try{update.call();}catch(Exception e){throw new IllegalStateException("server側のスロットを更新できません",e);}
        }});
    }
    public static void observeGui(Object stack) {
        if(stage!=3||index<0)return;
        try {
            Object player=s.field(MC,"player",minecraft);
            Object inventory=legacy?s.field("net.minecraft.entity.living.player.PlayerEntity","inventory",player):inventory(player);
            if(stack!=s.call(legacy?"net.minecraft.inventory.Inventory":INVENTORY,"getItem",inventory,9))return;
            if(classic||modern){guiLightmapShaderTexture=((Number)s.call("com.mojang.blaze3d.systems.RenderSystem","getShaderTexture",null,guiLightmapUnit)).intValue();return;}
            int active=((Number)s.call("org.lwjgl.opengl.GL11","glGetInteger",null,34016)).intValue();
            try {
                s.call("org.lwjgl.opengl.GL13","glActiveTexture",null,33984+guiLightmapUnit);
                guiLightmapEnabled=(Boolean)s.call("org.lwjgl.opengl.GL11","glIsEnabled",null,3553);
            } finally {s.call("org.lwjgl.opengl.GL13","glActiveTexture",null,active);}
        }catch(Exception e){throw new IllegalStateException("GUIのlightmap状態を取得できません",e);}
    }
    private static Object stack(String id) throws Exception {
        Object items=items();
        Object item=s.call("net.minecraft.core.Registry",s.hasMethod("net.minecraft.core.Registry","getValue",1)?"getValue":"get",items,location(id));
        return s.call("net.minecraft.world.item.Item","getDefaultInstance",item);
    }
    private static Object items() throws Exception {return s.field(s.hasClass("net.minecraft.core.registries.BuiltInRegistries")?"net.minecraft.core.registries.BuiltInRegistries":"net.minecraft.core.Registry","ITEM",null);}
    private static Object inventory(Object player) throws Exception {return fixed?s.field("net.minecraft.world.entity.player.Player","inventory",player):s.call("net.minecraft.world.entity.player.Player","getInventory",player);}
    private static String descriptor(String owner) {return "L"+s.actualClass(owner).replace('.','/')+";";}
    private static void createDirectWorld() throws Exception {
        Object survival=s.field("net.minecraft.world.level.GameType","SURVIVAL",null);
        stage=2;frames=0;
        if(s.hasMethod(MC,"selectLevel",3)) {
            Object settings=s.construct("net.minecraft.world.level.LevelSettings",0L,survival,false,false,s.field("net.minecraft.world.level.LevelType","FLAT",null));
            s.call("net.minecraft.world.level.LevelSettings","enableSinglePlayerCommands",settings);
            s.call(MC,"selectLevel",minecraft,"mcicon-qa","MCIcon QA",settings);
        } else {
            Object registries=s.call("net.minecraft.core.RegistryAccess",s.hasMethod("net.minecraft.core.RegistryAccess","builtin",0)?"builtin":"builtinCopy",null);
            Object settings=s.construct("net.minecraft.world.level.LevelSettings","MCIcon QA",survival,false,s.field("net.minecraft.world.Difficulty","PEACEFUL",null),true,s.construct("net.minecraft.world.level.GameRules"),s.field("net.minecraft.world.level.DataPackConfig","DEFAULT",null));
            Properties properties=new Properties();properties.setProperty("level-seed","0");properties.setProperty("level-type","flat");properties.setProperty("generate-structures","false");
            String generation="net.minecraft.world.level.levelgen.WorldGenSettings";
            Object parameters=s.hasClass("net.minecraft.server.dedicated.DedicatedServerProperties$WorldGenProperties")?s.construct("net.minecraft.server.dedicated.DedicatedServerProperties$WorldGenProperties","0",new JsonObject(),false,"flat"):properties;
            Object world=s.hasMethod(generation,"create",1)?s.call(generation,"create",null,parameters):s.call(generation,"create",null,registries,parameters);
            world=s.call(generation,"withSeed",world,false,OptionalLong.of(0L));
            if(((Number)s.call(generation,"seed",world)).longValue()!=0L||!Boolean.TRUE.equals(s.call(generation,"isFlatWorld",world)))throw new IllegalStateException("ワールドのseed/flat設定が不一致です");
            report.addProperty("worldSeed",0L);report.addProperty("worldIsFlat",true);
            s.call(MC,"createLevel",minecraft,"mcicon-qa",settings,registries,world);
        }
    }
    private static void configureClassicWorld(Object screen) throws Exception {
        String componentType="net.minecraft.client.gui.screens.worldselection.WorldGenSettingsComponent";
        Object component=s.field(CREATE,"worldGenSettingsComponent",screen);
        Object registries=s.call(componentType,"registryHolder",component);
        Object registryKey=s.field(s.hasClass("net.minecraft.core.registries.Registries")?"net.minecraft.core.registries.Registries":"net.minecraft.core.Registry","WORLD_PRESET"+(s.hasClass("net.minecraft.core.registries.Registries")?"":"_REGISTRY"),null);
        Object registry=s.call("net.minecraft.core.RegistryAccess","registryOrThrow",registries,registryKey);
        Object preset=s.call("net.minecraft.core.Registry","get",registry,s.field("net.minecraft.world.level.levelgen.presets.WorldPresets","FLAT",null));
        if(s.hasClass("net.minecraft.world.level.levelgen.WorldOptions")) {
            Object options=s.construct("net.minecraft.world.level.levelgen.WorldOptions",0L,false,false);
            s.call(componentType,"importSettings",component,options,s.call("net.minecraft.world.level.levelgen.presets.WorldPreset","createWorldDimensions",preset));
        } else s.call(componentType,"importSettings",component,s.call("net.minecraft.world.level.levelgen.presets.WorldPreset","createWorldGenSettings",preset,0L,false,false));
        s.call("net.minecraft.client.gui.components.EditBox","setValue",s.field(componentType,"seedEdit",component),"0");
    }
    private static void observeLightTexture(Object light,int fullBright,JsonObject capture) throws Exception {
        boolean cpu=s.hasField("net.minecraft.client.renderer.LightTexture","lightPixels");
        Object texture=s.field("net.minecraft.client.renderer.LightTexture",cpu?"lightTexture":"target",light);
        ByteBuffer gpu=readLightTexture(texture,cpu?"net.minecraft.client.renderer.texture.AbstractTexture":"com.mojang.blaze3d.pipeline.RenderTarget",cpu?"getId":"getColorTextureId");
        JsonArray cpuRgba=new JsonArray(),gpuRgba=new JsonArray();
        for(int channel=0;channel<4;channel++){cpuRgba.add((fullBright>>(channel*8))&255);gpuRgba.add(gpu.get((15*16+15)*4+channel)&255);}
        capture.addProperty("lightmapCpuAvailable",cpu);
        if(cpu)capture.add("fullBrightCpuRgba",cpuRgba);
        capture.add("fullBrightGpuRgba",gpuRgba);
        if(!cpu)capture.add("fullBrightScreenshotRgba",cpuRgba);
        if(index>=0) {
            if(classic||modern) {
                if(guiLightmapShaderTexture==null)throw new IllegalStateException("GUIのshader textureを観測できませんでした");
                capture.addProperty("guiLightmapShaderTexture",guiLightmapShaderTexture);
            } else {
                if(guiLightmapEnabled==null)throw new IllegalStateException("GUIのlightmap状態を観測できませんでした");
                capture.addProperty("guiLightmapTextureEnabled",guiLightmapEnabled);
            }
            capture.addProperty("guiLightmapTextureUnit",guiLightmapUnit);
        }
        report.addProperty("lightmapProbe",cpu?"cpu-lightPixels-and-gpu-texture":"gpu-target-screenshot-and-texture");
    }
    private static ByteBuffer readLightTexture(Object texture,String type,String method) throws Exception {
        int id=((Number)s.call(type,method,texture)).intValue();
        ByteBuffer gpu=ByteBuffer.allocateDirect(16*16*4);
        int previous=((Number)s.call("org.lwjgl.opengl.GL11","glGetInteger",null,32873)).intValue();
        try {s.call("org.lwjgl.opengl.GL11","glBindTexture",null,3553,id);s.call("org.lwjgl.opengl.GL11","glGetTexImage",null,3553,0,6408,5121,gpu);}
        finally {s.call("org.lwjgl.opengl.GL11","glBindTexture",null,3553,previous);}
        return gpu;
    }
    private static Object location(String id) throws Exception {
        String owner="net.minecraft.resources.ResourceLocation";
        return s.hasMethod(owner,"parse",1)?s.call(owner,"parse",null,id):s.construct(owner,id);
    }
    private static void writeReport() throws IOException {Files.write(output.resolve("capture.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes("UTF-8"));}
}
