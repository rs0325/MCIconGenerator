package io.github.rs0325.mcicon;

import java.util.List;
import java.util.Map;

public record ExportResult(String version, String mode, String manifest, boolean complete,
                           int succeeded, int failed, List<Entry> entries) {
    public ExportResult { entries=List.copyOf(entries); }
    public int exitCode() { return complete ? 0 : 3; }
    public record Entry(String id, String kind, String status, String path, String sha256,
                        String sourceSha256, Map<String,String> auxiliary, String code, String message) {
        public Entry { auxiliary=Map.copyOf(auxiliary); }
    }
}
