package io.github.rs0325.mcicon;

import java.util.List;

public record DiagnosticReport(String version, boolean healthy, List<Check> checks) {
    public DiagnosticReport { checks = List.copyOf(checks); }
    public record Check(String name, boolean passed, String detail) {}
}
