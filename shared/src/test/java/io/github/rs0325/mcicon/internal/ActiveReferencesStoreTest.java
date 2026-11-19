package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActiveReferencesStoreTest {
    @TempDir Path root;
    private LeaseTrust trust() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");
        return IdentityFixtures.trust(TrustFixtures.trust(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic()));
    }
    private void prepare() throws Exception {root=root.toRealPath();Files.createDirectory(root.resolve("locks"));Files.writeString(root.resolve("host-id"),UUID.randomUUID().toString());}
    @Test void 原子的snapshotの旧revisionで更新せず元stateを保持する() throws Exception {
        prepare();LeaseTrust trust=trust();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();var references=new ActiveReferences(root,trust,end);
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            var first=references.publish(null,references.initial("c".repeat(64),UUID.randomUUID().toString()),held,end);
            var next=first.payload().put("revision",2);references.publish(first,next,held,end);
            IOException failure=assertThrows(IOException.class,()->references.publish(first,next,held,end));assertTrue(failure.getMessage().contains("CAS"));assertEquals(2,references.read(end).payload().path("revision").asLong());
        }
    }
    @Test void 同じbytesでもfile置換した古いsnapshotをCASへ使わない() throws Exception {
        prepare();LeaseTrust trust=trust();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();var references=new ActiveReferences(root,trust,end);
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            var first=references.publish(null,references.initial("c".repeat(64),UUID.randomUUID().toString()),held,end);
            Path file=root.resolve("logs/active-references-v1/state.json"),replacement=file.resolveSibling("replace.part");Files.copy(file,replacement);Files.move(replacement,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var next=first.payload().put("revision",2);assertThrows(IOException.class,()->references.publish(first,next,held,end));assertEquals(1,references.read(end).payload().path("revision").asLong());
        }
    }
    @Test void 未終端epoch変更と通常boot変更を公開しない() throws Exception {
        prepare();LeaseTrust trust=trust();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();var references=new ActiveReferences(root,trust,end);
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            var value=references.initial("c".repeat(64),UUID.randomUUID().toString());var identity=ActiveReferencesTest.identity().put("hostId",Files.readString(root.resolve("host-id")).trim());value.withArray("entries").add(ActiveReferences.reserved(identity,ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64)));
            var first=references.publish(null,value,held,end);
            var next=first.payload().put("revision",2).put("epoch",UUID.randomUUID().toString());assertThrows(IOException.class,()->references.publish(first,next,held,end));
            var changedBoot=first.payload().put("revision",2).put("bootSha256","d".repeat(64));assertThrows(IOException.class,()->references.publish(first,changedBoot,held,end));
            changedBoot.put("phase","recovering");var recovered=references.publish(first,changedBoot,held,end);assertEquals(first.payload().path("epoch"),recovered.payload().path("epoch"));assertEquals("recovering",references.read(end).payload().path("phase").asText());
        }
    }
    @Test void capture終了前のCASを拒否し終了後のreadbackは新identityを使う() throws Exception {
        prepare();LeaseTrust trust=trust();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();var references=new ActiveReferences(root,trust,end);
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            var first=references.publish(null,references.initial("c".repeat(64),UUID.randomUUID().toString()),held,end);
            Path file=root.resolve("logs/active-references-v1/state.json");
            var next=first.payload().put("revision",2);
            try(var capture=trust.captureIdentities(root,held,java.util.List.of(file),root,end)) {
                capture.revalidate();
                assertThrows(IOException.class,()->references.publish(first,next,held,end));
                assertEquals(1,references.read(end).payload().path("revision").asLong());
            }
            var second=references.publish(first,next,held,end);
            var third=references.publish(second,second.payload().put("revision",3),held,end);
            assertEquals(3,third.payload().path("revision").asLong());
        }
    }
    @Test void 同じsnapshotからの同時予約は片方だけをCAS公開する() throws Exception {
        prepare();LeaseTrust trust=trust();long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();var references=new ActiveReferences(root,trust,end);
        ActiveReferences.State initial;
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            initial=references.publish(null,references.initial("c".repeat(64),UUID.randomUUID().toString()),held,end);
        }
        var start=new java.util.concurrent.CountDownLatch(1);var tasks=new java.util.ArrayList<java.util.concurrent.FutureTask<IOException>>();var threads=new java.util.ArrayList<Thread>();
        for(int index=0;index<2;index++) {
            var identity=ActiveReferencesTest.identity().put("hostId",Files.readString(root.resolve("host-id")));
            var task=new java.util.concurrent.FutureTask<IOException>(()->{
                start.await();
                try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
                    references.reserve(initial,identity,ActiveReferencesTest.generation(),UUID.randomUUID().toString(),"c".repeat(64),held,end);return null;
                }catch(IOException failure){return failure;}
            });tasks.add(task);threads.add(Thread.ofPlatform().start(task));
        }
        start.countDown();int successes=0;
        try {for(var task:tasks){IOException failure=task.get(15,java.util.concurrent.TimeUnit.SECONDS);if(failure==null)successes++;else assertTrue(failure.getMessage().contains("CAS"));}}
        finally {for(Thread thread:threads){if(thread.isAlive())thread.interrupt();thread.join(Duration.ofSeconds(2));assertFalse(thread.isAlive());}}
        assertEquals(1,successes);assertEquals(1,references.read(end).payload().path("entries").size());
    }
    @Test void 初期認証からCASとreadbackまで元deadlineをreaderへ渡す() throws Exception {
        prepare();var generator=KeyPairGenerator.getInstance("Ed25519");var keys=TrustFixtures.trust(generator.generateKeyPair().getPublic(),generator.generateKeyPair().getPublic());
        LeaseTrust delegate=IdentityFixtures.trust(keys);var deadlines=new java.util.ArrayList<Long>();
        class Reader implements LeaseTrust.IdentityReader,LeaseTrust.BatchIdentityReader {
            public String read(Path path){throw new AssertionError("期限なしscalarを呼びました");}
            public java.util.Map<Path,String> readAll(java.util.List<Path> paths,Path scratch,long end) throws IOException {deadlines.add(end);return delegate.readIdentities(paths,scratch,end);}
        }
        LeaseTrust trust=LeaseTrust.verified(keys,new Reader());long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        assertThrows(IOException.class,()->new ActiveReferences(root,trust,System.nanoTime()-1));assertTrue(deadlines.isEmpty());
        var references=new ActiveReferences(root,trust,end);
        try(var held=RuntimeLock.acquire(root.resolve("locks/generations.lock"),end,path->trust.fileIdentity(path,root,end))) {
            references.publish(null,references.initial("c".repeat(64),UUID.randomUUID().toString()),held,end);references.read(end);
        }
        assertTrue(deadlines.size()>5);assertTrue(deadlines.stream().allMatch(value->value==end));
    }
}
