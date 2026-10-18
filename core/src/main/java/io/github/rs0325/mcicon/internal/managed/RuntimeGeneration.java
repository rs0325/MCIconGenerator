package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.internal.FilesSupport;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 内容digest別の世代を確定する。既存世代への上書きと使用中世代の削除は行わない。 */
public final class RuntimeGeneration {
    private final RuntimeStore store;
    public RuntimeGeneration(RuntimeStore store) {this.store=store;}
    /** 大容量guest/imageより先に取得・検証し、preflightを実行できるhelperを返す。 */
    public Path helper(RuntimeDescriptor descriptor) throws IOException {
        RuntimeDescriptor.Artifact artifact=descriptor.artifact(descriptor.helperArtifact());Path archive=store.obtain(artifact);
        try(var ignored=store.lock("helper-"+artifact.sha256())) {
            Path parent=store.path("helpers/"+descriptor.host().os()+"-"+descriptor.host().arch());Files.createDirectories(parent);
            Path destination=parent.resolve(artifact.sha256()),stage=parent.resolve("staging-"+UUID.randomUUID());
            try {
                RuntimeArchive.extract(archive,stage,artifact,store);
                Path executable=FilesSupport.safeResolve(stage,descriptor.helperExecutable());
                if(!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS))throw RuntimeDescriptor.invalid("helper executableがarchiveにありません");
                if(!descriptor.host().os().equals("windows")&&!executable.toFile().setExecutable(true,true))throw new IOException("helperの実行権限を設定できません");
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
                    if(!tree(stage).equals(tree(destination)))throw RuntimeDescriptor.invalid("既存helper世代が破損しています");
                }else Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);
                Path installed=FilesSupport.safeResolve(destination,descriptor.helperExecutable());
                if(!descriptor.host().os().equals("windows")&&!Files.isExecutable(installed))throw RuntimeDescriptor.invalid("helperの実行権限がありません");
                return installed;
            }finally {removeStage(stage);}
        }
    }
    /** preflight成功後に呼び、guest/rootfsとrendererをhostへ展開せず確定する。inventoryは同梱resourceの元bytesを渡す。 */
    public Path prepare(RuntimeDescriptor descriptor,Map<String,byte[]> inventories) throws IOException {
        for(var artifact:descriptor.artifacts()) {
            byte[] inventory=inventories.get(artifact.inventoryPath());if(inventory==null)throw RuntimeDescriptor.invalid("同梱inventoryがありません");descriptor.verifyInventory(artifact,inventory);
        }
        try(var ignored=store.lock("runtime-"+descriptor.digest())) {
            Path destination=store.generation(descriptor);Files.createDirectories(destination.getParent());
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){verify(destination,descriptor);return destination;}
            Path stage=destination.getParent().resolve("staging-"+UUID.randomUUID());Files.createDirectory(stage);
            try {
                for(var artifact:descriptor.artifacts()) {
                    store.check();if(artifact.role().equals("host-helper"))continue;
                    Path source=store.obtain(artifact);RuntimeStore.requireSpace(stage,Math.max(artifact.bytes(),artifact.unpackedBytes()));
                    store.copy(source,stage.resolve(artifact.id()+"."+artifact.format()),artifact.bytes());
                }
                verify(stage,descriptor);Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);return destination;
            }finally {removeStage(stage);}
        }
    }
    private void verify(Path directory,RuntimeDescriptor descriptor) throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw RuntimeDescriptor.invalid("runtime世代がdirectoryではありません");
        Set<String> expected=new HashSet<>();
        for(var artifact:descriptor.artifacts()) {
            if(artifact.role().equals("host-helper"))continue;
            store.check();String name=artifact.id()+"."+artifact.format();expected.add(name);
            if(!store.valid(FilesSupport.safeResolve(directory,name),artifact))throw RuntimeDescriptor.invalid("runtime世代のartifactが破損しています");
        }
        try(var files=Files.list(directory)) {if(!files.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected))throw RuntimeDescriptor.invalid("runtime世代に未知fileがあります");}
    }
    private Map<String,String> tree(Path root) throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw RuntimeDescriptor.invalid("helper世代がdirectoryではありません");
        Map<String,String> files=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.toList()) {
                store.check();if(Files.isSymbolicLink(path))throw RuntimeDescriptor.invalid("helper世代にlinkがあります");
                String relative=root.relativize(path).toString().replace('\\','/');
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))files.put(relative,"directory");
                else if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))files.put(relative,store.hash(path));
                else throw RuntimeDescriptor.invalid("helper世代に特殊fileがあります");
            }
        }
        return files;
    }
    private static void removeStage(Path stage) throws IOException {
        if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(stage,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure) throws IOException {if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });
    }
}
