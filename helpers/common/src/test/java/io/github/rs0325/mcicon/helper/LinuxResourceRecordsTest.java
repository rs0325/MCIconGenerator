package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LinuxResourceRecordsTest {
    @TempDir Path root;
    static JsonNode examples() throws Exception {return HelperProtocol.read(Files.readAllBytes(Path.of("..","..","schemas","managed-helper-linux-v1.examples.json"))).get("resourceExamples");}
    static ObjectNode ready() throws Exception {for(var item:examples())if(item.path("name").asText().equals("linux-ready-owned-shape"))return ((ObjectNode)item.get("resources")).deepCopy();throw new AssertionError();}
    @Test void linuxAndSchemaShareIntentReadyAndRejectedIdentityFixtures() throws Exception {
        for(var item:examples()) {
            if(item.path("helperValid").booleanValue())assertDoesNotThrow(()->ResourceRecords.validate(item.get("resources"),"linux"),item.path("name").asText());
            else assertThrows(IOException.class,()->ResourceRecords.validate(item.get("resources"),"linux"),item.path("name").asText());
        }
    }
    @Test void readyRequiresEveryLiveIdentityAndEveryNestedField() throws Exception {
        var original=ready();var linux=original.path("linux");
        for(String field:List.of("hostBootId","uid","userUnit","rootlesskit","dockerd","userNamespace","dockerSocket")) {
            var changed=original.deepCopy();((ObjectNode)changed.get("linux")).remove(field);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"),field);
        }
        for(String record:List.of("userUnit","rootlesskit","dockerd","userNamespace","dockerSocket")) {
            for(Iterator<String> fields=linux.path(record).fieldNames();fields.hasNext();) {
                String field=fields.next();
                var changed=original.deepCopy();((ObjectNode)changed.path("linux").get(record)).remove(field);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"),record+"/"+field);
            }
            var unknown=original.deepCopy();((ObjectNode)unknown.path("linux").get(record)).put("unknown",1);assertThrows(IOException.class,()->ResourceRecords.validate(unknown,"linux"),record);
        }
        var unknown=original.deepCopy();((ObjectNode)unknown.get("linux")).put("unknown",1);assertThrows(IOException.class,()->ResourceRecords.validate(unknown,"linux"));
    }
    @Test void rootUidUnsafePathsForeignCgroupAndWrongOsAreRejected() throws Exception {
        for(long uid:List.of(0L,-1L,4294967295L)) {var changed=ready();((ObjectNode)changed.get("linux")).put("uid",uid);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"));}
        for(String path:List.of("relative","/fixture/../runtime","/fixture/./runtime","/fixture//runtime","/","/fixture/","/fixture\0runtime")) {
            var changed=ready();((ObjectNode)changed.get("linux")).put("runtimeDir",path);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"),path);
        }
        var changed=ready();((ObjectNode)changed.path("linux").get("userUnit")).put("controlGroup","/system.slice/foreign.service");assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"));
        for(String os:List.of("macos","windows","unknown"))assertThrows(IOException.class,()->ResourceRecords.validate(ready(),os));
    }
    @Test void processNumbersRemainExactAndAliasOrIncompleteIdentityIsRejected() throws Exception {
        var valid=ready();ResourceRecords.validate(valid,"linux");assertEquals(9007199254740994L,valid.path("linux").path("rootlesskit").path("startTicks").longValue());
        for(String field:List.of("pid","startTicks"))for(JsonNode number:List.of(HelperProtocol.JSON.getNodeFactory().numberNode(-1),HelperProtocol.JSON.getNodeFactory().numberNode(1.5),HelperProtocol.JSON.getNodeFactory().textNode("123"))) {
            var changed=ready();((ObjectNode)changed.path("linux").get("rootlesskit")).set(field,number);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"));
        }
        var zero=ready();((ObjectNode)zero.path("linux").get("rootlesskit")).put("pid",0);assertThrows(IOException.class,()->ResourceRecords.validate(zero,"linux"));
        var alias=ready();((ObjectNode)alias.path("linux").get("rootlesskit")).put("pid",123);((ObjectNode)alias.path("linux").get("dockerd")).put("pid",123L);assertThrows(IOException.class,()->ResourceRecords.validate(alias,"linux"));
    }
    @Test void containerIntentAndActualIdentityCannotBeMixedOrDuplicated() throws Exception {
        for(String field:List.of("id","startedAt")) {var changed=ready();((ObjectNode)changed.path("linux").path("containers").get(1)).remove(field);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"));}
        for(String field:List.of("name","id")) {
            var changed=ready();var containers=(ArrayNode)changed.path("linux").get("containers");var duplicate=((ObjectNode)containers.get(1)).deepCopy();if(field.equals("id"))duplicate.put("name","other");containers.add(duplicate);assertThrows(IOException.class,()->ResourceRecords.validate(changed,"linux"),field);
        }
        var unknown=ready();((ObjectNode)unknown.path("linux").path("containers").get(0)).put("transportId",UUID.randomUUID().toString());assertThrows(IOException.class,()->ResourceRecords.validate(unknown,"linux"));
    }
    @Test void linuxResourcesUseExistingReceiptCasAndPreserveInvalidEvidence() throws Exception {
        var owner=new HelperCommonTest();owner.root=root;var fixture=owner.new Fixture();
        Path descriptorFile=fixture.file.resolveSibling("descriptor.json");var descriptor=(ObjectNode)HelperProtocol.read(Files.readAllBytes(descriptorFile));((ObjectNode)descriptor.get("host")).put("os","linux").put("arch","x86_64");descriptor.put("minimumOsVersion","6.8");byte[] definition=HelperProtocol.JSON.writeValueAsBytes(descriptor);Files.write(descriptorFile,definition);
        var identity=(ObjectNode)HelperProtocol.read(Files.readAllBytes(fixture.file));identity.remove(List.of("state","resources"));identity.put("descriptorSha256",HelperCommonTest.hash(definition));var initial=identity.deepCopy().put("state","active");initial.putObject("resources");Files.write(fixture.file,HelperProtocol.JSON.writeValueAsBytes(initial));Files.write(fixture.file.resolveSibling("lease.lock"),LeaseFile.receipt(identity));
        Path executable=fixture.base.resolve("helpers/linux-x86_64/"+"1".repeat(64)+"/bin/helper");Files.createDirectories(executable.getParent());Files.writeString(executable,"fixture-only");var lease=LeaseFile.open(fixture.command,executable);var empty=HelperProtocol.JSON.createObjectNode();var ready=ready();lease.replaceResources(empty,ready);
        var saved=lease.read();assertEquals(ready,saved.get("resources"));saved.set("resources",empty);assertEquals(initial,saved);assertThrows(IOException.class,()->lease.replaceResources(empty,ready));
        var partial=ready.deepCopy();var linux=(ObjectNode)partial.get("linux");linux.put("phase","starting");linux.remove(List.of("rootlesskit","dockerd","userNamespace","dockerSocket"));((ObjectNode)linux.get("userUnit")).remove("controlGroup");lease.replaceResources(ready,partial);assertEquals(partial,lease.read().get("resources"));
        var invalid=partial.deepCopy();((ObjectNode)invalid.get("linux")).put("phase","ready");byte[] before=Files.readAllBytes(fixture.file);assertThrows(IOException.class,()->lease.replaceResources(partial,invalid));assertArrayEquals(before,Files.readAllBytes(fixture.file));
        var corrupt=lease.read();corrupt.set("resources",invalid);byte[] evidence=HelperProtocol.JSON.writeValueAsBytes(corrupt);Files.write(fixture.file,evidence);assertThrows(IOException.class,lease::read);assertArrayEquals(evidence,Files.readAllBytes(fixture.file));
    }
}
