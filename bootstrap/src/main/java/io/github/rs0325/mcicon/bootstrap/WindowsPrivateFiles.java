package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.security.auth.module.NTSystem;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** trusted installer prefixから起動し、本人stateの安全な親と新規nodeの使用前所有を確認する。 */
final class WindowsPrivateFiles {
    private static final String TRUSTED_INSTALLER="S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
    private final Path root;
    private final WindowsSecurityReader reader;
    private final String user;
    private final UserPrincipal principal;
    WindowsPrivateFiles(Path installed,Path root) throws IOException {
        this.root=root;reader=new WindowsSecurityReader(installed);NTSystem token=new NTSystem();user=token.getUserSID();
        principal=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(token.getDomain()+"\\"+token.getName());
    }
    private static boolean trusted(String sid,String user){return sid.equals(user)||sid.equals("S-1-5-32-544")||sid.equals("S-1-5-18");}
    static long mask(long raw) throws IOException {
        if((raw&~0xf01f01ffL)!=0)throw Seed.invalid("未知のWindows access maskです");
        long value=raw&0x1f01ff;if((raw&0x80000000L)!=0)value|=0x120089;if((raw&0x40000000L)!=0)value|=0x120116;if((raw&0x20000000L)!=0)value|=0x1200a0;if((raw&0x10000000L)!=0)value|=0x1f01ff;return value;
    }
    private static void ace(JsonNode ace) throws IOException {
        if(ace.path("unknown").asBoolean()||ace.path("type").intValue()>1||(ace.path("flags").longValue()&~0x1fL)!=0)throw Seed.invalid("未知のWindows ACEです");mask(ace.path("rawMask").longValue());
    }
    private static boolean special(String sid){return sid.startsWith("S-1-3-");}
    private static void permissions(JsonNode ace,String user,long forbidden,String creator) throws IOException {
        String sid=Seed.text(ace,"sid");if(sid.equals("S-1-3-0")&&creator!=null)sid=creator;else if(special(sid))throw Seed.invalid("未対応のWindows特殊SIDです");
        if(!trusted(sid,user)&&(mask(ace.path("rawMask").longValue())&forbidden)!=0)throw Seed.invalid("他userによるWindows node変更を拒否しました");
    }
    /** readerで型・chainを検証済みのraw観測に、限定ALLOW policyを適用する。DENYで危険ALLOWを救済しない。 */
    static void check(JsonNode observation,String user,int managedFrom,boolean creating,boolean newDirectory) throws IOException {
        if(!user.equals(Seed.text(observation,"tokenUserSid")))throw Seed.invalid("Windows token userが不一致です");
        String defaultOwner=Seed.text(observation,"tokenDefaultOwnerSid");if(creating&&!trusted(defaultOwner,user))throw Seed.invalid("新nodeのdefault ownerが不明です");
        JsonNode rows=observation.get("rows");for(int i=0;i<rows.size();i++) {
            JsonNode row=rows.get(i);String owner=Seed.text(row,"ownerSid");boolean managed=i>=managedFrom;
            if(managed?!owner.equals(user):!trusted(owner,user)&&!(owner.equals(TRUSTED_INSTALLER)&&row.path("nodeType").asText().equals("directory")))throw Seed.invalid("Windows nodeの所有が不明です");
            if(!row.path("daclPresent").asBoolean()||row.path("daclNull").asBoolean()||(row.path("controlFlags").longValue()&~0xbf3fL)!=0)throw Seed.invalid("Windows DACLが不明です");
            boolean child=i+1<rows.size();long forbidden=managed?0xd0156:0xd0110;
            if(child||creating&&i==rows.size()-1)forbidden|=0x40;
            if(!managed&&!child)forbidden|=6;
            for(JsonNode entry:row.get("aces")) {
                ace(entry);if(entry.path("type").intValue()==1)continue;
                int flags=entry.path("flags").intValue();
                if((flags&8)==0)permissions(entry,user,forbidden,null);
                if(creating&&i==rows.size()-1&&(flags&(newDirectory?2:1))!=0)permissions(entry,user,0xd0156,defaultOwner);
            }
        }
    }
    private List<Path> chain(Path path) {
        List<Path> paths=new ArrayList<>();for(Path p=path;p!=null;p=p.getParent())paths.add(p);Collections.reverse(paths);return paths;
    }
    private JsonNode observe(Path path,boolean file,boolean creating,boolean newDirectory,long deadline) throws IOException {Seed.check(deadline);
        List<Path> paths=chain(path);int start=paths.indexOf(root);JsonNode value=file?reader.readFile(paths,deadline):reader.read(paths,deadline);check(value,user,start<0?paths.size():start,creating,newDirectory);Seed.check(deadline);return value;
    }
    void verify(Path path,boolean file,long deadline) throws IOException {Seed.check(deadline);
        if(!path.startsWith(root))throw Seed.invalid("managed root外の所有検査です");observe(path,file,false,false,deadline);
    Seed.check(deadline);}
    private FileAttribute<List<AclEntry>> attributes() {
        List<AclEntry> acl=List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        return new FileAttribute<>() {public String name(){return "acl:acl";}public List<AclEntry> value(){return acl;}};
    }
    void directory(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(!Files.notExists(path,LinkOption.NOFOLLOW_LINKS)){verify(path,false,deadline);return;}
        Path parent=path.getParent();if(Files.notExists(parent,LinkOption.NOFOLLOW_LINKS))directory(parent,deadline);
        createDirectory(path,deadline);
    Seed.check(deadline);}
    void createDirectory(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(!path.startsWith(root)&&!root.startsWith(path))throw Seed.invalid("managed rootと無関係なdirectoryです");
        observe(path.getParent(),false,true,true,deadline);Seed.check(deadline);Files.createDirectory(path,attributes());Seed.check(deadline);Files.setOwner(path,principal);JsonNode value=observe(path,false,false,false,deadline);if(!value.path("rows").get(value.path("rows").size()-1).path("ownerSid").asText().equals(user))throw Seed.invalid("新directoryの本人所有を確認できません");
    Seed.check(deadline);}
    FileChannel create(Path path,long deadline) throws IOException {Seed.check(deadline);
        if(!path.startsWith(root))throw Seed.invalid("managed root外の作成です");
        observe(path.getParent(),false,true,false,deadline);
        Seed.check(deadline);FileChannel channel=FileChannel.open(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),attributes());
        try {Seed.check(deadline);Files.setOwner(path,principal);verify(path,true,deadline);Seed.check(deadline);return channel;}
        catch(IOException|RuntimeException failure){try{channel.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
    }
}
