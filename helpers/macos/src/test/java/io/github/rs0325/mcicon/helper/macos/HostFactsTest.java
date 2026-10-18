package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostFactsTest {
    @Test void 実証対象と導入済み能力とdescriptor下限だけを受け入れる() throws Exception {
        ObjectNode descriptor=MacFiles.JSON.createObjectNode().put("minimumOsVersion","13");descriptor.putObject("host").put("os","macos").put("arch","aarch64");
        ObjectNode facts=MacFiles.JSON.createObjectNode().put("arch","aarch64").put("vz",true).put("rosetta","installed").put("osVersion","15.7.1").put("uid",501).put("physicalMemory",16L*1024*1024*1024).put("cpus",8).put("bootSessionId","00000000-0000-0000-0000-000000000001").put("hostBindingSha256","a".repeat(64));
        assertEquals(501,HostFacts.parse(facts,descriptor).uid());
        facts.put("rosetta","not-installed");assertThrows(IOException.class,()->HostFacts.parse(facts,descriptor));facts.put("rosetta","installed");
        facts.put("arch","x86_64");assertThrows(IOException.class,()->HostFacts.parse(facts,descriptor));facts.put("arch","aarch64");
        descriptor.put("minimumOsVersion","16.0");assertThrows(IOException.class,()->HostFacts.parse(facts,descriptor));
    }
    @Test void binding欠落とboot不正は値を診断に含めず拒否する() throws Exception {
        ObjectNode descriptor=MacFiles.JSON.createObjectNode().put("minimumOsVersion","13");descriptor.putObject("host").put("os","macos").put("arch","aarch64");
        ObjectNode value=MacFiles.JSON.createObjectNode().put("arch","aarch64").put("vz",true).put("rosetta","installed").put("osVersion","15.7.1").put("uid",501).put("physicalMemory",16L*1024*1024*1024).put("cpus",8).put("bootSessionId","00000000-0000-0000-0000-000000000001");
        assertThrows(IOException.class,()->HostFacts.parse(value,descriptor));value.put("hostBindingSha256","A".repeat(64));assertThrows(IOException.class,()->HostFacts.parse(value,descriptor));
        value.put("hostBindingSha256","a".repeat(64)).put("bootSessionId","private-fixture-boot");IOException failure=assertThrows(IOException.class,()->HostFacts.parse(value,descriptor));assertFalse(failure.getMessage().contains("private-fixture-boot"));assertFalse(failure.getMessage().contains("a".repeat(64)));
    }
    @Test void 実probeの保存stdoutを現契約で解析する() throws Exception {
        String file=System.getProperty("mcicon.macosProbeFacts");org.junit.jupiter.api.Assumptions.assumeTrue(file!=null,"実probeの保存stdoutが指定された場合だけ実行する");
        var raw=MacFiles.json(java.nio.file.Path.of(file));ObjectNode descriptor=MacFiles.JSON.createObjectNode().put("minimumOsVersion","13");descriptor.putObject("host").put("os","macos").put("arch","aarch64");
        HostFacts facts=HostFacts.parse(raw,descriptor);assertFalse(facts.bootSessionId().isEmpty());assertTrue(raw.path("bootSessionId").asText().equals(facts.bootSessionId()),"ローカルboot識別の一致");assertTrue(raw.path("hostBindingSha256").asText().equals(facts.hostBindingSha256()),"ローカルbinding識別の一致");assertEquals(raw.path("uid").asLong(),facts.uid());assertEquals(raw.path("physicalMemory").asLong(),facts.memoryBytes());assertEquals(raw.path("osVersion").asText(),facts.osVersion());
    }
}
