package io.github.rs0325.mcicon.helper.macos;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheStateTest {
    @TempDir Path temporary;
    private final List<Path> anchors=new ArrayList<>();
    private CacheState state() throws IOException {
        Path root=temporary.toRealPath().resolve("long-"+"a".repeat(150));Files.createDirectories(root);
        long uid=((Number)Files.getAttribute(root,"unix:uid")).longValue();CacheState state=new CacheState(root,UUID.randomUUID().toString(),"1".repeat(64),uid,"a".repeat(64),"00000000-0000-0000-0000-000000000001");anchors.add(state.anchor());return state;
    }
    @AfterEach void cleanup() throws IOException {for(Path root:anchors)if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(root,new SimpleFileVisitor<>() {public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path directory,IOException error) throws IOException {if(error!=null)throw error;Files.delete(directory);return FileVisitResult.CONTINUE;}});}
    @Test void longRootUsesOwnedShortCacheAndRetainsLostRegistration() throws Exception {
        CacheState state=state();try(var lock=state.lock(Duration.ofSeconds(1))) {var registration=state.register(true);state.verify();assertTrue(state.socket().toString().length()<104);assertEquals("v",registration.path("instance").asText());}
        cleanup();assertFalse(state.firstUse());assertThrows(IOException.class,()->state.register(false));assertThrows(IOException.class,()->state.register(true));assertFalse(Files.exists(state.anchor(),LinkOption.NOFOLLOW_LINKS));
    }
    @Test void unknownAnchorAndSymlinkAreNotTakenOver() throws Exception {
        CacheState state=state();Files.createDirectory(state.anchor());assertThrows(IOException.class,()->state.register(true));Files.delete(state.anchor());Files.createSymbolicLink(state.anchor(),temporary.toRealPath());assertThrows(IOException.class,()->state.register(true));
    }
    @Test void changedMarkerAndPermissionsAreRejected() throws Exception {
        CacheState state=state();state.register(true);var marker=(com.fasterxml.jackson.databind.node.ObjectNode)MacFiles.json(state.anchor().resolve("owner.json"));marker.put("root","/wrong");MacFiles.write(state.anchor().resolve("owner.json"),marker);assertThrows(IOException.class,state::verify);
        marker.put("root",state.registered().path("root").asText());MacFiles.write(state.anchor().resolve("owner.json"),marker);Files.setPosixFilePermissions(state.anchor(),PosixFilePermissions.fromString("rwxr-xr-x"));assertThrows(IOException.class,state::verify);
    }
    @Test void 別hostと旧field無し登録を移行せず拒否する() throws Exception {
        CacheState state=state();var original=state.register(true);Path registration=temporary.toRealPath().resolve("long-"+"a".repeat(150)).resolve("macos").resolve("1".repeat(64)+".json");
        var marker=original.deepCopy();marker.remove("hostBindingSha256");MacFiles.write(registration,marker);byte[] retained=Files.readAllBytes(registration);assertThrows(IOException.class,()->state.register(false));assertArrayEquals(retained,Files.readAllBytes(registration));
        marker=original.deepCopy();marker.put("hostBindingSha256","b".repeat(64));MacFiles.write(registration,marker);retained=Files.readAllBytes(registration);assertThrows(IOException.class,state::verify);assertArrayEquals(retained,Files.readAllBytes(registration));
    }
    @Test void boot差だけではcacheを置換せず同boot欠落も保持する() throws Exception {
        CacheState state=state();var original=state.register(true);Path root=Path.of(original.path("root").asText());
        CacheState after=new CacheState(root,original.path("hostId").asText(),"1".repeat(64),original.path("uid").asLong(),"a".repeat(64),"00000000-0000-0000-0000-000000000002");
        assertTrue(after.registered().equals(original));assertThrows(IOException.class,after::verify);assertThrows(IOException.class,()->after.register(true));assertTrue(state.registered().equals(original));
        cleanup();assertThrows(IOException.class,()->after.register(true));assertThrows(IOException.class,()->state.register(true));assertFalse(Files.exists(state.anchor(),LinkOption.NOFOLLOW_LINKS));
    }
    @Test void lifecycleLockHasFiniteWait() throws Exception {
        CacheState state=state();try(var held=state.lock(Duration.ofSeconds(1))) {assertThrows(IOException.class,()->state.lock(Duration.ofMillis(30)));}
    }
}
