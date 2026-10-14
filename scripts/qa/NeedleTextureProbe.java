package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** 元資源と採取済みbytecode・World根拠から、16pxの既定針だけを計算する。 */
public final class NeedleTextureProbe {
    public static void main(String[] args) throws Exception {
        Path client=Paths.get(args[0]),proofPath=Paths.get(args[1]);
        JsonObject proof=json(Files.readAllBytes(proofPath));
        if(!proof.get("passed").getAsBoolean()||!hash(Files.readAllBytes(client)).equals(proof.get("sourceSha256").getAsString()))throw new IOException("針の事前根拠とclientが一致しません");
        if(proof.get("schemaVersion").getAsInt()!=1||proof.getAsJsonArray("bytecodeInputs").size()==0||proof.getAsJsonArray("nullBranches").size()==0)throw new IOException("針の元分岐の根拠がありません");
        for(JsonElement branch:proof.getAsJsonArray("nullBranches"))if(branch.getAsJsonObject().get("direction").getAsFloat()!=0||branch.getAsJsonObject().get("randomReached").getAsBoolean())throw new IOException("null針の0返却が証明されていません");
        JsonObject lighting=proof.getAsJsonObject("worldLighting"),sampling=proof.getAsJsonObject("sampling");
        if(!lighting.get("passed").getAsBoolean()||!lighting.get("sourceSha256").equals(proof.get("sourceSha256")))throw new IOException("独立World照明の入力が一致しません");
        if(!Double.isFinite(sampling.get("singlePassDisplacementUpperBound").getAsDouble())||!Double.isFinite(sampling.get("twoPassDisplacementUpperBound").getAsDouble()))throw new IOException("未知のsampling変位です");
        if(sampling.get("sourceTextureSize").getAsInt()!=16||!sampling.get("filter").getAsString().equals("nearest")||sampling.get("singlePassDisplacementUpperBound").getAsDouble()>=0.5||sampling.get("twoPassDisplacementUpperBound").getAsDouble()>=0.5)throw new IOException("16pxのsampling条件がありません");
        boolean prepare=args[2].equals("--prepare");
        Path folder=prepare?null:Paths.get(args[2]);
        JsonObject manifest=prepare?null:json(Files.readAllBytes(folder.resolve("manifest.json")));
        JsonObject prepared=prepare?null:json(Files.readAllBytes(Paths.get(args[3])));
        if(!prepare&&(!prepared.get("preparedBeforePng").getAsBoolean()||!manifest.get("sourceSha256").equals(proof.get("sourceSha256"))||!prepared.get("proofSha256").getAsString().equals(hash(Files.readAllBytes(proofPath)))))throw new IOException("生成・事前計算の入力が一致しません");
        JsonArray results=new JsonArray();boolean passed=true;
        try(ZipFile jar=new ZipFile(client.toFile())) {
            JsonArray verifiedInputs=new JsonArray();for(JsonElement e:proof.getAsJsonArray("bytecodeInputs"))verifiedInputs.add(e);if(proof.has("shaderInputs"))for(JsonElement e:proof.getAsJsonArray("shaderInputs"))verifiedInputs.add(e);
            for(JsonElement e:verifiedInputs) {
                JsonObject input=e.getAsJsonObject();byte[] bytes=resource(jar,input.get("path").getAsString(),new JsonArray());
                if(!hash(bytes).equals(input.get("sha256").getAsString()))throw new IOException("元bytecode hashが一致しません");
            }
            for(JsonElement itemElement:proof.getAsJsonArray("items")) {
                String item=itemElement.getAsString(),id="minecraft:"+item;JsonArray inputs=new JsonArray(),steps=new JsonArray();
                String modelId;JsonObject model;
                if(proof.get("itemDefinitions").getAsBoolean()) {
                    JsonObject definition=json(resource(jar,"assets/minecraft/items/"+item+".json",inputs)).getAsJsonObject("model");
                    definition=select(definition,item,steps);
                    if(!definition.get("type").getAsString().equals("minecraft:model")||definition.has("tints"))throw new IOException("針の単一無着色modelではありません");
                    modelId=definition.get("model").getAsString();model=json(resource(jar,path(modelId,"models/",".json"),inputs));
                } else {
                    modelId="minecraft:item/"+item;model=json(resource(jar,path(modelId,"models/",".json"),inputs));
                    if(model.has("overrides")) {
                        String property=item.equals("clock")?"time":"angle";String selected=modelId;int selectedIndex=-1,index=0;
                        for(JsonElement entry:model.getAsJsonArray("overrides")) {
                            JsonObject override=entry.getAsJsonObject(),predicate=override.getAsJsonObject("predicate");
                            if(predicate.size()!=1||!predicate.has(property))throw new IOException("未知の針override propertyです");
                            if(!Double.isFinite(predicate.get(property).getAsDouble()))throw new IOException("未知のoverride閾値です");
                            if(predicate.get(property).getAsDouble()<=0){selected=override.get("model").getAsString();selectedIndex=index;}index++;
                        }
                        JsonObject step=new JsonObject();step.addProperty("type","last_matching_override");step.addProperty("direction",0);step.addProperty("index",selectedIndex);steps.add(step);
                        if(!path(selected,"models/",".json").equals(path(modelId,"models/",".json")))model=json(resource(jar,path(selected,"models/",".json"),inputs));
                        modelId=selected;
                    }
                }
                String parent=model.get("parent").getAsString();
                if(!Arrays.asList("item/generated","minecraft:item/generated").contains(parent)||model.has("display")||model.has("elements")||model.has("gui_light"))throw new IOException("未知のgenerated針モデルです");
                JsonObject generated=json(resource(jar,"assets/minecraft/models/item/generated.json",inputs));
                if(!generated.get("parent").getAsString().equals("builtin/generated")||generated.getAsJsonObject("display").has("gui")||generated.has("elements")||generated.has("overrides"))throw new IOException("generatedのGUI契約が変わりました");
                if(generated.has("gui_light")&&!generated.get("gui_light").getAsString().equals("front"))throw new IOException("generatedがside照明を使います");
                if(proof.get("frontLighting").getAsBoolean()&&(!generated.has("gui_light")||!generated.get("gui_light").getAsString().equals("front")))throw new IOException("generatedのfront照明がありません");
                JsonObject textures=model.getAsJsonObject("textures");
                if(textures.size()!=1||!textures.has("layer0"))throw new IOException("針は単一layerだけを受け入れます");
                String texturePath=path(textures.get("layer0").getAsString(),"textures/",".png");
                if(jar.getEntry(texturePath+".mcmeta")!=null)throw new IOException("針textureがanimation metadataを持ちます");
                BufferedImage texture=ImageIO.read(new ByteArrayInputStream(resource(jar,texturePath,inputs)));
                if(texture==null||texture.getWidth()!=16||texture.getHeight()!=16)throw new IOException("針textureが16pxではありません");
                int light=proof.get("lightMultiplier255").getAsInt();if(light!=252&&light!=255)throw new IOException("未知の照明倍率です");
                int[] expected=new int[256];
                for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                    int pixel=texture.getRGB(x,y),alpha=pixel>>>24;
                    if(alpha!=0&&alpha!=255)throw new IOException("半透明針textureは対象外です");
                    int rgb=0;
                    if(alpha!=0)for(int shift:new int[]{0,8,16})rgb|=((((pixel>>>shift)&255)*light+127)/255)<<shift;
                    expected[y*16+x]=(alpha<<24)|rgb;
                }
                JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("model",modelId);result.addProperty("texture",texturePath);result.add("selection",steps);result.add("resourceInputs",inputs);result.addProperty("expectedRgbaSha256",pixelsHash(expected));
                if(!prepare) {
                    JsonObject prior=entry(prepared,id);
                    if(!prior.get("expectedRgbaSha256").equals(result.get("expectedRgbaSha256"))||!prior.get("resourceInputs").equals(inputs)||!prior.get("selection").equals(steps)||!prior.get("model").equals(result.get("model")))throw new IOException("PNG生成前の計算と一致しません");
                    JsonObject export=entry(manifest,id);if(!export.get("status").getAsString().equals("success"))throw new IOException("針PNGが成功していません: "+id);
                    String relative=export.get("path").getAsString();Path png=folder.resolve(relative).normalize();
                    if(!png.startsWith(folder.normalize())||!relative.equals("assets/minecraft/textures/item/"+item+".png"))throw new IOException("針PNGの出力pathが不正です");
                    byte[] bytes=Files.readAllBytes(png);if(!hash(bytes).equals(export.get("sha256").getAsString()))throw new IOException("針PNG hashが一致しません");
                    BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
                    if(image==null||image.getWidth()!=16||image.getHeight()!=16)throw new IOException("比較対象は16pxだけです");
                    int rgbError=0,alphaError=0,bad=0;
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
                        int a=expected[y*16+x],b=image.getRGB(x,y),rgb=0;
                        for(int shift:new int[]{0,8,16})rgb=Math.max(rgb,Math.abs(((a>>>shift)&255)-((b>>>shift)&255)));
                        int alpha=Math.abs((a>>>24)-(b>>>24));rgbError=Math.max(rgbError,rgb);alphaError=Math.max(alphaError,alpha);if(rgb>1||alpha>0)bad++;
                    }
                    result.addProperty("maxRgbError",rgbError);result.addProperty("maxAlphaError",alphaError);result.addProperty("pixelsOverTolerance",bad);result.addProperty("exportPngSha256",hash(bytes));result.addProperty("passed",bad==0);passed&=bad==0;
                }
                results.add(result);
            }
        }
        JsonObject report=new JsonObject();report.add("version",proof.get("version"));report.add("sourceSha256",proof.get("sourceSha256"));report.addProperty("proofSha256",hash(Files.readAllBytes(proofPath)));report.addProperty("gameClassesLoaded",false);report.addProperty("gpuUsed",false);report.addProperty("preparedBeforePng",prepare);report.addProperty("size",16);report.add("entries",results);report.addProperty("passed",passed);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));if(!passed)System.exit(1);
    }
    private static JsonObject select(JsonObject d,String item,JsonArray steps) throws IOException {
        if(d.get("type").getAsString().equals("minecraft:condition")) {
            if(!item.equals("compass")||!d.get("property").getAsString().equals("minecraft:has_component")||!d.get("component").getAsString().equals("minecraft:lodestone_tracker"))throw new IOException("未知のcomponent分岐です");
            steps.add("default_stack_without_lodestone_tracker");d=d.getAsJsonObject("on_false");
        }
        if(d.get("type").getAsString().equals("minecraft:select")) {
            if(!d.get("property").getAsString().equals("minecraft:context_dimension"))throw new IOException("未知のdimension分岐です");
            steps.add("null_context_dimension_fallback");d=d.getAsJsonObject("fallback");
        }
        if(!d.get("type").getAsString().equals("minecraft:range_dispatch")||!d.get("property").getAsString().equals(item.equals("clock")?"minecraft:time":"minecraft:compass"))throw new IOException("未知のrange propertyです");
        String source=d.get(item.equals("clock")?"source":"target").getAsString();
        if(!(item.equals("clock")?Arrays.asList("random","daytime").contains(source):item.equals("compass")?Arrays.asList("none","spawn").contains(source):source.equals("recovery")))throw new IOException("未知の針source/targetです");
        if(d.has("normalize")||!d.has("scale")||d.get("scale").getAsFloat()!=(item.equals("clock")?64:32))throw new IOException("未知のrange変換です");
        JsonObject selected=null;float previous=Float.NEGATIVE_INFINITY;int index=0,chosen=-1;
        for(JsonElement e:d.getAsJsonArray("entries")) {
            JsonObject entry=e.getAsJsonObject();float threshold=entry.get("threshold").getAsFloat();
            if(!Float.isFinite(threshold)||threshold<=previous)throw new IOException("range閾値が昇順ではありません");
            if(threshold<=0){selected=entry.getAsJsonObject("model");chosen=index;}previous=threshold;index++;
        }
        if(selected==null)throw new IOException("direction0のrange modelがありません");
        JsonObject step=new JsonObject();step.addProperty("type","range_dispatch");step.addProperty("direction",0);step.addProperty("index",chosen);steps.add(step);return selected;
    }
    private static JsonObject entry(JsonObject report,String id) throws IOException {JsonObject found=null;for(JsonElement e:report.getAsJsonArray("entries"))if(e.getAsJsonObject().get("id").getAsString().equals(id)){if(found!=null)throw new IOException("IDが重複しています");found=e.getAsJsonObject();}if(found==null)throw new IOException("IDがありません: "+id);return found;}
    private static String pixelsHash(int[] pixels) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int pixel:pixels)for(int shift:new int[]{16,8,0,24})bytes.write((pixel>>>shift)&255);return hash(bytes.toByteArray());}
    private static byte[] resource(ZipFile jar,String path,JsonArray inputs) throws Exception {ZipEntry entry=jar.getEntry(path);if(entry==null)throw new IOException("元資源がありません: "+path);byte[] bytes;try(InputStream input=jar.getInputStream(entry)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);bytes=output.toByteArray();}JsonObject record=new JsonObject();record.addProperty("path",path);record.addProperty("sha256",hash(bytes));inputs.add(record);return bytes;}
    private static JsonObject json(byte[] bytes) throws Exception {return new JsonParser().parse(new String(bytes,"UTF-8")).getAsJsonObject();}
    private static String hash(byte[] bytes) throws Exception {StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format("%02x",b&255));return result.toString();}
    private static String path(String id,String kind,String suffix){String[] parts=(id.contains(":")?id:"minecraft:"+id).split(":",2);return "assets/"+parts[0]+"/"+kind+parts[1]+suffix;}
}
