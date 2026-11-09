package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** package Java上で実validator/所有/publishを通し、通信だけを有限のbyte列へ置き換える。 */
public final class DownloadClosureProcess {
    public static void main(String[] args) throws Exception {
        Bootstrap b=new Bootstrap(Path.of(args[0]),Path.of(args[1]),Main.host());Path input=Path.of(args[2]);String mode=args[3];boolean fixture=mode.equals("fixture"),success=fixture||mode.equals("success");long deadline=System.nanoTime()+(fixture?UpdatePreparation.BUDGET:Duration.ofSeconds(20)).toNanos();if(!fixture)b.launch(List.of());JsonNode before=b.state();UpdatePreparation prep=new UpdatePreparation(b);JsonNode pin=prep.packagePin(deadline);String id=prep.reserve(b.state(deadline),pin,Instant.now(),deadline);if(id==null)throw new AssertionError("予約できません");
        try(FileChannel channel=FileChannel.open(prep.path(id),StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock held=channel.lock()) {
            ObjectNode record=(ObjectNode)UpdatePreparation.readReceipt(channel);JsonNode self=Bootstrap.identity(ProcessHandle.current());record.set("child",self);record.put("state","running");Bootstrap.write(channel,record);ObjectNode state=(ObjectNode)b.state(deadline),reservation=(ObjectNode)state.get("preparation");reservation.put("phase","owned");reservation.set("worker",self);b.atomic(b.root.resolve("active.json"),state,deadline);
            UpdateDownloadTest.Client client=new UpdateDownloadTest.Client(request->{
                try {
                    String name=request.uri().getPath().endsWith(".sig")?"stable.json.sig":request.uri().getPath().endsWith(".json")?"stable.json":"tool.zip";byte[] body=Files.readAllBytes(input.resolve(name));
                    if(mode.equals("signature")&&name.endsWith(".sig"))body[0]^=1;if(mode.equals("disable")&&name.equals("tool.zip"))prep.configure(false,deadline);
                    return CompletableFuture.completedFuture(new UpdateDownloadTest.Reply(request,200,new ByteArrayInputStream(body),Map.of()));
                }catch(IOException failure){return CompletableFuture.failedFuture(failure);}
            });
            boolean accepted=false;try{if(fixture)UpdateDownload.publish(prep,id,self,pin,new Seed(input,b.key,b.host),b.state(deadline),deadline);else UpdateDownload.prepare(prep,id,self,pin,client,deadline);accepted=true;}catch(IOException expected){if(success)throw expected;}
            if(accepted!=success)throw new AssertionError("候補の採否が不正です");JsonNode after=b.state(deadline);if(accepted){
                Seed expected=fixture?new Seed(input,b.key,b.host):null;
                long sequence=fixture?expected.manifest.path("sequence").asLong():2;
                String active=fixture?before.path("active").path("toolVersion").asText():"1.1.0";
                if(after.path("pending").isNull()||after.path("highestAcceptedSequence").asLong()!=sequence||!after.path("active").path("toolVersion").asText().equals(active))throw new AssertionError("pending成功状態が不正です");
                if(fixture&&(!after.path("pending").equals(expected.reference())||!after.path("active").equals(before.path("active"))))throw new AssertionError("署名候補または元activeが一致しません");
            }else if(!after.path("pending").isNull()||after.path("highestAcceptedSequence").asInt()!=1)throw new AssertionError("未適用の高水位が変化しました");
            record.put("state","finished").put("exitCode",accepted?0:1).put("result",accepted?"pending":"未適用");Bootstrap.write(channel,record);Path evidence=Path.of(args[4]);Files.createDirectory(evidence);Files.write(evidence.resolve("state.json"),Seed.JSON.writeValueAsBytes(after));Files.write(evidence.resolve("receipt.json"),Seed.JSON.writeValueAsBytes(record));Files.write(evidence.resolve("result.json"),Seed.JSON.writeValueAsBytes(Seed.JSON.createObjectNode().put("mode",mode).put("pendingAccepted",accepted).put("fakeHttpRequests",client.calls.get()).put("externalConnections",0).put("productionDistributionAccepted",false)));
        }
    }
}
