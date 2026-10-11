package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/** Joins pinned official → intermediary → Feather Tiny v2 mappings. */
public final class LegacyMappings {
    private record Member(String kind, String descriptor, String name) {}
    private record ClassNames(String name, Map<Member, String> members) {}
    private static final Pattern TYPE = Pattern.compile("\\[*L[^;]+;|\\[*[VZBSCIJFD]");
    private static final Map<Character, String> PRIMITIVES = Map.of(
            'V', "void", 'Z', "boolean", 'B', "byte", 'S', "short", 'C', "char",
            'I', "int", 'J', "long", 'F', "float", 'D', "double");

    public static Path prepare(AssetStore store, String version) throws IOException {
        JsonNode pins;
        try (InputStream input = LegacyMappings.class.getResourceAsStream("/adapters/legacy-mappings.json")) {
            if (input == null) throw new IOException("Missing legacy mappings lock");
            pins = Json.MAPPER.readTree(input).path(version);
        }
        if (pins.isMissingNode()) throw new IOException("No pinned legacy mappings for " + version);
        String repository = "https://maven.ornithemc.net/releases/net/ornithemc/";
        int build=pins.path("featherBuild").asInt(31);
        String featherName = "feather-" + version + "+build."+build+"-v2.jar";
        String intermediaryName = "calamus-intermediary-" + version + "-v2.jar";
        Path feather = store.obtain("mappings/" + version + "/feather.jar",
                URI.create(repository + "feather/" + version + "+build."+build+"/" + featherName),
                pins.path("feather").asText(), -1, false);
        Path intermediary = store.obtain("mappings/" + version + "/intermediary.jar",
                URI.create(repository + "calamus-intermediary/" + version + "/" + intermediaryName),
                pins.path("intermediary").asText(), -1, false);
        Path output = FilesSupport.safeResolve(store.root(), "versions/" + version + "/mappings.txt");
        FilesSupport.atomicWrite(output, merge(intermediary, feather).getBytes(StandardCharsets.UTF_8));
        return output;
    }

    static String merge(Path intermediary, Path feather) throws IOException {
        Map<String, ClassNames> official = read(intermediary, "official", "intermediary");
        Map<String, ClassNames> named = read(feather, "intermediary", "named");
        Map<String, String> classes = new HashMap<>();
        for (var entry : official.entrySet()) {
            String middle = entry.getValue().name();
            classes.put(entry.getKey(), named.containsKey(middle) ? named.get(middle).name() : middle);
        }
        StringBuilder output = new StringBuilder();
        for (var entry : official.entrySet()) {
            output.append(classes.get(entry.getKey()).replace('/', '.')).append(" -> ")
                    .append(entry.getKey().replace('/', '.')).append(":\n");
            ClassNames names = named.get(entry.getValue().name());
            for (var member : entry.getValue().members().entrySet()) {
                Member source = member.getKey();
                Matcher matcher = Pattern.compile("L([^;]+);").matcher(source.descriptor());
                String descriptor = matcher.replaceAll(result -> Matcher.quoteReplacement("L" +
                        (official.containsKey(result.group(1)) ? official.get(result.group(1)).name() : result.group(1)) + ";"));
                String name = names == null ? member.getValue() : names.members().getOrDefault(
                        new Member(source.kind(), descriptor, member.getValue()), member.getValue());
                if (source.kind().equals("f")) {
                    output.append("    ").append(types(source.descriptor(), classes).getFirst()).append(' ').append(name);
                } else {
                    int end = source.descriptor().indexOf(')');
                    if (end < 0) throw new IOException("Invalid method descriptor");
                    output.append("    ").append(types(source.descriptor().substring(end + 1), classes).getFirst())
                            .append(' ').append(name).append('(')
                            .append(String.join(",", types(source.descriptor().substring(1, end), classes))).append(')');
                }
                output.append(" -> ").append(source.name()).append('\n');
            }
        }
        return output.toString();
    }

    private static List<String> types(String descriptor, Map<String, String> classes) throws IOException {
        List<String> types = new ArrayList<>();
        Matcher matcher = TYPE.matcher(descriptor);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IOException("Invalid descriptor");
            String value = matcher.group();
            int dimensions = 0;
            while (value.charAt(dimensions) == '[') dimensions++;
            String raw = value.substring(dimensions);
            String name = raw.startsWith("L") ? classes.getOrDefault(raw.substring(1, raw.length() - 1),
                    raw.substring(1, raw.length() - 1)).replace('/', '.') : PRIMITIVES.get(raw.charAt(0));
            types.add(name + "[]".repeat(dimensions));
            end = matcher.end();
        }
        if (end != descriptor.length()) throw new IOException("Invalid descriptor");
        return types;
    }

    private static Map<String, ClassNames> read(Path jar, String from, String to) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("mappings/mappings.tiny");
            if (entry == null || entry.getSize() < 0 || entry.getSize() > 32 * 1024 * 1024)
                throw new IOException("Missing or oversized Tiny mappings");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                if (!Objects.equals(input.readLine(), "tiny\t2\t0\t" + from + "\t" + to))
                    throw new IOException("Unexpected Tiny namespaces");
                Map<String, ClassNames> classes = new LinkedHashMap<>();
                ClassNames owner = null;
                String line;
                while ((line = input.readLine()) != null) {
                    String[] fields = line.split("\t", -1);
                    if (fields[0].equals("c") && fields.length == 3) {
                        owner = new ClassNames(fields[2], new LinkedHashMap<>());
                        if (classes.put(fields[1], owner) != null) throw new IOException("Duplicate mapping class");
                    } else if (fields.length >= 5 && fields[0].isEmpty() && Set.of("m", "f").contains(fields[1])) {
                        if (owner == null) throw new IOException("Mapping member without owner");
                        owner.members().put(new Member(fields[1], fields[2], fields[3]), fields[4]);
                    }
                }
                return classes;
            }
        }
    }
    private LegacyMappings() {}
}
