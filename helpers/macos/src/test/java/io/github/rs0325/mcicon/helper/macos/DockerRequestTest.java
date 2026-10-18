package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DockerRequestTest {
    private ObjectNode descriptor() {
        ObjectNode descriptor=MacFiles.JSON.createObjectNode();var render=descriptor.putObject("rendering");render.put("imageId","sha256:"+"a".repeat(64));render.putArray("javaExecutables").addObject().put("executable","/opt/java/java21/bin/java");render.putObject("environment").put("LIBGL_ALWAYS_SOFTWARE","1");return descriptor;
    }
    private ObjectNode lease() {ObjectNode lease=MacFiles.JSON.createObjectNode().put("jobId",UUID.randomUUID().toString()).put("ownerId",UUID.randomUUID().toString()).put("descriptorSha256","b".repeat(64));lease.putObject("requirements").put("containerMemoryMiB",1024);return lease;}
    private List<String> worker() {return List.of("run","--rm","--name","mcicon-worker-"+UUID.randomUUID(),"-i","--platform","linux/amd64","--network","none","--memory","1024m","--cpus","4","-v","/private/cache:/mcicon/cache","-v","/private/cache/worker-one/worker-agent.jar:/mcicon/worker.jar:ro","sha256:"+"a".repeat(64),"/opt/java/java21/bin/java","-version");}
    @Test void 正規bindだけをguestのjob限定snapshotへ変換する() throws Exception {
        DockerRequest request=DockerRequest.parse(worker(),descriptor(),lease(),"/mcicon/jobs/job/cache","owner");assertTrue(request.worker());assertEquals("create",request.create().getFirst());assertFalse(request.create().contains("--rm"));assertTrue(request.create().contains("/mcicon/jobs/job/cache:/mcicon/cache"));assertTrue(request.create().contains("/mcicon/jobs/job/cache/worker-one/worker-agent.jar:/mcicon/worker.jar:ro"));assertTrue(request.create().stream().anyMatch(value->value.startsWith("io.github.rs0325.mcicon.jobId=")));
    }
    @Test void 任意hostbindとimageと予算変更を拒否する() {
        for(String replacement:List.of("/Users:/host","/private/cache:/mcicon/cache:ro")) {List<String> args=new ArrayList<>(worker());args.set(args.indexOf("/private/cache:/mcicon/cache"),replacement);assertThrows(IOException.class,()->DockerRequest.parse(args,descriptor(),lease(),"/mcicon/jobs/job/cache","owner"));}
        List<String> args=new ArrayList<>(worker());args.set(args.indexOf("1024m"),"2m");assertThrows(IOException.class,()->DockerRequest.parse(args,descriptor(),lease(),"/mcicon/jobs/job/cache","owner"));
    }
    @Test void 既存diagnosticsだけを許し予算を付加する() throws Exception {
        List<String> args=List.of("run","--rm","--name","mcicon-doctor-"+UUID.randomUUID(),"--platform","linux/amd64","--network","none","--entrypoint","/opt/java/java21/bin/java","sha256:"+"a".repeat(64),"-version");
        DockerRequest request=DockerRequest.parse(args,descriptor(),lease(),"/mcicon/jobs/job/cache","owner");assertFalse(request.worker());assertTrue(request.create().contains("1024m"));
        List<String> changed=new ArrayList<>(args);changed.set(changed.indexOf("/opt/java/java21/bin/java"),"/bin/sh");assertThrows(IOException.class,()->DockerRequest.parse(changed,descriptor(),lease(),"/mcicon/jobs/job/cache","owner"));
    }
}
