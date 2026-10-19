package io.github.rs0325.mcicon.bootstrap;

import java.nio.file.*;
import java.util.*;

/** 同梱Javaからbootstrapを開始し、選択slotのCLIへargv/stdio/exitを渡す。 */
public final class Main {
    private Main() {}
    /** package内trust/seedから選択slotを起動し、元CLIの終了codeを返す。
     * @param arguments CLIへ渡す引数。--bootstrap-repairは単独指定する
     */
    public static void main(String[] arguments) {System.exit(run(arguments));}
    static int run(String[] arguments) {
        try {
            if(System.getProperty("jpackage.app-path")==null)throw Seed.invalid("Java同梱のpackage entryから起動してください");
            Path jar=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());Seed.regular(jar);Seed.Host host=host();Bootstrap bootstrap=new Bootstrap(jar.getParent(),root(host),host);
            if(Arrays.asList(arguments).contains("--bootstrap-repair")){if(arguments.length!=1)throw new IllegalArgumentException("--bootstrap-repairは単独で指定してください");bootstrap.repair();return 0;}
            if(Arrays.asList(arguments).contains("--bootstrap-auto-update")){if(arguments.length!=2||!arguments[0].equals("--bootstrap-auto-update")||!Set.of("true","false").contains(arguments[1]))throw new IllegalArgumentException("--bootstrap-auto-update true|falseを単独で指定してください");new UpdatePreparation(bootstrap).configure(Boolean.parseBoolean(arguments[1]),System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos());return 0;}
            List<String> cli=new ArrayList<>(List.of(arguments));String pin=null;if(!cli.isEmpty()&&cli.getFirst().equals("--bootstrap-pin")){if(cli.size()<2)throw new IllegalArgumentException("--bootstrap-pinに指定版が必要です");pin=cli.remove(1);cli.removeFirst();}
            return bootstrap.launch(cli,pin);
        }catch(Exception failure) {System.err.println("BOOTSTRAP_INVALID: "+failure.getMessage());return failure instanceof IllegalArgumentException?2:1;}
    }
    static Seed.Host host() throws java.io.IOException {
        String system=System.getProperty("os.name").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch").toLowerCase(Locale.ROOT);String os=system.contains("mac")?"macos":system.contains("win")?"windows":system.contains("linux")?"linux":null;String cpu=Set.of("arm64","aarch64").contains(arch)?"aarch64":Set.of("amd64","x86_64").contains(arch)?"x86_64":null;if(os==null||cpu==null)throw Seed.invalid("未対応hostです");return new Seed.Host(os,cpu);
    }
    static Path root(Seed.Host host) {
        Path home=Path.of(System.getProperty("user.home"));return switch(host.os()) {
            case "macos"->home.resolve("Library/Application Support/MCIconBootstrap");
            case "windows"->{String local=System.getenv("LOCALAPPDATA");yield (local==null?home.resolve("AppData/Local"):Path.of(local)).resolve("MCIconBootstrap");}
            default->{String data=System.getenv("XDG_DATA_HOME");yield (data==null?home.resolve(".local/share"):Path.of(data)).resolve("mcicon-bootstrap");}
        };
    }
}
