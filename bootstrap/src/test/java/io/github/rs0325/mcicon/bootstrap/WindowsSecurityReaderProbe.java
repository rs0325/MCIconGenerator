package io.github.rs0325.mcicon.bootstrap;

import java.nio.file.Path;
import java.util.Arrays;

/** 実Windows単体受入用。製品CLI/seed/所有変更を実行しない。 */
public final class WindowsSecurityReaderProbe {
    public static void main(String[] arguments) throws Exception {
        boolean file=arguments.length>0&&arguments[0].equals("--file");int offset=file?1:0;
        String expected=arguments.length>offset&&arguments[offset].startsWith("--expect-unknown=")?arguments[offset++].substring("--expect-unknown=".length()):null;
        if(arguments.length<offset+2)throw new IllegalArgumentException("installedとdirectory chainが必要です");
        var reader=new WindowsSecurityReader(Path.of(arguments[offset]));
        var paths=Arrays.stream(arguments).skip(offset+1).map(Path::of).toList();
        try {var observation=file?reader.readFile(paths):reader.read(paths);if(expected!=null)throw new IllegalStateException("期待したunknownになりませんでした");System.out.println(Seed.JSON.writeValueAsString(observation));}
        catch(WindowsSecurityReader.ObservationFailure error){
            if(expected!=null&&error.nativeExit==1&&error.observation.path("status").asText().equals("unknown")&&error.observation.path("errorStage").asText().equals(expected)) {
                var result=Seed.JSON.createObjectNode().put("expectedUnknown",true).put("nativeExit",error.nativeExit);result.set("observation",error.observation);System.out.println(Seed.JSON.writeValueAsString(result));
            }else {System.err.println(Seed.JSON.writeValueAsString(error.observation));System.exit(1);}
        }
    }
}
