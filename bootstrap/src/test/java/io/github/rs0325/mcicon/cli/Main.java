package io.github.rs0325.mcicon.cli;

/** bootstrapだけを検証する非配布CLI代役。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("block")){java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]),"started");Thread.sleep(60000);}
        System.out.println("fixture-cli:"+String.join("|",args));
        System.exit(args.length>0&&args[0].equals("exit7")?7:0);
    }
}
