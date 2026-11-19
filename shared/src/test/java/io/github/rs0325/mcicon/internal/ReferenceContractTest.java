package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

class ReferenceContractTest {
    @Test void 配分Aの総和とhelper境界と照合後予約を固定する() throws Exception {
        assertEquals(9000,ReferenceContract.BEFORE_VERIFY_MILLIS+ReferenceContract.VERIFY_MILLIS+ReferenceContract.AFTER_VERIFY_MILLIS+ReferenceContract.RESPONSE_MILLIS+ReferenceContract.ABORT_MILLIS);
        assertEquals(6850,ReferenceContract.BEFORE_VERIFY_MILLIS+ReferenceContract.VERIFY_MILLIS+ReferenceContract.AFTER_VERIFY_MILLIS);
        assertEquals(3950,ReferenceContract.AFTER_VERIFY_MILLIS);assertEquals(1,ReferenceContract.MAXIMUM_GENERATIONS);
        ReferenceContract.validate(JSON.writeValueAsBytes(ReferenceContract.declaration()));
    }
    @Test void 能力宣言の数値と型と未知fieldと重複と末尾recordを拒否する() throws Exception {
        for(String field:new String[]{"maximumEntries","maximumGenerations","deadlineMillis","helperMillis","afterVerifyMillis","verifyMillis"}) {
            ObjectNode value=ReferenceContract.declaration();value.put(field,value.path(field).asInt()+1);
            assertThrows(IOException.class,()->ReferenceContract.validate(JSON.writeValueAsBytes(value)));
        }
        ObjectNode value=ReferenceContract.declaration();value.put("maximumEntries","16");byte[] wrongType=JSON.writeValueAsBytes(value);assertThrows(IOException.class,()->ReferenceContract.validate(wrongType));
        value=ReferenceContract.declaration();value.put("unknown",1);byte[] unknown=JSON.writeValueAsBytes(value);assertThrows(IOException.class,()->ReferenceContract.validate(unknown));
        String valid=JSON.writeValueAsString(ReferenceContract.declaration());
        for(String invalid:new String[]{valid+"{}",valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),valid.replace("\"schemaVersion\":1","\"schemaVersion\":1.0")})assertThrows(IOException.class,()->ReferenceContract.validate(invalid.getBytes(StandardCharsets.UTF_8)));
    }
}
