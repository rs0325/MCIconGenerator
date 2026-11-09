package io.github.rs0325.mcicon.internal.managed;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 公開旧readerの実sourceで、activeを先に見せる危険とreleased後の互換を検査する。 */
class LegacyReaderCompatibilityTest {
    @TempDir Path temporary;
    @Test void 公開旧二版は新activeを拒否するが公開前延期とrelease後なら旧jobを守れる() throws Exception {
        for(String version:List.of("1.1.0","1.1.1")) {
            Path root=Files.createDirectory(temporary.resolve("root-"+version)).toRealPath();var fixture=new WindowsAdmissionTest.Fixture(root);
            try(RuntimeLease old=fixture.oldJob();URLClassLoader reader=reader(version)) {
                Path executable=root.resolve("helpers/windows-x86_64/"+ManagedFixture.HASH+"/bin/helper");
                assertFalse(unknown(reader,root,old.file(),executable));
                assertThrows(ManagedBusyFailure.class,()->RuntimeLease.create(fixture.store,fixture.candidate,fixture.options,Instant.now().plusSeconds(30),fixture.gate()));
                assertFalse(unknown(reader,root,old.file(),executable));
                // 危険な順序をfixtureだけで再現する。製品の公開前ゲートは通さない。
                try(RuntimeLease newer=RuntimeLease.create(fixture.store,fixture.candidate,fixture.options,Instant.now().plusSeconds(30))) {
                    fixture.writeJob(newer,fixture.candidateBytes);assertTrue(unknown(reader,root,old.file(),executable));
                    newer.released();newer.close();assertFalse(unknown(reader,root,old.file(),executable));
                }
                old.released();
            }
        }
    }
    private boolean unknown(ClassLoader loader,Path root,Path lease,Path executable) throws Exception {
        Class<?> command=loader.loadClass("io.github.rs0325.mcicon.helper.HelperProtocol$Command");
        Object value=command.getConstructor(Path.class,Path.class,String.class,List.class).newInstance(root,lease,"release",List.of());
        Class<?> references=loader.loadClass("io.github.rs0325.mcicon.helper.windows.LeaseReferences");
        var inspect=references.getDeclaredMethod("inspect",command,Path.class,String.class);inspect.setAccessible(true);
        Object result=inspect.invoke(null,value,executable,RuntimeDescriptor.read(Files.readAllBytes(lease)).path("descriptorSha256").asText());var unknown=result.getClass().getDeclaredMethod("unknown");unknown.setAccessible(true);return (boolean)unknown.invoke(result);
    }
    private URLClassLoader reader(String version) throws Exception {
        Path source=Files.createDirectory(temporary.resolve("source-"+version)),classes=Files.createDirectory(temporary.resolve("classes-"+version));List<String> arguments=new ArrayList<>();
        List<URL> jars=new ArrayList<>();for(Class<?> type:List.of(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.core.JsonFactory.class,com.fasterxml.jackson.annotation.JsonProperty.class))jars.add(type.getProtectionDomain().getCodeSource().getLocation());
        arguments.addAll(List.of("--release","21","-encoding","UTF-8","-classpath",String.join(File.pathSeparator,jars.stream().map(url->{try{return Path.of(url.toURI()).toString();}catch(Exception failure){throw new IllegalStateException(failure);}}).toList()),"-d",classes.toString()));
        try(ZipInputStream archive=new ZipInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/legacy-windows-readers/sources.zip")))) {
            ZipEntry entry;while((entry=archive.getNextEntry())!=null)if(entry.getName().startsWith(version+"/")) {
                Path file=source.resolve(entry.getName().substring(version.length()+1)).normalize();assertTrue(file.startsWith(source));Files.createDirectories(file.getParent());Files.write(file,archive.readAllBytes());arguments.add(file.toString());
            }
        }
        ByteArrayOutputStream errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,arguments.toArray(String[]::new)),errors.toString(java.nio.charset.StandardCharsets.UTF_8));
        jars.addFirst(classes.toUri().toURL());return new URLClassLoader(jars.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());
    }
}
