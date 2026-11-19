package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** package内の信頼集合と既知公開descriptorだけを認証根にする。 */
public final class LeaseTrust {
    private static final Set<String> LEGACY=Set.of(
            "2103835ca14e88d50cff70162585304af5aa13351babb0284b2f13dad2998101",
            "cdcd7ce552f0be140a57eae56c0497e4156238a1914f68a3a5bd2654e87c3294",
            "23757eae57959c789d1baa3cbd37a23592ed8c508d6b030ea740caf7ba1cc857",
            "e41d30a5241beac9e7276e5ad14caf9c279b5e8a9022123e8cd736221bb47d57");
    /** 呼出し元packageが認証済みのOS reader。file identityの代用品を返してはならない。 */
    @FunctionalInterface public interface IdentityReader {String read(Path path) throws IOException;}
    /** 任意pathの欠落を単一file queryへfallbackしない、上限付きの一括reader。 */
    @FunctionalInterface public interface BatchIdentityReader {Map<Path,String> readAll(List<Path> paths,Path scratch,long end) throws IOException;}
    private IdentityReader externalIdentity;
    private final ThreadLocal<IdentityCapture> capture=new ThreadLocal<>();
    private final TrustSet trust;
    private Path identityReader;
    private String readerSha256;
    private boolean readerVerified;
    private String currentDescriptor;
    private Set<String> descriptorPins;
    private boolean packageUnbound;
    private Path helperRoot;
    private JsonNode helperInventory;
    private LeaseTrust(TrustSet trust){this.trust=trust;}
    /** 認証済みの同梱公開鍵を渡す。runtime rootから鍵を選択してはならない。 */
    public static LeaseTrust verified(TrustSet trust){return new LeaseTrust(Objects.requireNonNull(trust));}
    public static LeaseTrust verified(TrustSet trust,IdentityReader reader){LeaseTrust result=verified(trust);result.externalIdentity=Objects.requireNonNull(reader);return result;}
    /** 署名済みslotから渡した現在descriptor pinと公開旧4件へ保守参照を限定する。 */
    public static LeaseTrust pinned(TrustSet trust,String descriptor,IdentityReader reader) {
        LeaseTrust result=verified(trust);if(descriptor!=null&&!descriptor.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("descriptor pinが不正です");
        result.descriptorPins=descriptor==null?Set.of():Set.of(descriptor);result.externalIdentity=reader;return result;
    }
    /** 認証済みreaderを保ったまま現在pinを限定する。既存の許可範囲を広げない。 */
    public LeaseTrust restrictTo(String descriptor) {
        if(!descriptor.matches("[0-9a-f]{64}")||descriptorPins!=null&&!descriptorPins.contains(descriptor)||currentDescriptor!=null&&!currentDescriptor.equals(descriptor))throw new IllegalArgumentException("現在descriptor pinが一致しません");
        descriptorPins=Set.of(descriptor);return this;
    }
    /** 既知公開4件だけを参照する保守用の信頼入力。 */
    public static LeaseTrust legacy(){return new LeaseTrust(null);}
    public static boolean legacy(String digest){return LEGACY.contains(digest);}
    public static Set<String> legacyDescriptors(){return LEGACY;}
    /** 実行packageの固定supportから公開鍵だけを読む。 */
    public static LeaseTrust packaged(Path executable,String os) throws IOException {
        Path app=os.equals("macos")?executable.getParent().getParent():executable.getParent();
        LeaseTrust result=verified(TrustSet.parse(LeaseSnapshot.readFile(app.resolve("app/support/lease-trust.bin"),TrustSet.SIZE)));
        result.packageUnbound=true;
        if(os.equals("windows")){result.identityReader=app.resolve("app/support/windows-host.exe");result.readerSha256=LeaseSnapshot.fileDigest(result.identityReader);}
        return result;
    }
    /** 現descriptor・helper実体を照合し、Windowsでは起動するidentity readerも先に認証する。 */
    public void bindReader(Path root,byte[] descriptor,byte[] inventory,Path executable) throws IOException {
        if(!LEGACY.contains(LeaseSnapshot.digest(descriptor)))verifySignature(root,descriptor);JsonNode definition=LeaseSnapshot.read(descriptor),selected=null;
        for(JsonNode artifact:definition.path("artifacts"))if(artifact.path("id").equals(definition.path("helper").path("artifactId")))selected=artifact;
        if(selected==null||!LeaseSnapshot.digest(inventory).equals(LeaseSnapshot.hash(selected,"inventorySha256")))throw new IOException("reader元inventoryが一致しません");
        Path helperRoot=root.resolve("helpers").resolve(LeaseSnapshot.text(definition.path("host"),"os",16)+"-"+LeaseSnapshot.text(definition.path("host"),"arch",16)).resolve(LeaseSnapshot.hash(selected,"sha256"));
        if(!helperRoot.resolve(LeaseSnapshot.relative(definition.path("helper"),"executable")).equals(executable))throw new IOException("readerのhelper世代が一致しません");
        if(!executable.toRealPath().equals(executable)||!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS))throw new IOException("現helper実体にlinkがあります");
        JsonNode listing=LeaseSnapshot.read(inventory);
        JsonNode executablePin=LeaseSnapshot.helperPin(listing,helperRoot.relativize(executable).toString().replace('\\','/'));
        if(Files.size(executable)!=LeaseSnapshot.integer(executablePin,"bytes",1)||!LeaseSnapshot.fileDigest(executable).equals(LeaseSnapshot.hash(executablePin,"sha256")))throw new IOException("現helperの実体と元inventoryが一致しません");
        if(identityReader==null){currentDescriptor=LeaseSnapshot.digest(descriptor);packageUnbound=false;this.helperRoot=helperRoot;helperInventory=listing.deepCopy();return;}
        if(!identityReader.toRealPath().equals(identityReader)||!Files.isRegularFile(identityReader,LinkOption.NOFOLLOW_LINKS))throw new IOException("identity readerにlinkがあります");
        String relative=helperRoot.relativize(identityReader).toString().replace('\\','/');
        JsonNode pin=LeaseSnapshot.helperPin(listing,relative);
        if(Files.size(identityReader)!=LeaseSnapshot.integer(pin,"bytes",1)||!readerSha256.equals(LeaseSnapshot.hash(pin,"sha256")))throw new IOException("署名descriptorとreader実体が一致しません");
        readerVerified=true;currentDescriptor=LeaseSnapshot.digest(descriptor);packageUnbound=false;this.helperRoot=helperRoot;helperInventory=listing.deepCopy();
    }
    /** job公開前に必要な能力宣言/probeを、束縛済みhelper inventoryから認証する。 */
    public Path boundHelperFile(String relative) throws IOException {
        if(helperRoot==null||helperInventory==null||packageUnbound)throw new IOException("helper readerが未認証です");
        String name=LeaseSnapshot.relative(LeaseSnapshot.JSON.createObjectNode().put("path",relative),"path");
        Path file=helperRoot.resolve(name);JsonNode pin=LeaseSnapshot.helperPin(helperInventory,name);
        if(!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=LeaseSnapshot.integer(pin,"bytes",1)||!LeaseSnapshot.fileDigest(file).equals(LeaseSnapshot.hash(pin,"sha256")))throw new IOException("helper supportのpinが一致しません");
        return file;
    }
    /** 同じ認証済みinventoryのpinへ読取bytes自身を照合する。読取前後の置換も拒否する。 */
    public byte[] boundHelperBytes(String relative,int limit) throws IOException {
        Path file=boundHelperFile(relative);String key=fileIdentity(file);
        byte[] bytes=LeaseSnapshot.readFile(file,limit);
        JsonNode pin=LeaseSnapshot.helperPin(helperInventory,relative);
        if(bytes.length!=LeaseSnapshot.integer(pin,"bytes",1)||!LeaseSnapshot.digest(bytes).equals(LeaseSnapshot.hash(pin,"sha256"))||!key.equals(fileIdentity(file)))throw new IOException("helper supportの元bytes/identityが一致しません");
        return bytes;
    }
    /** 認証済みreaderで元file/directoryのidentityを得る。pathや時刻を代用品にしない。 */
    public String fileIdentity(Path file) throws IOException {
        if(capture.get()!=null)return capture.get().identity(file);
        if(!file.toRealPath().equals(file))throw new IOException("所有pathにlinkがあります");
        if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS)) {
            Object key=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            if(key!=null)return key.toString();
        }
        return identity(file).toString();
    }
    Object identity(Path file) throws IOException {
        if(capture.get()!=null)return capture.get().identity(file);
        if(externalIdentity!=null)return externalIdentity.read(file);
        if(Files.getFileStore(file).supportsFileAttributeView("unix")&&((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("所有fileにhardlinkがあります");
        Object key=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();if(key!=null)return key;
        if(identityReader==null||!readerVerified||!readerSha256.equals(LeaseSnapshot.fileDigest(identityReader)))throw new IOException("file identityの認証済みreaderがありません");
        return FileIdentityReader.read(identityReader,file);
    }
    /** root排他の同一区間でだけidentityを固定する。効果確定前にrevalidateを呼ぶ。 */
    public IdentityCapture captureIdentities(Path root,RuntimeLock held,List<Path> paths,Path scratch,long end) throws IOException {
        WindowsFileIdentities.check(end);
        if(capture.get()!=null)throw new IOException("identity captureの再入を拒否しました");
        held.requireHeld(root.resolve("locks/generations.lock"));
        List<Path> selected=new ArrayList<>(new LinkedHashSet<>(paths));
        if(!selected.contains(root))selected.add(root);
        Path lock=root.resolve("locks/generations.lock");if(!selected.contains(lock))selected.add(lock);
        IdentityCapture scope=new IdentityCapture(this,root,held,List.copyOf(selected),scratch,end);
        capture.set(scope);return scope;
    }
    /** lock取得時などcapture開始前の単独観測も、呼出し元の絶対期限へ通算する。 */
    public String fileIdentity(Path path,Path scratch,long end) throws IOException {
        WindowsFileIdentities.check(end);
        if(capture.get()!=null)return capture.get().identity(path);
        return readIdentities(List.of(path),scratch,end).get(path);
    }
    Map<Path,String> readIdentities(List<Path> paths,Path scratch,long end) throws IOException {
        WindowsFileIdentities.check(end);
        if(paths.isEmpty()||new HashSet<>(paths).size()!=paths.size())throw new IOException("identity capture件数が不正です");
        if(paths.size()>256) {
            Map<Path,String> result=new LinkedHashMap<>();Set<String> identities=new HashSet<>();
            for(int offset=0;offset<paths.size();offset+=256) {
                WindowsFileIdentities.check(end);
                Map<Path,String> batch=readIdentities(paths.subList(offset,Math.min(paths.size(),offset+256)),scratch,end);
                for(var entry:batch.entrySet())if(!identities.add(entry.getValue()))throw new IOException("identity batch間で同実体が重複しています");
                result.putAll(batch);
            }
            WindowsFileIdentities.check(end);return Map.copyOf(result);
        }
        if(externalIdentity instanceof BatchIdentityReader batch) {
            Map<Path,String> result=batch.readAll(paths,scratch,end);WindowsFileIdentities.check(end);return result;
        }
        if(identityReader!=null) {
            if(!readerVerified)throw new IOException("identity batch readerが未認証です");
            return new WindowsFileIdentities(identityReader,readerSha256).readAll(paths,scratch,end);
        }
        Map<Path,String> result=new LinkedHashMap<>();
        for(Path path:paths) {
            WindowsFileIdentities.check(end);
            if(!path.toRealPath().equals(path))throw new IOException("identity capture pathにlinkがあります");
            if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.getFileStore(path).supportsFileAttributeView("unix")&&((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("identity capture fileにhardlinkがあります");
            Object key=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            if(key==null)throw new IOException("identity batchの認証済みreaderがありません");
            result.put(path,key.toString());
        }
        if(new HashSet<>(result.values()).size()!=result.size())throw new IOException("identity captureに同実体があります");
        WindowsFileIdentities.check(end);return Map.copyOf(result);
    }
    void attach(LeaseSnapshot snapshot,java.nio.channels.FileChannel receipt) throws IOException {
        if(capture.get()!=null)capture.get().attach(snapshot,receipt);
    }
    boolean helperVerified(Path path,long bytes,String sha256) throws IOException {
        return capture.get()!=null&&capture.get().helperVerified(path,bytes,sha256);
    }
    void markHelperVerified(Path path) throws IOException {
        if(capture.get()!=null)capture.get().markHelperVerified(path);
    }
    String helperDigest(Path path,long bytes) throws IOException {
        return capture.get()==null?null:capture.get().helperDigest(path,bytes);
    }
    void endCapture(IdentityCapture scope) throws IOException {
        if(capture.get()!=scope)throw new IOException("identity capture所有者が一致しません");
        capture.remove();
    }
    void requireCaptureEnded() throws IOException {
        if(capture.get()!=null)throw new IOException("稼働参照の更新前にidentity captureを終了してください");
    }
    void verify(Path root,byte[] descriptor) throws IOException {
        String digest=LeaseSnapshot.digest(descriptor);
        if(LEGACY.contains(digest))return;
        if(trust==null||packageUnbound||descriptorPins!=null&&!descriptorPins.contains(digest)||currentDescriptor!=null&&!currentDescriptor.equals(digest))throw new IOException("既知の署名descriptorではありません");
        verifySignature(root,descriptor);
    }
    private void verifySignature(Path root,byte[] descriptor) throws IOException {
        if(trust==null)throw new IOException("署名検証用の固定trustがありません");String digest=LeaseSnapshot.digest(descriptor);
        JsonNode evidence=LeaseSnapshot.read(LeaseSnapshot.readFile(evidence(root,digest),4096));
        LeaseSnapshot.fields(evidence,"schemaVersion","descriptorSha256","signature");
        if(LeaseSnapshot.integer(evidence,"schemaVersion",1)!=1||!digest.equals(LeaseSnapshot.hash(evidence,"descriptorSha256")))throw new IOException("descriptor署名証拠の世代が一致しません");
        String encoded=LeaseSnapshot.text(evidence,"signature",88);
        byte[] signature;
        try{signature=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException failure){throw new IOException("descriptor署名の符号化が不正です",failure);}
        if(!Base64.getEncoder().encodeToString(signature).equals(encoded))throw new IOException("descriptor署名は正規Base64が必要です");
        trust.verify(descriptor,signature);
    }
    /** 認証済み署名を旧readerが走査しないlogsへdurable保存する。既存の異なる証拠は上書きしない。 */
    public static void publish(Path root,byte[] descriptor,byte[] signature,TrustSet trust) throws IOException {
        byte[] bytes=evidence(descriptor,signature,trust);Path file=evidence(root,LeaseSnapshot.digest(descriptor)),directory=file.getParent();
        Files.createDirectories(directory);if(!directory.toRealPath().equals(directory))throw new IOException("署名証拠directoryにlinkがあります");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(bytes,LeaseSnapshot.readFile(file,4096)))throw new IOException("既存の署名証拠が一致しません");return;}
        Path staged=Files.createTempFile(directory,"descriptor-",".tmp");
        try {
            try(var channel=java.nio.channels.FileChannel.open(staged,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(staged,file,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(staged);}
    }
    public static Path evidence(Path root,String digest) {
        if(!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("descriptor SHAが不正です");
        return root.resolve("logs/descriptor-"+digest+".trust-v1.json");
    }
    /** coreが認証済みの元署名を固定証拠へ変換する。署名の検証を省略しない。 */
    public static byte[] evidence(byte[] descriptor,byte[] signature,TrustSet trust) throws IOException {
        trust.verify(descriptor,signature);
        ObjectNode value=LeaseSnapshot.JSON.createObjectNode().put("schemaVersion",1).put("descriptorSha256",LeaseSnapshot.digest(descriptor)).put("signature",Base64.getEncoder().encodeToString(signature));
        return LeaseSnapshot.JSON.writeValueAsBytes(value);
    }
}
