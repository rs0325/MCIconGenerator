package io.github.rs0325.mcicon.internal;

import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.util.Arrays;

/** テスト専用の公開鍵集合。秘密鍵を保存しない。 */
public final class TrustFixtures {
    private TrustFixtures() {}
    public static byte[] bytes(PublicKey first,PublicKey second) {
        byte[] a=first.getEncoded(),b=second.getEncoded();
        if(Arrays.compareUnsigned(a,b)>0){byte[] swap=a;a=b;b=swap;}
        return ByteBuffer.allocate(96).put(new byte[]{'M','C','I','T','R','S','1','\n'}).put(a).put(b).array();
    }
    public static TrustSet trust(PublicKey first,PublicKey second) throws java.io.IOException {return TrustSet.parse(bytes(first,second));}
}
