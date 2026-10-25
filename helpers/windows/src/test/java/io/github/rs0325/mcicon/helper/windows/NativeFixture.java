package io.github.rs0325.mcicon.helper.windows;

import java.nio.file.*;
import java.util.*;

/** Java監督fixture。子孫を作らず、Windows Jobの実証には用いない。 */
public final class NativeFixture {
    public static void main(String[] args) throws Exception {
        Path record=Path.of(args[3]);var value=WindowsFiles.JSON.createObjectNode().put("parent",Long.parseLong(args[1])).put("parentStarted",Long.parseLong(args[2])).put("pid",ProcessHandle.current().pid()).put("started",ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()).put("boot",OwnershipTest.BOOT);
        Files.write(record.resolveSibling(record.getFileName()+".state.json"),WindowsFiles.JSON.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
        if(args[5].endsWith("fixture-sleep"))Thread.sleep(30000);
        System.out.write(WindowsFiles.JSON.writeValueAsBytes(Arrays.asList(args).subList(5,args.length)));Files.write(record.resolveSibling(record.getFileName()+".done.json"),WindowsFiles.JSON.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }
}
