package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WindowsAdmissionTest {
    @TempDir Path directory;
    @Test void 旧readerがliveの間は候補jobを公開せず終了後に作成できる() throws Exception {
        Fixture f=new Fixture(directory);
        try(RuntimeLease old=f.oldJob()) {
            long before=f.jobs();WindowsAdmission gate=f.gate();
            ManagedBusyFailure busy=assertThrows(ManagedBusyFailure.class,()->RuntimeLease.create(f.store,f.candidate,f.options,Instant.now().plusSeconds(30),gate));
            assertEquals(before,f.jobs());assertEquals(2,busy.admission().size());assertTrue(Files.isRegularFile(AdmissionEvidence.preAdmission(f.store.root(),busy.admission().path("attemptId").asText())));
            old.released();old.close();
            try(RuntimeLease current=RuntimeLease.create(f.store,f.candidate,f.options,Instant.now().plusSeconds(30),gate)){assertEquals(before+1,f.jobs());current.released();}
        }
    }
    @Test void receipt不一致を既知busyに変換せず候補も作らない() throws Exception {
        Fixture f=new Fixture(directory);try(RuntimeLease old=f.oldJob()) {
            Files.writeString(old.file().resolveSibling("lease.lock"),"0".repeat(64));long before=f.jobs();
            IOException failure=assertThrows(IOException.class,()->RuntimeLease.create(f.store,f.candidate,f.options,Instant.now().plusSeconds(30),f.gate()));
            assertFalse(failure instanceof ManagedBusyFailure);assertEquals(before,f.jobs());assertFalse(Files.exists(f.store.root().resolve("logs"))&&f.admissions()>0);
        }
    }
    @Test void deadactiveの自己整合記録もbusyにしない() throws Exception {
        Fixture f=new Fixture(directory);try(RuntimeLease old=f.oldJob()) {
            ObjectNode value=old.read();value.put("processStartedAt",Instant.EPOCH.toString());ObjectNode identity=value.deepCopy();identity.remove(List.of("state","resources"));
            Files.write(old.file(),RuntimeDescriptor.JSON.writeValueAsBytes(value));Files.write(old.file().resolveSibling("lease.lock"),RuntimeLease.receipt(identity));long before=f.jobs();
            IOException failure=assertThrows(IOException.class,()->RuntimeLease.create(f.store,f.candidate,f.options,Instant.now().plusSeconds(30),f.gate()));
            assertFalse(failure instanceof ManagedBusyFailure);assertEquals(before,f.jobs());
        }
    }
    static final class Fixture {
        final ManagedFixture source=new ManagedFixture();
        final RuntimeStore store;
        final ManagedOptions options;
        final RuntimeDescriptor old,candidate;
        final byte[] inventory,oldBytes,candidateBytes;
        final LeaseTrust trust;
        Fixture(Path root) throws Exception {
            store=new RuntimeStore(root,true,new CancellationToken(),Duration.ofSeconds(30),(u,o,t)->{throw new AssertionError();});
            options=new ManagedOptions(store.root(),Duration.ofSeconds(30),Duration.ofSeconds(2),Duration.ofSeconds(1),3072,2048);
            ((ObjectNode)source.json.path("host")).put("os","windows");source.json.put("minimumOsVersion","10.0");
            ObjectNode listing=RuntimeDescriptor.JSON.createObjectNode();listing.putObject("contents").putArray("files").addObject().put("path","bin/helper").put("bytes",7).put("sha256",FilesSupport.hash("fixture".getBytes()));
            inventory=RuntimeDescriptor.JSON.writeValueAsBytes(listing);
            for(var artifact:source.json.path("artifacts"))((ObjectNode)artifact).put("inventorySha256",FilesSupport.hash(inventory));
            oldBytes=source.bytes();old=descriptor(oldBytes,"1.1.0");publish(oldBytes);
            source.json.put("toolVersion","1.1.2");((ObjectNode)source.json.path("worker")).put("toolVersion","1.1.2");
            for(var artifact:source.json.path("artifacts"))if(artifact.path("role").asText().equals("host-helper"))((ObjectNode)artifact).put("sha256","d".repeat(64));
            candidateBytes=source.bytes();candidate=descriptor(candidateBytes,"1.1.2");publish(candidateBytes);
            Path helper=store.root().resolve("helpers/windows-x86_64/"+ManagedFixture.HASH+"/bin/helper");Files.createDirectories(helper.getParent());Files.writeString(helper,"fixture");
            Path newer=store.root().resolve("helpers/windows-x86_64/"+"d".repeat(64)+"/bin/helper");Files.createDirectories(newer.getParent());Files.writeString(newer,"fixture");
            trust=IdentityFixtures.trust(source.trust);
        }
        RuntimeDescriptor descriptor(byte[] bytes,String version) throws Exception {return RuntimeDescriptor.verify(bytes,source.sign(bytes),source.trust,new RuntimeDescriptor.Pin(FilesSupport.hash(bytes),version,ManagedFixture.HASH,ManagedFixture.HASH,new RuntimeDescriptor.Host("windows","x86_64"),Set.of(21)));}
        void publish(byte[] bytes) throws Exception {LeaseTrust.publish(store.root(),bytes,source.sign(bytes),source.trust);}
        RuntimeLease oldJob() throws Exception {
            RuntimeLease lease=RuntimeLease.create(store,old,options,Instant.now().plusSeconds(30));Path job=lease.file().getParent();Files.createDirectory(job.resolve("tmp"));Files.createDirectory(job.resolve("inventory"));
            writeJob(lease,oldBytes);return lease;
        }
        void writeJob(RuntimeLease lease,byte[] definition) throws Exception {Path job=lease.file().getParent();Files.createDirectories(job.resolve("tmp"));Files.createDirectories(job.resolve("inventory"));Files.write(job.resolve("descriptor.json"),definition);for(String name:List.of("helper","image"))Files.write(job.resolve("inventory/"+name+".json"),inventory);}
        WindowsAdmission gate(){return new WindowsAdmission(store,candidate,trust,Set.of(old.digest()));}
        long jobs() throws Exception {try(var entries=Files.list(store.root().resolve("jobs"))){return entries.count();}}
        long admissions() throws Exception {try(var entries=Files.list(store.root().resolve("logs"))){return entries.filter(p->p.toString().endsWith(".pre-admission-v1.json")).count();}}
    }
}
