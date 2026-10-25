package io.github.rs0325.mcicon.helper.windows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipTest {
    @TempDir Path temporary;
    static final String BOOT="00000000-0000-0000-0000-000000000001",DEFAULT="00000000-0000-0000-0000-000000000002";
    ObjectNode lease(){return WindowsFiles.JSON.createObjectNode().put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString()).put("hostId",UUID.randomUUID().toString()).put("descriptorSha256","a".repeat(64));}
    ObjectNode descriptor(){ObjectNode value=WindowsFiles.JSON.createObjectNode().put("minimumOsVersion","10.0.19045");value.putObject("host").put("os","windows").put("arch","x86_64");return value;}
    HostFacts host(String defaultGuid,List<HostFacts.Distribution> rows){return new HostFacts("S-1-5-21-1",BOOT,"10.0.26200",16L*1024*1024*1024,8,defaultGuid,rows);}
    @Test void GUIDとBasePathとSIDの一致を全て必要とする() throws Exception {
        Path root=temporary.toRealPath();ObjectNode lease=lease();HostFacts original=host(DEFAULT,List.of(new HostFacts.Distribution("existing",DEFAULT,"C:\\existing",2)));WslDistribution distribution=new WslDistribution(root,lease,original);ObjectNode owner=distribution.reserve(original);Files.createDirectory(distribution.basePath);
        String guid=UUID.randomUUID().toString();HostFacts registered=host(DEFAULT,List.of(original.distributions().getFirst(),new HostFacts.Distribution(distribution.name,guid,distribution.basePath.toString(),2)));distribution.registered(owner,registered,"b".repeat(64));assertEquals(guid,distribution.verify(registered).path("guid").asText());
        assertThrows(IOException.class,()->distribution.verify(host(DEFAULT,List.of(new HostFacts.Distribution(distribution.name,UUID.randomUUID().toString(),distribution.basePath.toString(),2)))));
        assertThrows(IOException.class,()->distribution.verify(host(DEFAULT,List.of(new HostFacts.Distribution(distribution.name,guid,root.resolve("other").toString(),2)))));
        HostFacts foreign=new HostFacts("S-1-5-21-2",BOOT,original.version(),original.memoryBytes(),8,DEFAULT,registered.distributions());assertThrows(IOException.class,()->new WslDistribution(root,lease,foreign).verify(foreign));
    }
    @Test void 初回default不在と名前衝突と曖昧importは採用しない() throws Exception {
        WslDistribution distribution=new WslDistribution(temporary.toRealPath(),lease(),host("",List.of()));assertThrows(IOException.class,()->distribution.reserve(host("",List.of())));assertFalse(distribution.exists());
        HostFacts normal=host(DEFAULT,List.of(new HostFacts.Distribution("existing",DEFAULT,"C:\\existing",2)));distribution.reserve(normal);assertThrows(IOException.class,()->distribution.reserve(normal));assertThrows(IOException.class,()->distribution.verify(normal));
    }
    @Test void native能力の未知bootと登録欠落と重複は拒否する() throws Exception {
        ObjectNode value=WindowsFiles.JSON.createObjectNode().put("sid","S-1-5-21-1").put("boot",BOOT).put("version","10.0.26200").put("arch","x86_64").put("memoryBytes",16L<<30).put("cpus",8).put("defaultGuid",DEFAULT);value.putArray("distributions").addObject().put("name","existing").put("guid",DEFAULT).put("basePath","C:\\existing").put("version",2);assertEquals(BOOT,HostFacts.parse(value,descriptor()).boot());
        value.put("boot","uptime-estimate");assertThrows(IOException.class,()->HostFacts.parse(value,descriptor()));value.put("boot",BOOT);value.withArray("distributions").removeAll();assertThrows(IOException.class,()->HostFacts.parse(value,descriptor()));
    }
    @Test void journalの未確定生成をclient終了だけでreleaseしない() throws Exception {
        ObjectNode lease=lease();try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),lease,Duration.ofSeconds(1))){assertTrue(journal.unstarted());journal.begin("create",BOOT);assertThrows(IOException.class,journal::release);assertThrows(IOException.class,journal::settledRequired);}
        try(OperationJournal journal=OperationJournal.acquire(temporary.toRealPath(),lease,Duration.ofSeconds(1))){assertTrue(journal.pending());journal.recovered(WindowsFiles.JSON.createObjectNode().put("closed",true));journal.release();assertThrows(IOException.class,journal::active);}
        lease.put("ownerId",UUID.randomUUID().toString());assertThrows(IOException.class,()->OperationJournal.acquire(temporary.toRealPath(),lease,Duration.ofMillis(50)));
    }
    @Test void hostが足りても三jobのguest実容量不足を拒否する() throws Exception {
        long hostBytes=16L*1024*1024*1024,guestBytes=8L*1024*1024*1024;
        assertTrue(hostBytes>GuestBudget.requiredBytes(3072,6144));assertEquals(10L*1024*1024*1024,GuestBudget.requiredBytes(3072,6144));assertThrows(IOException.class,()->GuestBudget.require(guestBytes,GuestBudget.requiredBytes(3072,6144)));
        GuestBudget.require(guestBytes,GuestBudget.requiredBytes(3072,3072));GuestBudget.require(12L*1024*1024*1024,GuestBudget.requiredBytes(3072,6144));assertThrows(IOException.class,()->GuestBudget.requiredBytes(Long.MAX_VALUE,3072));
    }
    @Test void WSLのUTF16一覧を文字列として読み不正encodingは拒否する() throws Exception {
        assertEquals(Set.of("専用 distro","docker-desktop"),WindowsRuntime.runningNames("専用 distro\r\ndocker-desktop\r\n".getBytes(StandardCharsets.UTF_16LE)));
        assertThrows(IOException.class,()->WindowsRuntime.runningNames(new byte[]{'a',0,'b'}));assertEquals(Set.of(),WindowsRuntime.runningNames(new byte[0]));
    }
}
