package io.github.rs0325.mcicon.helper.windows;

import io.github.rs0325.mcicon.helper.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** jpackageの固定entrypoint。worker stdoutと制御JSONを混ぜない。 */
public final class Main {
    private Main() {}
    public static void main(String[] arguments){System.exit(run(arguments));}
    static int run(String[] arguments) {
        long started=System.nanoTime();HelperProtocol.Command command=null;String status="failed";
        try {
            command=HelperProtocol.parse(arguments);
            String entry=System.getProperty("jpackage.app-path");if(entry==null||!Path.of(entry).isAbsolute())throw new IOException("自己完結native entrypointから起動してください");
            LeaseFile lease=LeaseFile.open(command,Path.of(entry));if(command.operation().equals("preflight"))status="setup-required";
            if(!(command.operation().equals("release")&&WindowsRuntime.releaseUnstarted(command,lease))) {
                WindowsRuntime runtime=new WindowsRuntime(command,Path.of(entry),lease,started);
                switch(command.operation()){case "keep"->new WindowsKeeper(runtime).run();case "preflight"->runtime.preflight();case "prepare"->runtime.prepare();case "release"->runtime.release();case "docker"->{return runtime.docker(command.dockerArguments());}default->throw new IOException("未対応helper操作です");}
            }
            System.out.write(HelperProtocol.response("ok",List.of()));System.out.write('\n');return 0;
        }catch(IOException|InvalidPathException failure) {
            String detail=failure.getMessage();if(detail==null||detail.isEmpty())detail="Windows helper操作に失敗しました";if(detail.length()>16000)detail=detail.substring(0,16000);
            if(command!=null&&command.operation().equals("docker")){System.err.println(detail);return 1;}
            try{System.out.write(HelperProtocol.response(status,List.of(new HelperProtocol.Diagnostic(status.equals("setup-required")?"WINDOWS_RUNTIME_SETUP_REQUIRED":"WINDOWS_RUNTIME_FAILED",detail,false,false))));System.out.write('\n');return HelperProtocol.exitCode(status);}catch(IOException output){System.err.println("helper応答を書き込めません");return 1;}
        }
    }
}
