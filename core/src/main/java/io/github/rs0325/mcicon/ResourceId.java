package io.github.rs0325.mcicon;

import java.util.regex.Pattern;

/** A validated Minecraft resource identifier, independent of game classes. */
public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    public ResourceId {
        if (namespace == null || path == null || namespace.equals(".") || namespace.equals("..") || !NAMESPACE.matcher(namespace).matches()
                || !PATH.matcher(path).matches() || path.startsWith("/") || path.endsWith("/"))
            throw new IllegalArgumentException("Invalid resource identifier");
        for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("Invalid resource path segment");
    }
    public static ResourceId parse(String value) {
        if (value == null) throw new IllegalArgumentException("Missing resource identifier");
        int colon = value.indexOf(':');
        return colon < 0 ? new ResourceId("minecraft", value)
                : new ResourceId(value.substring(0, colon), value.substring(colon + 1));
    }
    @Override public String toString() { return namespace + ":" + path; }
    @Override public int compareTo(ResourceId other) { return toString().compareTo(other.toString()); }
}
