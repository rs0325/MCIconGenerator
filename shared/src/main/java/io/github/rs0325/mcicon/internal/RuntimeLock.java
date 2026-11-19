package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** 同JVMの待機者が別FDを閉じて保持中のPOSIX lockを失わせない。 */
public final class RuntimeLock implements AutoCloseable {
    private static final class Gate {
        final ReentrantLock lock=new ReentrantLock(true);
        int users;
    }
    private static final Map<Path,Gate> gates=new HashMap<>();
    private static final String REGISTRY_PROPERTY="io.github.rs0325.mcicon.runtimeLockRegistry";
    private static final String REGISTRY_OWNER=UUID.randomUUID().toString();
    private final Path file;
    private final Gate gate;
    private final FileChannel channel;
    private final FileLock lock;
    private final String key;
    private final LeaseTrust.IdentityReader reader;
    private final Thread owner;
    private boolean closed;

    private RuntimeLock(Path file,Gate gate,FileChannel channel,FileLock lock,String key,LeaseTrust.IdentityReader reader) {
        this.file=file;this.gate=gate;this.channel=channel;this.lock=lock;this.key=key;this.reader=reader;owner=Thread.currentThread();
    }
    /** 固定絶対期限で排他する。Windows等のfileKeyがない環境では認証済みreaderが必須。 */
    public static RuntimeLock acquire(Path file,long end,LeaseTrust.IdentityReader reader) throws IOException {
        return acquire(file,end,reader,()->false);
    }
    /** 取得前gateの待機も元取消しと期限に従う。取消し時に別FDを作らない。 */
    public static RuntimeLock acquire(Path file,long end,LeaseTrust.IdentityReader reader,BooleanSupplier cancelled) throws IOException {
        requireRegistry();
        Path path=file.toAbsolutePath().normalize();
        if(!path.getParent().toRealPath().equals(path.getParent()))throw new IOException("lock親directoryにlinkがあります");
        Gate gate;
        synchronized(gates){gate=gates.computeIfAbsent(path,key->new Gate());gate.users++;}
        boolean entered=false,retained=false;FileChannel channel=null;FileLock lock=null;Throwable original=null;
        try {
            if(gate.lock.isHeldByCurrentThread())throw new IOException("lockの暗黙再入を拒否しました");
            while(!entered) {
                check(end,cancelled);
                try{entered=gate.lock.tryLock(Math.max(0,Math.min(end-System.nanoTime(),TimeUnit.MILLISECONDS.toNanos(10))),TimeUnit.NANOSECONDS);}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw interrupted();}
            }
            check(end,cancelled);
            try{Files.createFile(path);}catch(FileAlreadyExistsException exists){}
            String expected=identity(path,reader);
            channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            while(true) {
                check(end,cancelled);
                try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}
                if(lock!=null)break;
                try{TimeUnit.NANOSECONDS.sleep(Math.max(0,Math.min(end-System.nanoTime(),TimeUnit.MILLISECONDS.toNanos(10))));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw interrupted();}
            }
            if(!expected.equals(identity(path,reader)))throw new IOException("lock fileのidentityが変化しました");
            check(end,cancelled);retained=true;return new RuntimeLock(path,gate,channel,lock,expected,reader);
        }catch(IOException|RuntimeException|Error failure) {
            original=failure;throw failure;
        }finally {
            if(!retained) {
                try{closeResources(original,lock,channel);}
                finally{try{if(entered)gate.lock.unlock();}finally{release(path,gate);}}
            }
        }
    }
    static void closeResources(Throwable original,FileLock lock,FileChannel channel) throws IOException {
        Throwable failure=original;
        try{if(lock!=null)lock.release();}
        catch(IOException|RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        try{if(channel!=null)channel.close();}
        catch(IOException|RuntimeException|Error cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if(original==null&&failure!=null) {
            if(failure instanceof IOException error)throw error;
            if(failure instanceof RuntimeException error)throw error;
            throw (Error)failure;
        }
    }
    private static void requireRegistry() throws IOException {
        var properties=System.getProperties();
        synchronized(properties) {
            String owner=properties.getProperty(REGISTRY_PROPERTY);
            if(owner==null)properties.setProperty(REGISTRY_PROPERTY,REGISTRY_OWNER);
            else if(!owner.equals(REGISTRY_OWNER))throw new IOException("別classloaderによるroot lock registryの重複を拒否しました");
        }
    }
    private static String identity(Path file,LeaseTrust.IdentityReader reader) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file))throw new IOException("lockが通常fileではありません");
        if(Files.getFileStore(file).supportsFileAttributeView("unix")&&((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("lockにhardlinkがあります");
        Object key=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        String value=key==null?reader==null?null:reader.read(file):key.toString();
        if(value==null||value.isBlank())throw new IOException("lock fileのidentityを確認できません");
        return value;
    }
    private static void release(Path file,Gate gate) {
        synchronized(gates){if(--gate.users==0)gates.remove(file,gate);}
    }
    private static void check(long end,BooleanSupplier cancelled) throws IOException {
        if(Thread.currentThread().isInterrupted())throw interrupted();
        if(cancelled.getAsBoolean())throw new IOException("lock取得が取り消されました");
        if(System.nanoTime()>=end)throw new IOException("lockの絶対期限を超えました");
    }
    private static InterruptedIOException interrupted(){return new InterruptedIOException("lock待機が中断されました");}
    /** 集合更新が元root排他の所有threadであることを確認する。再取得はしない。 */
    public void requireHeld(Path expected) throws IOException {
        requireOwned(expected);
        if(!key.equals(identity(file,reader)))throw new IOException("元root lockを保持していません");
    }
    void requireOwned(Path expected) throws IOException {
        if(closed||Thread.currentThread()!=owner||!file.equals(expected.toAbsolutePath().normalize())||!lock.isValid())throw new IOException("元root lockを保持していません");
    }
    @Override public void close() throws IOException {
        if(Thread.currentThread()!=owner)throw new IOException("lock tokenの所有threadが一致しません");
        if(closed)return;closed=true;
        try{closeResources(null,lock,channel);}finally{try{gate.lock.unlock();}finally{release(file,gate);}}
    }
}
