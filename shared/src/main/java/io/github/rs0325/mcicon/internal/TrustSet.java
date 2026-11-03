package io.github.rs0325.mcicon.internal;

import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** 固定2鍵の正規化済み信頼集合。旧単一鍵DERは受け付けない。 */
public final class TrustSet {
    /** 形式識別子と公開鍵2件を含む固定byte数。 */
    public static final int SIZE=96;
    private static final byte[] MAGIC={'M','C','I','T','R','S','1','\n'};
    private static final byte[] PREFIX=HexFormat.of().parseHex("302a300506032b6570032100");
    private final List<PublicKey> keys;
    private TrustSet(List<PublicKey> keys) {this.keys=List.copyOf(keys);}

    /**
     * 固定識別子と正規Ed25519 DERから、昇順かつ重複のない2鍵を読み取る。
     * @param bytes 正規化済みの96byte信頼集合
     * @return 読み取った不変の信頼集合
     * @throws IOException 旧形式、件数・順序・DERが不正、または署名検証に使えない鍵を含む場合
     */
    public static TrustSet parse(byte[] bytes) throws IOException {
        if(bytes.length!=SIZE||!Arrays.equals(MAGIC,Arrays.copyOf(bytes,8)))throw new IOException("信頼集合の形式が不正です");
        byte[] first=Arrays.copyOfRange(bytes,8,52),second=Arrays.copyOfRange(bytes,52,96);
        if(Arrays.compareUnsigned(first,second)>=0)throw new IOException("信頼集合の鍵が重複または正規順ではありません");
        try {
            List<PublicKey> keys=new ArrayList<>();
            for(byte[] der:List.of(first,second)) {
                if(!Arrays.equals(PREFIX,Arrays.copyOf(der,PREFIX.length)))throw new IOException("Ed25519公開鍵DERが不正です");
                PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
                Signature.getInstance("Ed25519").initVerify(key);
                keys.add(key);
            }
            return new TrustSet(keys);
        }catch(GeneralSecurityException failure){throw new IOException("信頼集合を読み込めません",failure);}
    }

    /**
     * 元bytesの署名を2鍵のいずれかで検証する。
     * @param bytes 署名対象の元bytes
     * @param signature 64byteのEd25519署名
     * @throws IOException 署名が不正、両鍵で不一致、または検証不能の場合
     */
    public void verify(byte[] bytes,byte[] signature) throws IOException {
        if(signature.length!=64)throw new IOException("署名は64bytesが必要です");
        for(PublicKey key:keys) {
            try {
                Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(bytes);
                if(verifier.verify(signature))return;
            }catch(GeneralSecurityException failure) { /* 他方の鍵も検証する。 */ }
        }
        throw new IOException("署名が信頼集合のどの鍵とも一致しません");
    }
}
