package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class FileIdentityReaderTest {
    @TempDir Path root;
    Path reader,file,fixture;
    LeaseTrust trust;
    @BeforeEach void authenticate() throws Exception {
        String configured=System.getProperty("mcicon.testIdentityReader");Assumptions.assumeTrue(configured!=null,"明示したnative readerが必要です");
        Path built=Path.of(configured).resolveSibling("identity-fixture.exe");Assumptions.assumeTrue(Files.isRegularFile(built),"同directoryのbuild済みidentity-fixture.exeが必要です");
        fixture=built;bindReader(Files.readAllBytes(built));file=Files.writeString(root.resolve("request"),"success");
    }
    private void bindReader(byte[] payload) throws Exception {
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var recovery=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] trustBytes=TrustFixtures.bytes(key.getPublic(),recovery.getPublic());TrustSet keys=TrustSet.parse(trustBytes);
        Path helper=root.resolve("helpers/windows-x86_64/"+"1".repeat(64)+"/bin/helper.exe");Files.createDirectories(helper.getParent());Files.writeString(helper,"fixture helper");
        Path support=Files.createDirectories(helper.getParent().resolve("app/support"));reader=Files.write(support.resolve("windows-host.exe"),payload);Files.write(support.resolve("lease-trust.bin"),trustBytes);
        ObjectNode inventory=LeaseSnapshot.JSON.createObjectNode();var files=inventory.putObject("contents").putArray("files");
        files.addObject().put("path","bin/helper.exe").put("bytes",Files.size(helper)).put("sha256",LeaseSnapshot.fileDigest(helper));
        files.addObject().put("path","bin/app/support/windows-host.exe").put("bytes",Files.size(reader)).put("sha256",LeaseSnapshot.fileDigest(reader));
        byte[] listing=LeaseSnapshot.JSON.writeValueAsBytes(inventory);
        ObjectNode descriptor=LeaseSnapshot.JSON.createObjectNode();descriptor.putObject("host").put("os","windows").put("arch","x86_64");descriptor.putObject("helper").put("artifactId","helper").put("executable","bin/helper.exe");
        descriptor.putArray("artifacts").addObject().put("id","helper").put("sha256","1".repeat(64)).put("inventorySha256",LeaseSnapshot.digest(listing));
        byte[] definition=LeaseSnapshot.JSON.writeValueAsBytes(descriptor);var signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(definition);
        LeaseTrust.publish(root,definition,signature.sign(),keys);trust=LeaseTrust.packaged(helper,"windows");trust.bindReader(root,definition,listing,helper);
    }
    private FileIdentityReader.Failure fail(String mode,FileIdentityReader.Kind kind) throws Exception {
        Files.writeString(file,mode);var failure=assertThrows(FileIdentityReader.Failure.class,()->trust.identity(file));
        assertEquals(kind,failure.kind);assertTrue(failure.collected,failure.toString());assertTrue(failure.getMessage().contains("phase=FILE_IDENTITY"));return failure;
    }
    @Test void 成功schemaとpin認証を維持する() throws Exception {
        assertEquals("windows-file-id-v1:12:"+"a".repeat(32),trust.identity(file));
        Files.writeString(reader,"changed");assertFalse(assertThrows(IOException.class,()->trust.identity(file)).getMessage().contains("phase=FILE_IDENTITY"));
    }
    @Test void 非zeroは固定native情報と数値だけを保持する() throws Exception {
        var failure=fail("nonzero",FileIdentityReader.Kind.NONZERO_EXIT);assertEquals(23,failure.exitCode);assertEquals(3,failure.stdoutBytes);
        assertEquals("CreateFileW",failure.operation);assertEquals("WIN32",failure.nativeKind);assertEquals(32L,failure.win32Code);
        failure=fail("stderr-secret",FileIdentityReader.Kind.NONZERO_EXIT);assertNull(failure.operation);assertFalse(failure.toString().contains("SECRET"));
        failure=fail("stderr-extra",FileIdentityReader.Kind.NONZERO_EXIT);assertNull(failure.operation);
        failure=fail("contract",FileIdentityReader.Kind.NONZERO_EXIT);assertEquals("CONTRACT",failure.nativeKind);assertNull(failure.win32Code);
    }
    @Test void 応答上限は検出byte込みで保持する() throws Exception {
        var failure=assertTimeoutPreemptively(Duration.ofSeconds(3),()->fail("huge",FileIdentityReader.Kind.OUTPUT_LIMIT));assertEquals(4096,failure.stdoutBytes);
        Files.writeString(file,"boundary-ok");assertEquals("windows-file-id-v1:12:"+"a".repeat(32),trust.identity(file));
        assertEquals(4096,fail("boundary-limit",FileIdentityReader.Kind.OUTPUT_LIMIT).stdoutBytes);
    }
    @Test void 不正JSONと固定schema拒否は本文を持たない診断だけを保持する() throws Exception {
        var failure=fail("invalid",FileIdentityReader.Kind.INVALID_RESPONSE);assertNull(failure.getCause());assertEquals(1,failure.stdoutBytes);assertTrue(failure.exceptionClass.startsWith("com.fasterxml.jackson.core."));assertEquals(1,failure.line);assertTrue(failure.column>0);
        failure=fail("extra",FileIdentityReader.Kind.INVALID_RESPONSE);assertNull(failure.getCause());assertEquals(IOException.class.getName(),failure.exceptionClass);assertEquals(-1,failure.line);
        for(String mode:new String[]{"invalid-token","invalid-duplicate","invalid-trailing","invalid-utf8"}) {
            failure=fail(mode,FileIdentityReader.Kind.INVALID_RESPONSE);assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertNotNull(failure.exceptionClass);assertTrue(failure.stdoutBytes>0);assertEquals(0,failure.exitCode);
            var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));assertFalse(text.toString().contains("NATIVE_SENTINEL_207"));
        }
    }
    @Test void 認証済みでも起動不能なreaderの起動例外を保持する() throws Exception {
        bindReader("not an executable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var failure=assertThrows(FileIdentityReader.Failure.class,()->trust.identity(file));assertEquals(FileIdentityReader.Kind.START_FAILED,failure.kind);assertInstanceOf(IOException.class,failure.getCause());
    }
    @Test void 同版nativeをpin結合しWin32失敗と契約拒否をJavaへ保持する() throws Exception {
        Path nativeReader=Path.of(System.getProperty("mcicon.testIdentityReader"));assertEquals(System.getProperty("mcicon.testIdentityReaderSha256"),LeaseSnapshot.fileDigest(nativeReader));bindReader(Files.readAllBytes(nativeReader));
        assertTrue(trust.identity(file).toString().matches("windows-file-id-v1:[0-9]{1,20}:[0-9a-f]{32}"));
        var failure=assertThrows(FileIdentityReader.Failure.class,()->FileIdentityReader.read(reader,root.resolve("missing")));assertEquals(FileIdentityReader.Kind.NONZERO_EXIT,failure.kind);assertEquals("CreateFileW",failure.operation);assertEquals(2L,failure.win32Code);assertTrue(failure.collected);
        Files.createLink(root.resolve("hardlink"),file);
        failure=assertThrows(FileIdentityReader.Failure.class,()->trust.identity(file));assertEquals("CONTRACT",failure.nativeKind);assertEquals("FileStandardInfo",failure.operation);assertNull(failure.win32Code);assertEquals(0,failure.stdoutBytes);
    }
    @Test void timeoutは子とpipeを期限内に回収する() throws Exception {
        var failure=assertTimeoutPreemptively(Duration.ofSeconds(3),()->fail("timeout",FileIdentityReader.Kind.TIMEOUT));assertInstanceOf(TimeoutException.class,failure.getCause());
    }
    @Test void 中断は元causeと割込み状態を維持し子を回収する() throws Exception {
        Files.writeString(file,"timeout");
        try {
            Thread.currentThread().interrupt();var failure=assertThrows(FileIdentityReader.Failure.class,()->trust.identity(file));
            assertEquals(FileIdentityReader.Kind.INTERRUPTED,failure.kind);assertInstanceOf(InterruptedException.class,failure.getCause());assertTrue(Thread.currentThread().isInterrupted());assertTrue(failure.collected);
        }finally {Thread.interrupted();}
    }
    @Test void 別processのpipe保持は期限内に失敗しそのprocessを終了しない() throws Exception {
        Files.writeString(file,"descendant");
        Instant start=Instant.now();
        var failure=assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(FileIdentityReader.Failure.class,()->trust.identity(file)));
        long pid=Long.parseLong(Files.readString(file.resolveSibling(file.getFileName()+".child-pid")));
        ProcessHandle held=ProcessHandle.of(pid).orElseThrow();var info=held.info();
        assertEquals(reader.toRealPath(),Path.of(info.command().orElseThrow()).toRealPath());
        assertFalse(info.startInstant().orElseThrow().isBefore(start.minusSeconds(1)));
        try {
            assertEquals(FileIdentityReader.Kind.TIMEOUT,failure.kind);assertFalse(failure.collected);assertEquals(0,failure.exitCode);
            assertTrue(held.isAlive());assertEquals(1,failure.getSuppressed().length);
            assertEquals(FileIdentityReader.Kind.CLEANUP_FAILED,((FileIdentityReader.Failure)failure.getSuppressed()[0]).kind);
        }finally {
            assertEquals(info.startInstant(),held.info().startInstant());held.destroyForcibly();held.onExit().get(3,TimeUnit.SECONDS);
        }
    }
    @Test void 短命nativeを3000回実行しても現在JVMと監視processを終了しない() throws Exception {
        bindReader(Files.readAllBytes(Path.of(System.getProperty("mcicon.testIdentityReader"))));
        var expected=trust.identity(file);Path stop=root.resolve("monitor.stop");
        Process monitor=new ProcessBuilder(fixture.toString(),"--monitor",stop.toString()).start();
        try {
            assertTimeoutPreemptively(Duration.ofMinutes(5),()->{
                for(int attempt=0;attempt<3000;attempt++) {
                    assertEquals(expected,trust.identity(file));assertTrue(ProcessHandle.current().isAlive());assertTrue(monitor.isAlive(),"監視processが終了: "+attempt);
                }
            });
        }finally {
            Files.writeString(stop,"STOP");if(!monitor.waitFor(5,TimeUnit.SECONDS)){monitor.destroyForcibly();assertTrue(monitor.waitFor(5,TimeUnit.SECONDS));}
        }
        assertEquals(0,monitor.exitValue());
    }
    @Test void 回収時RuntimeExceptionも一次分類と診断数値を壊さない() throws Exception {
        var failure=cleanupFailure("NATIVE_SENTINEL_207");
        assertEquals(FileIdentityReader.Kind.INVALID_RESPONSE,failure.kind);assertNull(failure.getCause());assertEquals(19,failure.stdoutBytes);assertEquals(0,failure.exitCode);assertFalse(failure.collected);
        var cleanup=(FileIdentityReader.Failure)failure.getSuppressed()[0];assertEquals(FileIdentityReader.Kind.CLEANUP_FAILED,cleanup.kind);assertInstanceOf(IllegalStateException.class,cleanup.getCause());
        var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));assertFalse(text.toString().contains("NATIVE_SENTINEL_207"));
        String response="{\"schemaVersion\":1,\"volumeSerial\":\"12\",\"fileId\":\""+"a".repeat(32)+"\"}";
        failure=cleanupFailure(response);assertEquals(FileIdentityReader.Kind.CLEANUP_FAILED,failure.kind);assertInstanceOf(IllegalStateException.class,failure.getCause());assertEquals(response.length(),failure.stdoutBytes);assertEquals(0,failure.exitCode);assertFalse(failure.collected);
    }
    private FileIdentityReader.Failure cleanupFailure(String response) {
        Process child=new Process(){
            boolean alive,observed;
            public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
            public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            public InputStream getErrorStream(){return InputStream.nullInputStream();}
            public int waitFor(){return 0;}
            public int exitValue(){if(!observed){observed=true;alive=true;}return 0;}
            public boolean isAlive(){return alive;}
            public void destroy(){destroyForcibly();}
            public Process destroyForcibly(){alive=false;throw new IllegalStateException("回収fixtureの失敗");}
        };
        return assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(FileIdentityReader.Failure.class,()->FileIdentityReader.read(child,System.nanoTime()+TimeUnit.SECONDS.toNanos(2))));
    }
}
