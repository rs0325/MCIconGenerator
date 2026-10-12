package io.github.rs0325.mcicon;

import java.nio.file.Path;
import java.util.List;

/**
 * 元テクスチャの取得指定。アイテムIDではなく、textures以下の拡張子なしパスを使う。
 * @param version 正式版のexact ID、または公式metadataのlatest
 * @param textures item/diamond等のテクスチャID。all=trueでは空にする
 * @param all textures/itemとtextures/blockのPNG・付随mcmetaを全件取得する
 * @param output version/mode別ディレクトリを作る出力の起点
 * @param overwrite 今回選択した出力だけを置換する
 * @param failFast 最初の個別失敗で処理を止める
 */
public record ExtractRequest(String version, List<ResourceId> textures, boolean all, Path output,
                             boolean overwrite, boolean failFast) {
    /**
 * @throws IllegalArgumentException texturesとallの併用・未指定、またはversion/outputの未指定
     */
    public ExtractRequest {
        textures = List.copyOf(textures);
        if (version == null || output == null || all == !textures.isEmpty())
            throw new IllegalArgumentException("Specify either textures or all, a version, and an output directory");
    }
}
