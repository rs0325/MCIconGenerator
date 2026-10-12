package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.*;
import java.util.Arrays;
import java.util.regex.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** ゲーム・GPUを使わず、generated textureと定数tint・既定の針モデルを照合する。 */
public final class FlatTextureProbe {
    public static void main(String[] args) throws Exception {
        String version=args[0];Path client=Paths.get(args[1]),folder=Paths.get(args[2]);
        JsonObject manifest=json(Files.readAllBytes(folder.resolve("manifest.json")));
        String sourceHash=hash(Files.readAllBytes(client));
        if(!sourceHash.equals(manifest.get("sourceSha256").getAsString()))throw new IOException("Client/baseline hash mismatch");
        int light=version.equals("1.21.4")?252:255;
        int firstItem=3;float shrink=0;JsonObject atlas=null;JsonArray uvInputs=new JsonArray();
        if(args.length>3&&args[3].equals("--atlas-log")) {
            if(!version.equals("1.21.4"))throw new IllegalArgumentException("UV oracle is limited to 1.21.4");
            byte[] log=Files.readAllBytes(Paths.get(args[4]));
            Matcher match=Pattern.compile("Created: ([0-9]+)x([0-9]+)x[0-9]+ minecraft:textures/atlas/blocks\\.png-atlas").matcher(new String(log,"UTF-8"));
            if(!match.find())throw new IOException("Block atlas dimensions absent from log");
            int width=Integer.parseInt(match.group(1)),height=Integer.parseInt(match.group(2));
            if(match.find())throw new IOException("Multiple block atlas creations in log");
            shrink=4f/Math.max(width,height);firstItem=5;
            atlas=new JsonObject();atlas.addProperty("width",width);atlas.addProperty("height",height);atlas.addProperty("uvShrinkRatio",shrink);atlas.addProperty("logSha256",hash(log));
        }
        JsonArray results=new JsonArray();boolean passed=true;
        try(ZipFile jar=new ZipFile(client.toFile())) {
            if(atlas!=null) {
                resource(jar,"gnn.class",uvInputs);resource(jar,"het.class",uvInputs);
            }
            for(int i=firstItem;i<args.length;i++) {
                String item=args[i];String id="minecraft:"+item;
                JsonArray inputs=new JsonArray();JsonArray tints=new JsonArray();String modelId="minecraft:item/"+item;
                if(!version.equals("1.21.4")&&!version.equals("26.3")) {
                    // 旧版のItemColorsは実行時callbackのため、無着色diamondだけを計算する。
                    if(!item.equals("diamond"))throw new IllegalArgumentException("旧モデル形式の比較対象はdiamondだけです");
                } else {
                    JsonObject definition=json(resource(jar,"assets/minecraft/items/"+item+".json",inputs)).getAsJsonObject("model");
                    if(version.equals("1.21.4")&&(item.equals("clock")||item.equals("compass")||item.equals("recovery_compass"))) {
                        // NeedleDirectionHelper.getはentityもstackのentity表現もない場合、0を返す。
                        resource(jar,"hcs.class",inputs);
                        if(item.equals("compass")) {
                            if(!definition.get("component").getAsString().equals("minecraft:lodestone_tracker"))throw new IllegalArgumentException("Unexpected compass component");
                            definition=definition.getAsJsonObject("on_false");
                        }
                        if(!item.equals("recovery_compass")) {
                            if(!definition.get("property").getAsString().equals("minecraft:context_dimension"))throw new IllegalArgumentException("Unexpected context selector");
                            definition=definition.getAsJsonObject("fallback");
                        }
                        if(!definition.get("type").getAsString().equals("minecraft:range_dispatch"))throw new IllegalArgumentException("Unexpected needle model");
                        JsonObject selected=null;
                        for(JsonElement e:definition.getAsJsonArray("entries"))if(e.getAsJsonObject().get("threshold").getAsFloat()<=0)selected=e.getAsJsonObject().getAsJsonObject("model");
                        if(selected==null)throw new IllegalArgumentException("No model for zero needle direction");
                        definition=selected;
                    }
                    if(!definition.get("type").getAsString().equals("minecraft:model"))throw new IllegalArgumentException("Non-simple item definition");
                    modelId=definition.get("model").getAsString();
                    if(definition.has("tints"))tints=definition.getAsJsonArray("tints");
                    for(JsonElement tint:tints)if(!tint.getAsJsonObject().get("type").getAsString().equals("minecraft:constant"))throw new IllegalArgumentException("Non-constant tint");
                }
                JsonObject model=json(resource(jar,modelPath(modelId),inputs));
                String parent=model.get("parent").getAsString();if(!parent.equals("item/generated")&&!parent.equals("minecraft:item/generated"))throw new IllegalArgumentException("Non-generated model");
                if(model.has("display")||model.has("overrides")||model.has("elements"))throw new IllegalArgumentException("Model requires geometry/transform evaluation");
                JsonObject generated=json(resource(jar,"assets/minecraft/models/item/generated.json",inputs));
                if(!generated.get("parent").getAsString().equals("builtin/generated")||generated.getAsJsonObject("display").has("gui"))throw new IllegalArgumentException("Unexpected generated GUI transform");
                JsonObject textures=model.getAsJsonObject("textures");BufferedImage expected=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
                for(int layer=0;layer<textures.size();layer++) {
                    String location=textures.get("layer"+layer).getAsString();int tint=layer<tints.size()?tints.get(layer).getAsJsonObject().get("value").getAsInt():0xffffff;
                    BufferedImage image=ImageIO.read(new ByteArrayInputStream(resource(jar,texturePath(location),inputs)));
                    if(image.getWidth()!=16||image.getHeight()!=16)throw new IllegalArgumentException("Oracle requires static 16px textures");
                    if(jar.getEntry(texturePath(location)+".mcmeta")!=null)throw new IllegalArgumentException("Animated texture");
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                        int pixel=image.getRGB(x,y),alpha=pixel>>>24;
                        if(alpha!=0&&alpha!=255)throw new IllegalArgumentException("Oracle does not evaluate translucent layers");
                        if(alpha==0)continue;
                        int r=(((pixel>>>16)&255)*((tint>>>16)&255)*light+32512)/65025;
                        int g=(((pixel>>>8)&255)*((tint>>>8)&255)*light+32512)/65025;
                        int b=((pixel&255)*(tint&255)*light+32512)/65025;
                        expected.setRGB(x,y,0xff000000|(r<<16)|(g<<8)|b);
                    }
                }
                JsonObject entry=null;for(JsonElement e:manifest.getAsJsonArray("entries"))if(e.getAsJsonObject().get("id").getAsString().equals(id))entry=e.getAsJsonObject();
                if(entry==null||!entry.get("status").getAsString().equals("success"))throw new IOException("Baseline lacks "+id);
                byte[] bytes=Files.readAllBytes(folder.resolve(entry.get("path").getAsString()));
                if(!hash(bytes).equals(entry.get("sha256").getAsString()))throw new IOException("Baseline PNG hash mismatch");
                BufferedImage output=ImageIO.read(new ByteArrayInputStream(bytes));int size=output.getWidth();
                if(!Arrays.asList(16,32,64,128,256,512).contains(size)||output.getHeight()!=size)throw new IOException("Expected supported square output");
                int colorError=0,alphaError=0,bad=0;
                for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                    // FaceBakeryはUVを中心へ縮める。比率はTextureAtlasSpriteの4/max(atlas幅,高さ)。
                    float u=(x+.5f)*16/size,v=(y+.5f)*16/size;
                    u+=(8f-u)*shrink;v+=(8f-v)*shrink;
                    int a=expected.getRGB((int)u,(int)v),b=output.getRGB(x,y),error=0;
                    for(int shift:new int[]{0,8,16})error=Math.max(error,Math.abs(((a>>>shift)&255)-((b>>>shift)&255)));
                    int da=Math.abs((a>>>24)-(b>>>24));colorError=Math.max(colorError,error);alphaError=Math.max(alphaError,da);if(error>1||da>0)bad++;
                }
                JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("model",modelId);result.addProperty("size",size);result.add("tints",tints);result.add("resourceInputs",inputs);result.addProperty("exportPngSha256",hash(bytes));result.addProperty("maxRgbError",colorError);result.addProperty("maxAlphaError",alphaError);result.addProperty("pixelsOverTolerance",bad);result.addProperty("passed",bad==0);results.add(result);passed&=bad==0;
            }
        }
        JsonObject report=new JsonObject();report.addProperty("version",version);report.addProperty("fullBrightLightmap",light);report.addProperty("sourceSha256",sourceHash);report.addProperty("baselineAgentSha256",manifest.getAsJsonObject("settings").get("agentSha256").getAsString());report.addProperty("gameClassesLoaded",false);if(atlas!=null){report.add("atlas",atlas);report.add("uvResourceInputs",uvInputs);}report.add("results",results);report.addProperty("passed",passed);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));if(!passed)System.exit(1);
    }
    private static byte[] resource(ZipFile jar,String name,JsonArray inputs) throws Exception {
        ZipEntry entry=jar.getEntry(name);if(entry==null)throw new IOException("Missing input "+name);
        byte[] bytes;try(InputStream input=jar.getInputStream(entry)){bytes=read(input);}
        JsonObject record=new JsonObject();record.addProperty("path",name);record.addProperty("sha256",hash(bytes));inputs.add(record);return bytes;
    }
    private static byte[] read(InputStream input) throws IOException {ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toByteArray();}
    private static JsonObject json(byte[] bytes) throws Exception{return new JsonParser().parse(new String(bytes,"UTF-8")).getAsJsonObject();}
    private static String hash(byte[] bytes) throws Exception {StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format("%02x",b&255));return result.toString();}
    private static String modelPath(String id){return resourcePath(id,"models/",".json");}
    private static String texturePath(String id){return resourcePath(id,"textures/",".png");}
    private static String resourcePath(String id,String kind,String suffix){String[] parts=(id.indexOf(':')<0?"minecraft:"+id:id).split(":",2);return "assets/"+parts[0]+"/"+kind+parts[1]+suffix;}
}
