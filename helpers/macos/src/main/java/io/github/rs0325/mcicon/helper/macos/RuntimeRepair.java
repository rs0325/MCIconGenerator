package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.HelperProtocol;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** 既知旧版のStopped終端だけを確定する明示保守入口。旧helperは実行しない。 */
final class RuntimeRepair {
    private RuntimeRepair() {}
    static void run(String[] arguments,Path executable) throws IOException {
        if(arguments.length!=5||!arguments[1].equals("--runtime-root")||!arguments[3].equals("--job"))throw new IOException("repairの引数が不正です");
        Path root=Path.of(arguments[2]);String job=arguments[4];
        try{if(!UUID.fromString(job).toString().equals(job))throw new IllegalArgumentException();}catch(IllegalArgumentException failure){throw new IOException("job UUIDが不正です",failure);}
        if(!root.isAbsolute()||!root.toRealPath().equals(root))throw new IOException("runtime rootはlinkを含まない絶対pathが必要です");
        Path file=root.resolve("jobs").resolve(job).resolve("lease.json"),temporary=file.getParent().resolve("tmp");
        Path support=executable.getParent().getParent().resolve("app/support");
        LeaseTrust trust=LeaseTrust.pinned(TrustSet.parse(Files.readAllBytes(support.resolve("lease-trust.bin"))),null,null);
        LeaseSnapshot snapshot=LeaseSnapshot.read(root,file,trust);
        if(!snapshot.descriptor().path("host").path("os").asText().equals("macos")||!Set.of("1.1.0","1.1.1").contains(snapshot.lease().path("toolVersion").asText())||!LeaseTrust.legacy(snapshot.lease().path("descriptorSha256").asText()))throw new IOException("既知Mac旧版以外は保守できません");
        if(snapshot.lease().path("state").asText().equals("released")){RuntimeMaintenanceVerifier.requireReleased(snapshot);return;}
        try(FileChannel channel=FileChannel.open(file.resolveSibling("lease.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock active;try{active=channel.tryLock(64,1,false);}catch(OverlappingFileLockException busy){throw new IOException("元jobのactive lockが使用中です",busy);}
            if(active==null)throw new IOException("元jobのactive lockが使用中です");
            try {
                snapshot.verifyUnchanged(channel);ObjectNode value=snapshot.lease();if(RuntimeMaintenanceVerifier.live(value))throw new IOException("元coreが生存しています");
                if(!temporary.toRealPath().equals(temporary))throw new IOException("元job tmpにlinkがあります");
                Payload payload=new Payload(support);payload.verify();Map<String,String> environment=new HashMap<>();for(String key:List.of("TMP","TEMP","TMPDIR"))environment.put(key,temporary.toString());
                Commands bootstrap=new Commands(payload.path("process-guard"),environment,temporary);HostFacts host=HostFacts.read(payload.path("host-probe"),bootstrap,snapshot.descriptor(),value.path("hostId").asText());
                CacheState cache=new CacheState(root,value.path("hostId").asText(),value.path("descriptorSha256").asText(),host.uid(),host.hostBindingSha256(),host.bootSessionId());
                environment.put("HOME",cache.home().toString());environment.put("LIMA_HOME",cache.limaHome().toString());environment.put("DOCKER_CONFIG",cache.anchor().resolve("d").toString());environment.put("PATH","/usr/bin:/bin:/usr/sbin:/sbin");environment.put("LC_ALL","C");
                Commands commands=new Commands(payload.path("process-guard"),environment,temporary);
                String relative=Path.of(snapshot.descriptor().path("helper").path("executable").asText()).getParent().getParent().resolve("app/support/bin/limactl").toString().replace('\\','/');
                snapshot.helperFile(relative);
                var command=new HelperProtocol.Command(root,file,"release",List.of());
                try(OperationJournal journal=OperationJournal.acquire(temporary,value,Duration.ofSeconds(5));CacheState.Lock ignored=cache.lock(Duration.ofSeconds(5))) {
                    snapshot.verifyUnchanged(channel);
                    ObjectNode observed=value.deepCopy();if(value.path("resources").isEmpty())observed.withObject("resources").set("macos",journal.recoveredMac());
                    if(!StoppedRecovery.recover(command,executable,observed,cache,host,payload,commands,trust,journal))throw new IOException("限定回復できるstopがありません");
                    snapshot.verifyUnchanged(channel);
                    Path update=temporary.resolve("helper-lease.lock");
                    try(FileChannel updates=FileChannel.open(update,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=updates.tryLock()) {
                        if(held==null)throw new IOException("元leaseの更新が使用中です");snapshot.verifyUnchanged(channel);
                        value.putObject("resources");writeLease(file,temporary,value);
                        LeaseSnapshot cleared=LeaseSnapshot.read(root,file,trust,channel);if(!cleared.lease().equals(value))throw new IOException("保守resourcesのCAS後にleaseが変化しました");cleared.verifyUnchanged(channel);
                        value.put("state","released");writeLease(file,temporary,value);
                    }
                }
            }finally{active.release();}
        }
    }
    private static void writeLease(Path file,Path temporary,ObjectNode value) throws IOException {
        Path staged=Files.createTempFile(temporary,"repair-lease-",".json");
        try{try(FileChannel output=FileChannel.open(staged,StandardOpenOption.WRITE)){var bytes=java.nio.ByteBuffer.wrap(MacFiles.JSON.writeValueAsBytes(value));while(bytes.hasRemaining())output.write(bytes);output.force(true);}Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(FileChannel directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}}
        finally{Files.deleteIfExists(staged);}
    }
}
