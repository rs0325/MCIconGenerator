package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCaptureSnapshotTest {
    @TempDir Path root;

    @Test void identityが同じでもlease元bytesの変更を検出する() throws Exception {changed("lease");}
    @Test void identityが同じでもreceipt元bytesの変更を検出する() throws Exception {changed("receipt");}
    @Test void scope内で一度検証したhelperも確定前に再hashする() throws Exception {changed("helper");}
    @Test void 二度読んだ同fileの元snapshotを差替えない() throws Exception {changed("reread");}
    @Test void 非JARのsupport実行fileも同size同identity改変を確定前に拒否する() throws Exception {
        var fixture=new WindowsAdmissionTest.Fixture(root,true);root=fixture.store.root();
        LeaseTrust observer=IdentityFixtures.trust(fixture.source.trust);
        Path support=root.resolve("helpers/windows-x86_64/"+ManagedFixture.HASH+"/support/tool");
        String original=observer.fileIdentity(support);long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        try(var lease=fixture.oldJob();var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->fixture.trust.fileIdentity(path,root,end))) {
            List<Path> paths;try(var walk=Files.walk(root)){paths=walk.toList();}
            try(var capture=fixture.trust.captureIdentities(root,held,paths,root,end)) {
                LeaseSnapshot snapshot=LeaseSnapshot.read(root,lease.file(),fixture.trust,lease.receiptChannel());
                assertEquals(support,snapshot.helperFile("support/tool"));snapshot.helperFile("support/tool");capture.revalidate();
                Files.writeString(support,"fixturE");assertEquals(7,Files.size(support));assertEquals(original,observer.fileIdentity(support));
                IOException failure=assertThrows(IOException.class,capture::revalidate);assertTrue(failure.getMessage().contains("hash"));
            }
        }
    }

    private void changed(String target) throws Exception {
        var fixture=new WindowsAdmissionTest.Fixture(root);
        root=fixture.store.root();
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        try(var lease=fixture.oldJob();
            var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,
                    path->fixture.trust.fileIdentity(path,root,end))) {
            List<Path> paths;
            try(var walk=Files.walk(root)){paths=walk.toList();}
            try(var capture=fixture.trust.captureIdentities(root,held,paths,root,end)) {
                LeaseSnapshot.read(root,lease.file(),fixture.trust,lease.receiptChannel());
                capture.revalidate();
                Path file=switch(target) {
                    case "lease","reread" -> lease.file();
                    case "receipt" -> lease.file().resolveSibling("lease.lock");
                    case "helper" -> root.resolve("helpers/windows-x86_64/"+ManagedFixture.HASH+"/bin/helper");
                    default -> throw new AssertionError(target);
                };
                String before=fixture.trust.fileIdentity(file);
                if(target.equals("helper"))Files.writeString(file,"fixturE");
                else if(target.equals("receipt"))lease.receiptChannel().write(java.nio.ByteBuffer.wrap("0".repeat(64).getBytes(java.nio.charset.StandardCharsets.US_ASCII)),0);
                else Files.writeString(file," ",StandardOpenOption.APPEND);
                assertEquals(before,fixture.trust.fileIdentity(file));
                if(target.equals("reread"))LeaseSnapshot.read(root,lease.file(),fixture.trust,lease.receiptChannel());
                assertThrows(IOException.class,capture::revalidate);
            }
        }
    }
}
