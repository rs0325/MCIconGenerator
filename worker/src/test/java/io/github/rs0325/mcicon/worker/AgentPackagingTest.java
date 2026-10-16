package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class AgentPackagingTest {
    @Test void packagedAgentDoesNotLinkAgainstTheGamesAsm() throws Exception {
        try(ZipFile jar=new ZipFile(System.getProperty("mcicon.agentJar"))) {
            assertNull(jar.getEntry("org/objectweb/asm/ClassReader.class"));
            assertNotNull(jar.getEntry("io/github/rs0325/mcicon/worker/shaded/asm/ClassReader.class"));
            for(String name:new String[]{"RenderAgent","CanonicalTransformer","ResourceTransformer","HardwareDiagnosticsTransformer"}) {
                try(InputStream input=jar.getInputStream(jar.getEntry("io/github/rs0325/mcicon/worker/"+name+".class"))) {
                    String constants=new String(read(input),StandardCharsets.ISO_8859_1);
                    assertFalse(constants.contains("org/objectweb/asm/"),name);
                    assertTrue(constants.contains("io/github/rs0325/mcicon/worker/shaded/asm/"),name);
                }
            }
            assertNotNull(jar.getEntry("META-INF/licenses/ASM.txt"));
            assertNull(jar.getEntry("io/github/rs0325/mcicon/qa/ResourceFaultAgent.class"));
            assertNull(jar.getEntry("mcicon-resource-fault.txt"));
        }
    }
    private byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();
    }
}
