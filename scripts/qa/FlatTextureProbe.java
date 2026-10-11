package io.github.rs0325.mcicon.qa;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** ゲーム・GPUを使わず、不透明・透明のgenerated textureと定数tintを照合する。 */
public final class FlatTextureProbe {
    public static void main(String[] args) throws Exception {
        String version=args[0];Path client=Paths.get(args[1]),folder=Paths.get(args[2]);
        JsonObject manifest=json(Files.readAllBytes(folder.resolve("manifest.json")));
        String sourceHash=hash(Files.readAllBytes(client));
        if(!sourceHash.equals(manifest.get("sourceSha256").getAsString()))throw new IOException("Client/baseline hash mismatch");
        int light=version.equals("1.21.4")?252:255;
        JsonArray results=new JsonArray();boolean passed=true;
        try(ZipFile jar=new ZipFile(client.toFile())) {
            for(int i=3;i<args.length;i++) {
                String item=args[i];String id="minecraft:"+item;
                JsonArray inputs=new JsonArray();JsonArray tints=new JsonArray();String modelId="minecraft:item/"+item;
                if(version.equals("1.13")) {
                    // Legacy ItemColors are runtime callbacks; only untinted diamond is in this oracle.
                    if(!item.equals("diamond"))throw new IllegalArgumentException("Legacy oracle only covers diamond");
                } else {
                    JsonObject definition=json(resource(jar,"assets/minecraft/items/"+item+".json",inputs)).getAsJsonObject("model");
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
                BufferedImage output=ImageIO.read(new ByteArrayInputStream(bytes));if(output.getWidth()!=64||output.getHeight()!=64)throw new IOException("Expected 64px output");
                int colorError=0,alphaError=0,bad=0;
                for(int y=0;y<64;y++)for(int x=0;x<64;x++) {
                    int a=expected.getRGB(x/4,y/4),b=output.getRGB(x,y),error=0;
                    for(int shift:new int[]{0,8,16})error=Math.max(error,Math.abs(((a>>>shift)&255)-((b>>>shift)&255)));
                    int da=Math.abs((a>>>24)-(b>>>24));colorError=Math.max(colorError,error);alphaError=Math.max(alphaError,da);if(error>1||da>0)bad++;
                }
                JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("model",modelId);result.add("tints",tints);result.add("resourceInputs",inputs);result.addProperty("exportPngSha256",hash(bytes));result.addProperty("maxRgbError",colorError);result.addProperty("maxAlphaError",alphaError);result.addProperty("pixelsOverTolerance",bad);result.addProperty("passed",bad==0);results.add(result);passed&=bad==0;
            }
        }
        JsonObject report=new JsonObject();report.addProperty("version",version);report.addProperty("fullBrightLightmap",light);report.addProperty("sourceSha256",sourceHash);report.addProperty("baselineAgentSha256",manifest.getAsJsonObject("settings").get("agentSha256").getAsString());report.addProperty("gameClassesLoaded",false);report.add("results",results);report.addProperty("passed",passed);
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
