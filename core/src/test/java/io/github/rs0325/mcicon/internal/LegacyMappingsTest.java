package io.github.rs0325.mcicon.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMappingsTest {
    @TempDir Path root;
    Path mappings(String name,String text) throws Exception {
        Path file=root.resolve(name);
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("mappings/mappings.tiny"));out.write(text.getBytes());out.closeEntry();
        }
        return file;
    }
    @Test void joinsDescriptorsIncludingArraysAndInnerClasses() throws Exception {
        Path base=mappings("base.jar","tiny\t2\t0\tofficial\tintermediary\nc\ta\tnet/middle/C\n\tm\t([Lb;I)Lb;\tx\tm1\nc\tb\tnet/middle/C$Inner\n");
        Path named=mappings("named.jar","tiny\t2\t0\tintermediary\tnamed\nc\tnet/middle/C\tnet/named/Example\n\tm\t([Lnet/middle/C$Inner;I)Lnet/middle/C$Inner;\tm1\trender\nc\tnet/middle/C$Inner\tnet/named/Example$Child\n");
        assertTrue(LegacyMappings.merge(base,named).contains("net.named.Example$Child render(net.named.Example$Child[],int) -> x"));
    }
}
