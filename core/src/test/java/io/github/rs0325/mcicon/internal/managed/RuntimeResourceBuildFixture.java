package io.github.rs0325.mcicon.internal.managed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rs0325.mcicon.internal.FilesSupport;
import java.nio.file.*;

/** Gradle A→B→無指定の結合QAへ、公開署名入力だけを渡す。 */
final class RuntimeResourceBuildFixture {
    public static void main(String[] args) throws Exception {
        var fixture=new RuntimeResourceGeneratorTest();fixture.root=Path.of(args[0]);Files.createDirectory(fixture.root);String adapters;
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{Path.of(args[2]).toUri().toURL()},null)){adapters=ManagedRuntime.adapterDigest(loader);}
        for(String name:java.util.List.of("a","b")) {
            var input=fixture.new Input();input.fixture.json.put("runtimeId","fixture-"+name);((ObjectNode)input.fixture.json.get("worker")).put("agentSha256",FilesSupport.hash(Path.of(args[1]),"SHA-256")).put("adapterSetSha256",adapters);
            String inventory="inventory/helper-"+name+".json";Files.move(input.dir.resolve("inventory/helper.json"),input.dir.resolve(inventory));((ObjectNode)input.fixture.json.path("artifacts").get(0)).put("inventoryPath",inventory);input.publish();
            Files.writeString(fixture.root.resolve(name+"-input.txt"),input.dir.toString());
        }
    }
}
