package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** 肯定証拠を検証した未公開/回収済みの延期だけをCLIへ伝える。 */
public final class ManagedBusyFailure extends IOException {
    private final JsonNode admission;
    private final JsonNode jobs;
    ManagedBusyFailure(JsonNode admission){super("既知の稼働jobのため候補開始を延期します");this.admission=admission.deepCopy();this.jobs=RuntimeDescriptor.JSON.createArrayNode();}
    ManagedBusyFailure(com.fasterxml.jackson.databind.node.ArrayNode jobs){super("既知live jobと容量不足のため候補を延期します");this.admission=RuntimeDescriptor.JSON.nullNode();this.jobs=jobs.deepCopy();}
    public JsonNode jobs(){return jobs.deepCopy();}
    public JsonNode admission(){return admission.deepCopy();}
}
