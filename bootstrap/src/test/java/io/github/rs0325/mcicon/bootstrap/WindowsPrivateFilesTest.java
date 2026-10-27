package io.github.rs0325.mcicon.bootstrap;

import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsPrivateFilesTest {
    static final String USER="S-1-5-21-1",OTHER="S-1-5-21-2",ADMIN="S-1-5-32-544";
    static final String TI="S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
    private ObjectNode observation(int count) {
        ObjectNode value=Seed.JSON.createObjectNode().put("tokenUserSid",USER).put("tokenDefaultOwnerSid",ADMIN);ArrayNode rows=value.putArray("rows");
        for(int i=0;i<count;i++)rows.addObject().put("ownerSid",USER).put("nodeType","directory").put("controlFlags",4).put("daclPresent",true).put("daclNull",false).putArray("aces");return value;
    }
    private ObjectNode allow(ObjectNode value,int row,String sid,long mask,int flags){return ((ArrayNode)value.path("rows").get(row).get("aces")).addObject().put("type",0).put("flags",flags).put("rawMask",mask).put("sid",sid);}
    private void check(ObjectNode value,int managed,boolean create,boolean dir) throws IOException {WindowsPrivateFiles.check(value,USER,managed,create,dir);}
    @Test void readOnlyOthersAndTrustedOwnersAreAccepted() throws Exception {
        ObjectNode v=observation(2);((ObjectNode)v.path("rows").get(0)).put("ownerSid",ADMIN);allow(v,0,OTHER,0x1200a9,0);allow(v,1,OTHER,0x120089,0);assertDoesNotThrow(()->check(v,1,false,false));
    }
    @Test void managedAdminAndUnknownDefaultOwnerAreDifferentFailures() throws Exception {
        ObjectNode v=observation(1);((ObjectNode)v.path("rows").get(0)).put("ownerSid",ADMIN);assertThrows(IOException.class,()->check(v,0,false,false));
        ObjectNode fresh=observation(1);fresh.put("tokenDefaultOwnerSid",OTHER);assertDoesNotThrow(()->check(fresh,1,false,false));assertThrows(IOException.class,()->check(fresh,1,true,false));assertThrows(IOException.class,()->check(fresh,1,true,true));
    }
    @Test void trustedInstallerIsExistingAncestorDirectoryOwnerOnly() throws Exception {
        ObjectNode v=observation(2);((ObjectNode)v.path("rows").get(0)).put("ownerSid",TI);check(v,1,false,false);check(v,1,true,true);check(v,1,true,false);
        ObjectNode managed=observation(1);((ObjectNode)managed.path("rows").get(0)).put("ownerSid",TI);assertThrows(IOException.class,()->check(managed,0,false,false));
        ObjectNode file=observation(1);((ObjectNode)file.path("rows").get(0)).put("ownerSid",TI).put("nodeType","regular-file");assertThrows(IOException.class,()->check(file,1,false,false));
        for(String owner:new String[]{"S-1-5-80-1-2-3-4-5",TI.substring(0,TI.length()-1)+"5"}) {ObjectNode other=observation(2);((ObjectNode)other.path("rows").get(0)).put("ownerSid",owner);assertThrows(IOException.class,()->check(other,1,false,false));}
    }
    @Test void trustedInstallerDoesNotBecomeTrustedAceOrDefaultOwner() throws Exception {
        ObjectNode v=observation(2);((ObjectNode)v.path("rows").get(0)).put("ownerSid",TI);ObjectNode entry=allow(v,0,TI,0x1200a9,0);check(v,1,false,false);entry.put("rawMask",0x40000);assertThrows(IOException.class,()->check(v,1,false,false));
        ObjectNode fresh=observation(1);fresh.put("tokenDefaultOwnerSid",TI);allow(fresh,0,"S-1-3-0",0x10000000,11);assertThrows(IOException.class,()->check(fresh,1,true,true));assertThrows(IOException.class,()->check(fresh,1,true,false));
        ObjectNode unknown=observation(2);((ObjectNode)unknown.path("rows").get(0)).put("ownerSid",TI);allow(unknown,0,USER,0,0).put("unknown",true);assertThrows(IOException.class,()->check(unknown,1,false,false));
    }
    @Test void genericWriteIsNotConfusedWithReadControl() throws Exception {
        assertEquals(0x120116,WindowsPrivateFiles.mask(0x40000000));ObjectNode v=observation(1);ObjectNode ace=allow(v,0,OTHER,0x20000,0);check(v,0,false,false);ace.put("rawMask",0x40020000);assertThrows(IOException.class,()->check(v,0,false,false));
    }
    @Test void ancestorAddRightsNeedProtectedObservedChild() throws Exception {
        for(long rights:new long[]{2,4,6}) {
            ObjectNode v=observation(2);allow(v,0,OTHER,rights,0);check(v,1,false,false);
            for(long change:new long[]{0x10,0x100,0x10000,0x40000,0x80000}) {
                ObjectNode child=observation(2);allow(child,0,OTHER,rights,0);allow(child,1,OTHER,change,0);assertThrows(IOException.class,()->check(child,1,false,false));
            }
            ObjectNode parent=observation(2);allow(parent,0,OTHER,rights|0x40,0);assertThrows(IOException.class,()->check(parent,1,false,false));
            for(boolean creating:new boolean[]{false,true})for(boolean directory:new boolean[]{false,true}) {
                ObjectNode terminal=observation(1);allow(terminal,0,OTHER,rights,0);assertThrows(IOException.class,()->check(terminal,1,creating,directory));
            }
        }
    }
    @Test void componentChangesAndManagedWritesAreRejected() {
        for(long mask:new long[]{0x10,0x100,0x10000,0x40000,0x80000}){ObjectNode v=observation(2);allow(v,0,OTHER,mask,0);assertThrows(IOException.class,()->check(v,1,false,false),Long.toHexString(mask));}
        for(long mask:new long[]{2,4,0x40}){ObjectNode v=observation(1);allow(v,0,OTHER,mask,0);assertThrows(IOException.class,()->check(v,0,false,false));}
    }
    @Test void objectOnlyTemplateDoesNotGiveDirectoryAccessButFileCreationFails() throws Exception {
        ObjectNode v=observation(1);allow(v,0,OTHER,2,9);check(v,1,true,true);assertThrows(IOException.class,()->check(v,1,true,false));
        ObjectNode np=observation(1);allow(np,0,OTHER,2,13);check(np,1,true,true);assertThrows(IOException.class,()->check(np,1,true,false));
    }
    @Test void containerInheritanceAffectsDirectoryOnlyAndNoPropagateDoesNotRescueIt() throws Exception {
        for(int flags:new int[]{10,14,15}){ObjectNode v=observation(1);allow(v,0,OTHER,2,flags);assertThrows(IOException.class,()->check(v,1,true,true));if((flags&1)==0)check(v,1,true,false);}
    }
    @Test void creatorOwnerUsesTrustedDefaultOwnerButNotUnknownGroups() throws Exception {
        ObjectNode v=observation(1);allow(v,0,"S-1-3-0",0x10000000,11);check(v,1,true,true);check(v,1,true,false);v.put("tokenDefaultOwnerSid",OTHER);assertThrows(IOException.class,()->check(v,1,true,false));
    }
    @Test void denyDoesNotCancelDangerousAllowAndUnknownMetadataIsRejected() throws Exception {
        ObjectNode v=observation(1);allow(v,0,OTHER,0x10000000,0).put("type",1);allow(v,0,OTHER,2,0);assertThrows(IOException.class,()->check(v,0,false,false));
        for(long mask:new long[]{0x02000000,0x1000}){ObjectNode x=observation(1);allow(x,0,USER,mask,0);assertThrows(IOException.class,()->check(x,0,false,false));}
        ObjectNode unknown=observation(1);allow(unknown,0,USER,0,0).put("unknown",true);assertThrows(IOException.class,()->check(unknown,0,false,false));
        ObjectNode flags=observation(1);allow(flags,0,USER,0,0x20);assertThrows(IOException.class,()->check(flags,0,false,false));
    }
}
