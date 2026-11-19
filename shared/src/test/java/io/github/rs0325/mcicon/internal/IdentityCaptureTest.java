package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCaptureTest {
    @TempDir Path root;

    private LeaseTrust trust() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");
        return IdentityFixtures.trust(TrustFixtures.trust(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic()));
    }
    private Path lock() throws Exception {
        root=root.toRealPath();Files.createDirectory(root.resolve("locks"));
        return root.resolve("locks/generations.lock");
    }
    @Test void 同じroot排他区間だけで観測し対象外へfallbackしない() throws Exception {
        LeaseTrust trust=trust();Path lock=lock(),file=Files.writeString(root.resolve("file"),"original"),other=Files.writeString(root.resolve("other"),"other");
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var held=RuntimeLock.acquire(lock,end,path->trust.fileIdentity(path,root,end))) {
            try(var capture=trust.captureIdentities(root,held,List.of(file),root,end)) {
                assertNotNull(trust.fileIdentity(file));
                assertThrows(IOException.class,()->trust.fileIdentity(other));
                assertThrows(IOException.class,()->trust.captureIdentities(root,held,List.of(file),root,end));
                capture.revalidate();
            }
            assertNotNull(trust.fileIdentity(other));
        }
    }
    @Test void 同じbytesのfile置換も終端確定前に検出する() throws Exception {
        LeaseTrust trust=trust();Path lock=lock(),file=Files.writeString(root.resolve("file"),"original");
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var held=RuntimeLock.acquire(lock,end,path->trust.fileIdentity(path,root,end));
            var capture=trust.captureIdentities(root,held,List.of(file),root,end)) {
            Path replacement=Files.writeString(root.resolve("replacement"),"original");
            Files.move(replacement,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IOException.class,capture::revalidate);
        }
    }
    @Test void 元lock解放後の再検証を拒否する() throws Exception {
        LeaseTrust trust=trust();Path lock=lock(),file=Files.writeString(root.resolve("file"),"original");
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var held=RuntimeLock.acquire(lock,end,path->trust.fileIdentity(path,root,end));
            var capture=trust.captureIdentities(root,held,List.of(file),root,end)) {
            held.close();assertThrows(IOException.class,()->trust.fileIdentity(file));assertThrows(IOException.class,capture::revalidate);
        }
    }
    @Test void 期限超過と割込みは取得前に拒否し中断状態を保持する() throws Exception {
        LeaseTrust trust=trust();Path lock=lock(),file=Files.writeString(root.resolve("file"),"original");
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var held=RuntimeLock.acquire(lock,end,path->trust.fileIdentity(path,root,end))) {
            assertThrows(IOException.class,()->trust.captureIdentities(root,held,List.of(file),root,System.nanoTime()-1));
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class,()->trust.captureIdentities(root,held,List.of(file),root,end));
                assertTrue(Thread.currentThread().isInterrupted());
            }finally {Thread.interrupted();}
            try(var capture=trust.captureIdentities(root,held,List.of(file),root,end)){capture.revalidate();}
        }
    }
    @Test void scopeが256を超えても同一rootと同一期限で順次batchする() throws Exception {
        LeaseTrust trust=trust();Path lock=lock();var files=new java.util.ArrayList<Path>();
        for(int index=0;index<257;index++)files.add(Files.writeString(root.resolve("file-"+index),"fixture"));
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        try(var held=RuntimeLock.acquire(lock,end,path->trust.fileIdentity(path,root,end));
            var capture=trust.captureIdentities(root,held,files,root,end)) {
            for(Path file:files)assertNotNull(trust.fileIdentity(file));
            capture.revalidate();
        }
        assertTrue(System.nanoTime()<end);
    }
}
