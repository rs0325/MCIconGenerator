package io.github.rs0325.mcicon.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test void hashedMetadataUsesUtf8AndLfOnEveryHost() throws Exception {
        var value=Map.of("name","日本語\r\n", "objects",List.of(Map.of("id",1)));
        String expected="{\n  \"name\" : \"日本語\\r\\n\",\n  \"objects\" : [ {\n    \"id\" : 1\n  } ]\n}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),Json.MAPPER.writeValueAsBytes(value));
        assertEquals(Json.MAPPER.valueToTree(value),Json.MAPPER.readTree(expected));
    }
}
