package io.github.rs0325.mcicon;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
/**
 * 既定ItemStackからアイコンを生成する指定。任意NBT/componentsやoverlayは追加しない。
 * @param version 正式版のexact ID、または公式metadataのlatest
 * @param ids 個別取得するアイテムID。all=trueでは空にする
 * @param all air以外の登録アイテムを全件取得する
 * @param kind all/item/block。blockはアイテム形態を持つブロックだけを対象とする
 * @param size 正方形PNGの辺。16/32/64/128/256/512
 * @param output version/mode別ディレクトリを作る出力の起点
 * @param overwrite 今回選択した出力だけを置換する
 * @param failFast 最初の個別失敗で処理を止める
 * @param allowExperimental 未受入adapterの使用へ明示的に同意する
 */
public record RenderRequest(String version,List<ResourceId> ids,boolean all,String kind,int size,Path output,
                            boolean overwrite,boolean failFast,boolean allowExperimental) {
    /**
 * @throws IllegalArgumentException IDとallの併用・未指定、または無効なkind/size
     */
    public RenderRequest {
        ids=List.copyOf(ids);
        if(version==null||output==null||all==!ids.isEmpty())throw new IllegalArgumentException("Specify IDs or --all, a version and an output directory");
        if(!Set.of("item","block","all").contains(kind))throw new IllegalArgumentException("Invalid item kind");
        if(!Set.of(16,32,64,128,256,512).contains(size))throw new IllegalArgumentException("Size must be 16, 32, 64, 128, 256, or 512");
    }
}
