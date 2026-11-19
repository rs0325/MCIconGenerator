package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import static io.github.rs0325.mcicon.internal.LeaseSnapshot.*;

/** 永続参照v1の形式・数値契約。宣言の認証だけでは運用入口の参加を有効にしない。 */
public final class ReferenceContract {
    public static final int MAXIMUM_ENTRIES=16,MAXIMUM_GENERATIONS=1;
    public static final int DEADLINE_MILLIS=9000,HELPER_MILLIS=6850;
    public static final int BEFORE_VERIFY_MILLIS=1300,VERIFY_MILLIS=1600,NATIVE_REAP_MILLIS=2000;
    public static final int AFTER_STOP_MILLIS=1700,COMMIT_MILLIS=250,RESPONSE_MILLIS=150,ABORT_MILLIS=2000;
    public static final int AFTER_VERIFY_MILLIS=NATIVE_REAP_MILLIS+AFTER_STOP_MILLIS+COMMIT_MILLIS;
    private ReferenceContract() {}

    public static ObjectNode declaration() {
        return JSON.createObjectNode().put("schemaVersion",1).put("kind","active-references").put("version",1)
                .put("maximumEntries",MAXIMUM_ENTRIES).put("maximumGenerations",MAXIMUM_GENERATIONS)
                .put("deadlineMillis",DEADLINE_MILLIS).put("helperMillis",HELPER_MILLIS)
                .put("beforeVerifyMillis",BEFORE_VERIFY_MILLIS).put("verifyMillis",VERIFY_MILLIS)
                .put("nativeReapMillis",NATIVE_REAP_MILLIS).put("afterStopMillis",AFTER_STOP_MILLIS)
                .put("commitMillis",COMMIT_MILLIS).put("responseMillis",RESPONSE_MILLIS)
                .put("abortMillis",ABORT_MILLIS).put("afterVerifyMillis",AFTER_VERIFY_MILLIS);
    }
    /** 未知field・欠落・型・数値差を拒否する。署名検証は別途必要。 */
    public static void validate(byte[] bytes) throws IOException {
        if(bytes.length>4096)throw invalid("参照能力宣言がsize上限を超えています");
        JsonNode actual=read(bytes);
        if(!declaration().equals(actual))throw invalid("参照能力宣言が共通契約と一致しません");
    }
    /** bindReaderで署名descriptorと元inventoryへ束縛済みのpackageから認証する。 */
    public static void authenticate(LeaseTrust trust,String relative) throws IOException {
        validate(trust.boundHelperBytes(relative,4096));
    }
}
