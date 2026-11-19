package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.*;

/** 1回のroot排他区間に限るidentity観測。効果確定前に全証拠を再検証する。 */
public final class IdentityCapture implements AutoCloseable {
    private record Snapshot(LeaseSnapshot value,FileChannel receipt) {}
    private final LeaseTrust trust;
    private final Path root,scratch;
    private final RuntimeLock held;
    private final List<Path> paths;
    private final Map<Path,String> identities;
    private final Map<Path,Snapshot> snapshots=new LinkedHashMap<>();
    private record Pin(long bytes,String sha256) {}
    private final Map<Path,Pin> helpers=new LinkedHashMap<>();
    private final Set<Path> verifiedHelpers=new HashSet<>();
    private final long end;
    private final Thread owner=Thread.currentThread();
    private boolean closed;

    IdentityCapture(LeaseTrust trust,Path root,RuntimeLock held,List<Path> paths,Path scratch,long end) throws IOException {
        this.trust=trust;this.root=root;this.held=held;this.paths=paths;this.scratch=scratch;this.end=end;
        Map<Path,String> observed=trust.readIdentities(paths,scratch,end);
        requireComplete(observed);identities=Map.copyOf(observed);
    }
    private void requireComplete(Map<Path,String> values) throws IOException {
        if(!values.keySet().equals(new HashSet<>(paths))||values.values().stream().anyMatch(value->value==null||value.isBlank())||new HashSet<>(values.values()).size()!=values.size())throw new IOException("identity batch応答に欠落または同実体があります");
    }
    private void check() throws IOException {
        if(closed||Thread.currentThread()!=owner)throw new IOException("identity captureの所有区間外です");
        held.requireOwned(root.resolve("locks/generations.lock"));
        WindowsFileIdentities.check(end);
    }
    String identity(Path path) throws IOException {
        check();String value=identities.get(path);
        if(value==null)throw new IOException("identity captureに対象pathがありません");
        return value;
    }
    void attach(LeaseSnapshot snapshot,FileChannel receipt) throws IOException {
        check();snapshots.putIfAbsent(snapshot.file(),new Snapshot(snapshot,receipt));
    }
    boolean helperVerified(Path path,long bytes,String sha256) throws IOException {
        identity(path);Pin pin=new Pin(bytes,sha256);Pin previous=helpers.putIfAbsent(path,pin);
        if(previous!=null&&!previous.equals(pin))throw new IOException("同一helper実体のpinが競合しています");
        return verifiedHelpers.contains(path);
    }
    void markHelperVerified(Path path) throws IOException {check();verifiedHelpers.add(path);}
    String helperDigest(Path path,long bytes) throws IOException {
        identity(path);String result=LeaseSnapshot.fileDigest(path,bytes,end);check();return result;
    }
    /** 停止・公開・CASの直前に呼ぶ。identityと全元bytes、helper/JAR hashを再確認する。 */
    public void revalidate() throws IOException {
        check();held.requireHeld(root.resolve("locks/generations.lock"));
        verifyIdentities();
        verifiedHelpers.clear();
        for(Snapshot snapshot:snapshots.values()) {
            check();
            if(snapshot.receipt()!=null&&!snapshot.receipt().isOpen())throw new IOException("元receipt channelが閉じています");
            snapshot.value().verifyUnchanged(snapshot.receipt());
        }
        for(var helper:helpers.entrySet()) {
            check();Pin pin=helper.getValue();
            if(!helperDigest(helper.getKey(),pin.bytes()).equals(pin.sha256()))throw new IOException("helper実体のhashが一致しません");
        }
        verifiedHelpers.addAll(helpers.keySet());
        verifyIdentities();check();
    }
    private void verifyIdentities() throws IOException {
        Map<Path,String> current=trust.readIdentities(paths,scratch,end);
        requireComplete(current);
        if(!identities.equals(current))throw new IOException("identity capture中にfile/directoryが置換されました");
    }
    @Override public void close() throws IOException {
        if(Thread.currentThread()!=owner)throw new IOException("identity captureの所有threadが一致しません");
        if(closed)return;
        trust.endCapture(this);closed=true;
    }
}
