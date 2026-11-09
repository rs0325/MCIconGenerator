package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 旧coreも取得するgenerations lock内で、候補job公開より先に旧readerを確認する。 */
final class WindowsAdmission {
    private final RuntimeStore store;
    private final RuntimeDescriptor descriptor;
    private final LeaseTrust trust;
    private final Set<String> legacy;
    WindowsAdmission(RuntimeStore store,RuntimeDescriptor descriptor,Path executable,Map<String,byte[]> inventories,byte[] original) throws IOException {
        this.store=store;this.descriptor=descriptor;legacy=LeaseTrust.legacyDescriptors();trust=LeaseTrust.packaged(executable,"windows");
        trust.bindReader(store.root(),original,inventories.get(descriptor.artifact(descriptor.helperArtifact()).inventoryPath()),executable);
    }
    WindowsAdmission(RuntimeStore store,RuntimeDescriptor descriptor,LeaseTrust trust,Set<String> legacy){this.store=store;this.descriptor=descriptor;this.trust=trust;this.legacy=Set.copyOf(legacy);}
    LeaseTrust trust(){return trust;}
    void check() throws IOException {
        Path root=store.root(),jobs=root.resolve("jobs");if(!Files.exists(jobs,LinkOption.NOFOLLOW_LINKS))return;
        List<LeaseSnapshot> blockers=new ArrayList<>();
        if(!Files.isDirectory(jobs,LinkOption.NOFOLLOW_LINKS)||!jobs.toRealPath().equals(jobs))throw new IOException("job一覧が不明です");
        try(var entries=Files.newDirectoryStream(jobs)) {
            for(Path directory:entries) {
                store.check();LeaseSnapshot snapshot=LeaseSnapshot.read(root,directory.resolve("lease.json"),trust);
                if(!snapshot.descriptor().path("host").equals(RuntimeDescriptor.JSON.valueToTree(descriptor.host())))throw new IOException("参照leaseのhostが一致しません");
                if(snapshot.lease().path("state").asText().equals("active")) {
                    RuntimeMaintenanceVerifier.requireLive(snapshot);
                    String digest=snapshot.lease().path("descriptorSha256").asText();
                    if(legacy.contains(digest)&&!digest.equals(descriptor.digest()))blockers.add(snapshot);
                }else RuntimeMaintenanceVerifier.requireReleased(snapshot);
            }
        }
        if(blockers.isEmpty())return;
        String attempt=UUID.randomUUID().toString();ObjectNode value=RuntimeDescriptor.JSON.createObjectNode().put("schemaVersion",1).put("kind","pre-admission").put("cause","LEGACY_READER_ACTIVE").put("attemptId",attempt).put("root",root.toString()).put("hostId",Files.readString(root.resolve("host-id"),StandardCharsets.US_ASCII).trim()).put("pid",ProcessHandle.current().pid()).put("processStartedAt",ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("元core開始identityが不明です")).toString()).put("descriptorSha256",descriptor.digest());
        var proofs=value.putArray("references");for(LeaseSnapshot blocker:blockers){RuntimeMaintenanceVerifier.requireLive(blocker);proofs.add(RuntimeMaintenanceVerifier.proof(blocker));}
        store.check();AdmissionEvidence.save(AdmissionEvidence.preAdmission(root,attempt),value,trust);
        throw new ManagedBusyFailure(AdmissionEvidence.reference(root,attempt));
    }
}
