package io.github.rs0325.mcicon.cli;

/** bootstrapだけを検証する非配布CLI代役。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        var request=StartupControl.JSON.createObjectNode().put("command","root").put("managed",false).put("offline",false).put("help",false).put("preparationMillis",900000).put("startupMillis",180000).put("itemMillis",30000);request.set("args",StartupControl.JSON.valueToTree(args));
        try(StartupControl control=StartupControl.open(request,"1.1.0",java.nio.file.Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()))){if(control!=null&&control.switching())return;}
        if(args.length>0&&args[0].equals("block")){java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]),"started");Thread.sleep(60000);}
        System.out.println("fixture-cli:"+String.join("|",args));
        System.exit(args.length>0&&args[0].equals("exit7")?7:0);
    }
}
