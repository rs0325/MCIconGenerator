package io.github.rs0325.mcicon;

import java.util.List;

public record CacheReport(long bytes, int files, int verified, List<String> failures) {
    public CacheReport { failures = List.copyOf(failures); }
}
