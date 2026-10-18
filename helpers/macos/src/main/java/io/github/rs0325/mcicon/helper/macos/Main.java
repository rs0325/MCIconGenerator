package io.github.rs0325.mcicon.helper.macos;

import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.helper.LeaseFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** jpackage native入口から固定protocolを受ける。Dockerのstdoutは制御JSONと混ぜない。 */
public final class Main {
    private Main() {}
    public static void main(String[] arguments) {System.exit(run(arguments));}
    static int run(String[] arguments) {
        long invocationStarted=System.nanoTime();HelperProtocol.Command command=null;String failureStatus="failed";
        try {
            command=HelperProtocol.parse(arguments);String launcher=System.getProperty("jpackage.app-path");if(launcher==null)throw new IOException("自己完結native entrypointから起動してください");Path executable=Path.of(launcher);if(!executable.isAbsolute())throw new IOException("native entrypointは絶対pathが必要です");
            LeaseFile lease=LeaseFile.open(command,executable);if(command.operation().equals("preflight"))failureStatus="setup-required";
            if(command.operation().equals("release")&&MacRuntime.releaseUnstarted(command,lease,invocationStarted)){System.out.write(HelperProtocol.response("ok",List.of()));System.out.write('\n');return 0;}
            MacRuntime runtime=new MacRuntime(command,executable,lease,invocationStarted);
            switch(command.operation()) {
                case "preflight" -> runtime.preflight();
                case "prepare" -> runtime.prepare();
                case "release" -> runtime.release();
                case "docker" -> {return runtime.docker(command.dockerArguments());}
                default -> throw new IOException("未対応のhelper操作です");
            }
            System.out.write(HelperProtocol.response("ok",List.of()));System.out.write('\n');return 0;
        }catch(IOException|InvalidPathException failure) {
            String detail=failure.getMessage();if(detail==null||detail.isEmpty())detail="macOS helper操作に失敗しました";if(detail.length()>16000)detail=detail.substring(0,16000);
            if(command!=null&&command.operation().equals("docker")){System.err.println(detail);return 1;}
            try {System.out.write(HelperProtocol.response(failureStatus,List.of(new HelperProtocol.Diagnostic(failureStatus.equals("setup-required")?"MACOS_RUNTIME_SETUP_REQUIRED":"MACOS_RUNTIME_FAILED",detail,false,false))));System.out.write('\n');return HelperProtocol.exitCode(failureStatus);}
            catch(IOException outputFailure){System.err.println("helper制御応答を書き込めません");return 1;}
        }
    }
}
