package io.github.rs0325.mcicon.helper;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rs0325.mcicon.internal.LeaseResources;
import java.io.IOException;

/** 操作側も参照snapshotと同じ閉じた資源形式を使う。 */
final class ResourceRecords {
    private ResourceRecords() {}
    static void validate(JsonNode resources,String os) throws IOException {LeaseResources.validate(resources,os);}
}
