package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.helper.LeaseFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LeaseReferencesTest {
    @TempDir Path temporary;
    @Test void 全世代の未知とbusyを停止許可へ変換しない() throws Exception {
        Path root=temporary.toRealPath();var fixture=new MacRuntimeTest.Fixture(root);Path jobs=root.resolve("jobs");var command=fixture.job(false).command();
        String hostId=fixture.host;
        assertTrue(LeaseReferences.inspect(command,fixture.executable,fixture.digest).stopAllowed());
        Path other=Files.createDirectory(jobs.resolve(UUID.randomUUID().toString()));assertTrue(LeaseReferences.inspect(command,fixture.executable,fixture.digest).unknown());
        ObjectNode lease=MacFiles.JSON.createObjectNode();lease.put("schemaVersion",1).put("jobId",other.getFileName().toString()).put("ownerId",UUID.randomUUID().toString()).put("toolVersion","1.1.0").put("workerSha256","a".repeat(64)).put("runtimeId","other").put("descriptorSha256","b".repeat(64)).put("hostId",hostId).put("pid",123).put("processStartedAt","2026-01-01T00:00:00Z").put("createdAt","2026-01-01T00:00:00Z");lease.putObject("requirements").put("containerMemoryMiB",1024).put("heapMemoryMiB",512).put("cpus",4).put("preparationDeadline","2026-01-01T01:00:00Z");lease.put("state","released");lease.putObject("resources");
        ObjectNode identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(other.resolve("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(other.resolve("lease.json"),lease);
        assertTrue(LeaseReferences.inspect(command,fixture.executable,fixture.digest).stopAllowed());
        lease.put("hostId",UUID.randomUUID().toString());identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(other.resolve("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(other.resolve("lease.json"),lease);assertTrue(LeaseReferences.inspect(command,fixture.executable,fixture.digest).unknown());
        lease.put("hostId",hostId);identity=lease.deepCopy();identity.remove(List.of("state","resources"));Files.write(other.resolve("lease.lock"),LeaseFile.receipt(identity));MacFiles.write(other.resolve("lease.json"),lease);
        try(FileChannel channel=FileChannel.open(other.resolve("lease.lock"),StandardOpenOption.WRITE);var lock=channel.lock(64,1,false)) {assertFalse(LeaseReferences.inspect(command,fixture.executable,fixture.digest).stopAllowed());}
        lease.put("state","active");MacFiles.write(other.resolve("lease.json"),lease);var active=LeaseReferences.inspect(command,fixture.executable,fixture.digest);assertTrue(active.active());assertTrue(active.unknown());assertFalse(active.stopAllowed());
    }
}
