package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 元jobの終端と同incarnationの保持/停止証明を結ぶ補助形式。単独では停止・解放を許可しない。 */
public final class RetentionEvidence {
    public static final int MAX_BYTES=65536;
    private static final String[] FIELDS={"schemaVersion","kind","reason","hostId","rootKey","bootSha256","epoch","jobId","attempt","ownerId","descriptorSha256","generation","generationOwner","registrationKey","registrationSha256","incarnationSha256","leaseSha256","receiptKey","receiptSha256","terminalJournalSha256","keeperProofSha256","holder","stopped"};
    private RetentionEvidence() {}

    public static byte[] encode(ObjectNode value) throws IOException {
        validate(value);ObjectNode payload=JSON.createObjectNode();
        for(String field:FIELDS)payload.set(field,value.required(field));
        payload.set("generation",ReferenceGeneration.canonical(value.path("generation")));
        if(!value.path("holder").isNull())payload.set("holder",ordered(value.path("holder"),"jobId","attempt","ownerId","receiptKey","receiptSha256","leaseSha256","journalSha256"));
        if(!value.path("stopped").isNull())payload.set("stopped",ordered(value.path("stopped"),"proofKey","proofSha256"));
        ObjectNode envelope=JSON.createObjectNode();envelope.set("payload",payload);envelope.put("payloadSha256",digest(JSON.writeValueAsBytes(payload)));
        byte[] result=JSON.writeValueAsBytes(envelope);if(result.length>MAX_BYTES)throw invalid("保持補助証拠がsize上限を超えています");return result;
    }
    public static ObjectNode decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES)throw invalid("保持補助証拠がsize上限を超えています");
        JsonNode envelope=read(bytes);fields(envelope,"payload","payloadSha256");
        JsonNode payload=envelope.path("payload");validate(payload);
        JsonNode canonical=read(encode((ObjectNode)payload));
        if(!hash(envelope,"payloadSha256").equals(canonical.path("payloadSha256").asText()))throw invalid("保持補助証拠のhashが不一致です");
        return (ObjectNode)payload.deepCopy();
    }
    /** 元集合のjob/epoch/世代との結合を検証する。holder資格と停止の実証は呼出し元の責務。 */
    public static void verifyBinding(byte[] bytes,JsonNode state,JsonNode entry) throws IOException {
        ObjectNode value=decode(bytes);
        for(String name:List.of("hostId","rootKey","bootSha256","epoch"))if(!value.path(name).equals(state.path(name)))throw invalid("保持補助証拠のroot/epochが不一致です");
        for(String name:List.of("jobId","attempt","ownerId","descriptorSha256","receiptKey","receiptSha256","generation"))if(!value.path(name).equals(entry.path(name)))throw invalid("保持補助証拠の元jobが不一致です");
        if(!entry.path("phase").asText().equals("admitted"))throw invalid("未公開予約に終端保持証拠は使えません");
    }
    private static ObjectNode ordered(JsonNode value,String... names) {ObjectNode result=JSON.createObjectNode();for(String name:names)result.set(name,value.required(name));return result;}
    private static void validate(JsonNode value) throws IOException {
        fields(value,FIELDS);
        if(integer(value,"schemaVersion",1)!=1||!text(value,"kind",32).equals("runtime-retention"))throw invalid("保持補助証拠のversionが不正です");
        for(String name:List.of("hostId","epoch","jobId","attempt","ownerId","generationOwner"))uuid(value,name);
        for(String name:List.of("bootSha256","descriptorSha256","registrationSha256","incarnationSha256","leaseSha256","receiptSha256","terminalJournalSha256","keeperProofSha256"))hash(value,name);
        for(String name:List.of("rootKey","registrationKey","receiptKey"))text(value,name,512);
        ReferenceGeneration.canonical(value.path("generation"));
        if(!value.path("generation").path("descriptorSha256").equals(value.path("descriptorSha256")))throw invalid("保持補助証拠の世代が不一致です");
        switch(text(value,"reason",32)) {
            case "same-incarnation-holder" -> {
                if(!value.path("stopped").isNull())throw invalid("保持証拠に停止証拠が混在しています");
                JsonNode holder=value.path("holder");fields(holder,"jobId","attempt","ownerId","receiptKey","receiptSha256","leaseSha256","journalSha256");
                for(String name:List.of("jobId","attempt","ownerId"))uuid(holder,name);
                for(String name:List.of("receiptSha256","leaseSha256","journalSha256"))hash(holder,name);text(holder,"receiptKey",512);
                if(holder.path("jobId").equals(value.path("jobId"))||holder.path("attempt").equals(value.path("attempt")))throw invalid("自jobを保持先にはできません");
            }
            case "stopped" -> {
                if(!value.path("holder").isNull())throw invalid("停止証拠に保持先が混在しています");
                fields(value.path("stopped"),"proofKey","proofSha256");text(value.path("stopped"),"proofKey",512);hash(value.path("stopped"),"proofSha256");
            }
            default -> throw invalid("保持補助証拠のreasonが不正です");
        }
    }
}
