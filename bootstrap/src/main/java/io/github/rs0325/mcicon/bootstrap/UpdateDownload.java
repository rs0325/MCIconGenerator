package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 署名候補を有限取得し、全体検証後だけpendingと高水位を同時に保存する。 */
final class UpdateDownload {
    static HttpClient client(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();}
    static int version(String first,String second) {
        String[] a=first.split("\\."),b=second.split("\\.");for(int i=0;i<3;i++){int result=new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));if(result!=0)return result;}return 0;
    }
    static void newer(Seed seed,JsonNode state) throws IOException {
        if(Seed.positive(seed.manifest,"sequence")<=Seed.positive(state,"highestAcceptedSequence")||version(seed.version,Seed.text(state.path("active"),"toolVersion"))<=0)throw Seed.invalid("旧sequence/同版以下の暗黙更新を拒否しました");
    }
    static void prepare(UpdatePreparation prep,String id,JsonNode worker,JsonNode pin,HttpClient client,long deadline) throws IOException {
        Bootstrap b=prep.bootstrap;prep.checkOwner(id,worker,deadline);JsonNode expected=b.state(deadline);Path stage=b.root.resolve("staging/update-"+id);b.directory(stage,deadline);
        String url=Seed.text(b.info,"stableManifestUrl");get(b,client,URI.create(url),stage.resolve("stable.json"),1024*1024,deadline);get(b,client,URI.create(url+".sig"),stage.resolve("stable.json.sig"),64,deadline);
        Seed seed=new Seed(stage,b.key,b.host);newer(seed,expected);long bytes=Seed.positive(seed.payload,"bytes");if(Files.getFileStore(stage).getUsableSpace()<bytes)throw Seed.invalid("更新archiveの空き容量が不足しています");
        get(b,client,URI.create(Seed.text(seed.payload,"url")),stage.resolve("tool.zip"),bytes,deadline);seed.verifyArchive(stage.resolve("tool.zip"),deadline);
        publish(prep,id,worker,pin,seed,expected,deadline);
    }
    static void publish(UpdatePreparation prep,String id,JsonNode worker,JsonNode pin,Seed seed,JsonNode expected,long deadline) throws IOException {
        Bootstrap b=prep.bootstrap;prep.checkOwner(id,worker,deadline);if(!prep.packagePin(deadline).equals(pin))throw Seed.invalid("実行中package入力が変わりました");
        b.copyArchive(seed,deadline);b.saveManifest(seed,deadline);b.verifySlot(seed,deadline);
        try(var ignored=b.lock(deadline)) {
            ObjectNode current=(ObjectNode)b.state(deadline);JsonNode reservation=current.path("preparation");
            if(!id.equals(reservation.path("invocation").asText())||!UpdatePreparation.sameProcess(reservation.get("worker"),worker)||!reservation.path("phase").asText().equals("owned")||!java.time.Instant.now().isBefore(UpdatePreparation.instant(reservation,"expiresAt"))||!prep.enabled(deadline)||!current.path("active").equals(expected.get("active"))||!current.path("pending").isNull()||!current.path("trialInvocation").isNull()||!current.path("highestAcceptedSequence").equals(expected.get("highestAcceptedSequence")))throw Seed.invalid("更新publishの期待状態が変化しました");
            newer(seed,current);current.set("pending",seed.reference());current.put("highestAcceptedSequence",Seed.positive(seed.manifest,"sequence")).putNull("preparation");b.atomic(b.root.resolve("active.json"),current,deadline);
        }
    }
    static void get(Bootstrap files,HttpClient client,URI uri,Path target,long maximum,long deadline) throws IOException {
        for(int redirects=0;redirects<=5;redirects++) {
            Seed.check(deadline);Seed.https(uri.toString());HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(deadline-System.nanoTime())).GET().build();CompletableFuture<HttpResponse<InputStream>> future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());HttpResponse<InputStream> response;
            try {response=future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();abandon(future);throw new InterruptedIOException("更新取得を中断しました");}
            catch(ExecutionException|TimeoutException failure){abandon(future);throw new IOException("更新HTTPの応答を取得できません",failure);}
            int status=response.statusCode();if(status>=300&&status<400){try(InputStream body=response.body()){if(redirects==5)throw Seed.invalid("更新redirectが上限外です");uri=uri.resolve(response.headers().firstValue("location").orElseThrow(()->Seed.invalid("redirect先がありません")));}continue;}
            if(status!=200){response.body().close();throw Seed.invalid("更新HTTP statusが不正です: "+status);}
            AtomicReference<Closeable> output=new AtomicReference<>();Thread monitor=Thread.ofVirtual().start(()->{try{long remaining=deadline-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);response.body().close();Closeable file=output.get();if(file!=null)file.close();}catch(InterruptedException cancelled){}catch(IOException closed){}});
            IOException original=null;
            try(InputStream body=response.body();OutputStream file=java.nio.channels.Channels.newOutputStream(files.create(target,deadline))) {
                output.set(file);Seed.check(deadline);byte[] buffer=new byte[65536];long total=0;int count;while((count=body.read(buffer))!=-1){Seed.check(deadline);if(count>maximum-total)throw Seed.invalid("更新取得sizeが上限外です");file.write(buffer,0,count);total+=count;}Seed.check(deadline);
            }catch(IOException failure){original=failure;throw failure;}
            finally {monitor.interrupt();IOException cleanup=null;try{monitor.join(1000);if(monitor.isAlive())cleanup=Seed.invalid("更新stream監督を回収できません");}catch(InterruptedException failure){Thread.currentThread().interrupt();cleanup=new InterruptedIOException("更新stream監督回収を中断しました");}if(cleanup!=null){if(original!=null)original.addSuppressed(cleanup);else throw cleanup;}}
            return;
        }
    }
    private static void abandon(CompletableFuture<HttpResponse<InputStream>> future){future.whenComplete((response,failure)->{if(response!=null)try{response.body().close();}catch(IOException closed){}});future.cancel(true);}
}
