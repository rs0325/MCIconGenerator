package io.github.rs0325.mcicon.internal;

import io.github.rs0325.mcicon.CancellationToken;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** ファイルlockを保持した取得と、別入力の取得待ちを独立スレッドで制御する。 */
public final class AssetLockProcess {
    public static void main(String[] args) throws Exception {
        var release=new CountDownLatch(1);
        var store=new AssetStore(Path.of(args[0]),false,new CancellationToken(),uri->{
            System.out.println("HELD");release.await();return new ByteArrayInputStream(new byte[]{1});
        });
        Thread holder=new Thread(()->obtain(store,args[1]));holder.start();
        Thread waiter=null;
        try(var input=new BufferedReader(new InputStreamReader(System.in))) {
            String command;
            while((command=input.readLine())!=null) {
                if(command.equals("wait")) {
                    var other=new AssetStore(Path.of(args[0]),false,new CancellationToken(),uri->new ByteArrayInputStream(new byte[]{1}));
                    waiter=new Thread(()->obtain(other,args[2]));waiter.start();
                    waitForLock(waiter);
                } else if(command.equals("release"))break;
            }
        } finally {release.countDown();}
        holder.join();if(waiter!=null)waiter.join();
    }
    private static void waitForLock(Thread waiter) throws InterruptedException {
        while(waiter.isAlive()) {
            for(var frame:waiter.getStackTrace()) {
                if((frame.getClassName().startsWith("sun.nio.ch.")&&frame.getMethodName().equals("lock0"))
                        ||(frame.getClassName().equals("java.lang.Thread")&&frame.getMethodName().startsWith("sleep"))) {
                    System.out.println("WAITING");return;
                }
            }
            Thread.sleep(1);
        }
    }
    private static void obtain(AssetStore store,String path) {
        try {store.obtain(path,URI.create("https://example.test/"+path),null,1,false);System.out.println("DONE "+path);}
        catch(Exception e) {System.out.println("ERROR "+e);e.printStackTrace(System.err);}
    }
}
