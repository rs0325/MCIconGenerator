import io.github.rs0325.mcicon.CancellationToken;
import io.github.rs0325.mcicon.ExtractRequest;
import io.github.rs0325.mcicon.MCIconGenerator;
import io.github.rs0325.mcicon.ProgressListener;
import io.github.rs0325.mcicon.RenderRequest;
import io.github.rs0325.mcicon.ResourceId;
import io.github.rs0325.mcicon.WorkerOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 準備済みcacheから元PNGとアイコンを取得する利用例。引数は利用ガイドを参照。 */
public final class ExportExample {
    public static void main(String[] args) {
        var worker = new WorkerOptions(Path.of(args[2]), args[3], args[4],
                Duration.ofSeconds(180), Duration.ofSeconds(30));
        try (var generator = new MCIconGenerator(Path.of(args[0]), true,
                ProgressListener.NONE, new CancellationToken())) {
            var raw = generator.extract(new ExtractRequest("1.20.6",
                    List.of(ResourceId.parse("item/diamond")), false,
                    Path.of(args[1]), false, false)).join();
            var icons = generator.render(new RenderRequest("1.20.6",
                    List.of(ResourceId.parse("stone"), ResourceId.parse("player_head")),
                    false, "all", 64, Path.of(args[1]), false, false, false), worker).join();
            if (!raw.complete() || !icons.complete()) {
                throw new IllegalStateException("取得に失敗しました。manifestの診断を確認してください");
            }
            System.out.println(raw.manifest());
            System.out.println(icons.manifest());
        }
    }
}
