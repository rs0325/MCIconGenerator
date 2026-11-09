package io.github.rs0325.mcicon.helper;

import io.github.rs0325.mcicon.internal.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 公開元bytesと候補用テスト鍵fixtureの検証を混同しない。 */
class PublishedDescriptorsTest {
    @Test void 公開旧四descriptorの元署名と固定hashを検証する() throws Exception {
        String configured=System.getProperty("mcicon.publishedDescriptors");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured!=null,"公開asset照合は明示入力で実行する");
        Path root=Path.of(configured);TrustSet trust=TrustSet.parse(Files.readAllBytes(root.resolve("public-trust-set.bin")));
        for(String version:new String[]{"1.1.0","1.1.1"})for(String host:new String[]{"macos-aarch64","windows-x86_64"}) {
            byte[] original=Files.readAllBytes(root.resolve(version+"-"+host+".json")),signature=Files.readAllBytes(root.resolve(version+"-"+host+".json.sig"));
            trust.verify(original,signature);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));assertTrue(LeaseTrust.legacy(digest));
            byte[] changed=original.clone();changed[changed.length-1]^=1;assertThrows(java.io.IOException.class,()->trust.verify(changed,signature));
        }
    }
}
