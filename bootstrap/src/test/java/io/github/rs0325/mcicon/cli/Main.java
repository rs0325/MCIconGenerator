package io.github.rs0325.mcicon.cli;

/** bootstrapだけを検証する非配布CLI代役。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        boolean managed=args.length>0&&java.util.Set.of("list","render").contains(args[0]);var request=StartupControl.JSON.createObjectNode().put("command",managed?args[0]:"root").put("managed",managed).put("offline",java.util.Arrays.asList(args).contains("--offline")).put("help",false).put("preparationMillis",900000).put("startupMillis",180000).put("itemMillis",30000);request.set("args",StartupControl.JSON.valueToTree(args));
        java.nio.file.Path jar=java.nio.file.Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());String version=jar.getParent().getParent().getParent().getParent().getFileName().toString();
        try(StartupControl control=StartupControl.open(request,version,jar)){
            if(control!=null&&control.switching())return;
            if(java.util.Arrays.asList(args).contains("init-fail"))throw new java.io.IOException("fixture init failed");
            int waiting=java.util.Arrays.asList(args).indexOf("wait-ready");if(waiting>=0){java.nio.file.Path gate=java.nio.file.Path.of(args[waiting+1]);java.nio.file.Files.writeString(java.nio.file.Path.of(gate+".ready"),"before READY");long end=System.nanoTime()+java.time.Duration.ofSeconds(3).toNanos();while(!java.nio.file.Files.exists(gate)){if(System.nanoTime()>=end)throw new java.io.IOException("fixture gate timeout");Thread.sleep(5);}}
            if(managed&&control!=null)control.ready();
            int output=java.util.Arrays.asList(args).indexOf("output");if(output>=0)java.nio.file.Files.writeString(java.nio.file.Path.of(args[output+1]),"fixture output after GO");
        }
        if(args.length>0&&args[0].equals("block")){java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]),"started");Thread.sleep(60000);}
        System.out.println("fixture-cli:"+String.join("|",args));
        System.exit(args.length>0&&args[0].equals("exit7")?7:0);
    }
}
