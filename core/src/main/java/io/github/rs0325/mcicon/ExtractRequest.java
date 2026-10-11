package io.github.rs0325.mcicon;

import java.nio.file.Path;
import java.util.List;

public record ExtractRequest(String version, List<ResourceId> textures, boolean all, Path output,
                             boolean overwrite, boolean failFast) {
    public ExtractRequest {
        textures = List.copyOf(textures);
        if (version == null || output == null || all == !textures.isEmpty())
            throw new IllegalArgumentException("Specify either textures or all, a version, and an output directory");
    }
}
