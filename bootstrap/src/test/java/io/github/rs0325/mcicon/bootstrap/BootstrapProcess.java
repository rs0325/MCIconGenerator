package io.github.rs0325.mcicon.bootstrap;

import java.nio.file.Path;
import java.util.*;

/** fixtureだけが指定するprivate rootへ実bootstrap processを開始する。 */
public final class BootstrapProcess {
    public static void main(String[] args) throws Exception {
        Bootstrap bootstrap=new Bootstrap(Path.of(args[0]),Path.of(args[1]),Main.host());
        System.exit(bootstrap.launch(Arrays.asList(args).subList(2,args.length)));
    }
}
