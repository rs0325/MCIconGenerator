package io.github.rs0325.mcicon;

import io.github.rs0325.mcicon.internal.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 公式入力を専用cacheへ準備し、元テクスチャとインベントリアイコンを取得する。
 * 一つのinstanceではジョブを順番に実行し、失敗は返したFutureへ通知する。
 * Minecraftのクラスや認証情報を公開APIへ渡さない。
 * キャンセル後は新しいinstanceとCancellationTokenを使用する。
 */
public final class MCIconGenerator implements AutoCloseable {
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mcicon-job").factory());
    private final AssetStore assets;
    private final VersionService versions;
    private final CancellationToken cancellation;
    private final ProgressListener progress;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private boolean closed;
    /**
     * @param cache 入力・準備結果と診断ログの保存先。ジョブ実行時に書き込む
     * @param offline 検証済みcacheだけを使用し、不足時にダウンロードせず失敗する
     * @param progress ジョブのスレッドで呼ぶ通知先。同じinstanceのFutureを通知内で待たない
     * @param cancellation このinstanceの全ジョブに適用する取消状態
     */
    public MCIconGenerator(Path cache,boolean offline,ProgressListener progress,CancellationToken cancellation) {
        this(cache,offline,progress,cancellation,null);
    }
    /**
     * 既存インストールの公式入力をhash検証してcacheへコピーする。元のファイルは変更しない。
     * @param cache 入力・準備結果と診断ログの保存先
     * @param offline cacheと既存インストールだけを使用し、不足時は失敗する
     * @param progress ジョブのスレッドで呼ぶ通知先
     * @param cancellation このinstanceの全ジョブに適用する取消状態
     * @param minecraftDirectory 既存インストールのディレクトリ。nullでは取り込まない。
     *                           versions・libraries・assetsの必要な公式入力だけを読み取る。cacheと同一・入れ子の場所は使用できない
     * @throws IllegalArgumentException 入力ディレクトリが存在しない、cacheと重なる、または解決できない場合
     */
    public MCIconGenerator(Path cache,boolean offline,ProgressListener progress,CancellationToken cancellation,Path minecraftDirectory) {
        this.assets=new AssetStore(cache,offline,cancellation,minecraftDirectory);this.versions=new VersionService(assets);this.cancellation=cancellation;this.progress=progress;
    }
    /** 公式metadataから1.13以降の正式版を新しい順に返す。未登録adapterの版も含む。 */
    public CompletableFuture<List<VersionInfo>> listVersions() {return submit(versions::list);}
    /** 指定版のadapterとローカルDocker image・Java・Mesaを確認する。ゲームの全件描画は行わない。 */
    public CompletableFuture<DiagnosticReport> doctor(String version,WorkerOptions options) {return submit(()->Diagnostics.doctor(versions,version,options,cancellation));}
    /** cacheの使用量を返す。verify=trueでは記録済みSHA-256がある入力を検査する。 */
    public CompletableFuture<CacheReport> cache(boolean verify) {return submit(()->Diagnostics.cache(assets.root(),verify,cancellation));}
    /** 入力を準備してゲームworkerを起動し、登録アイテム・ブロックの対応と除外理由を返す。 */
    public CompletableFuture<List<CatalogEntry>> listEntries(String version,WorkerOptions options) {
        return submit(()-> {
            var runtime=new RuntimePreparer(assets,versions,cancellation).prepare(versions.resolve(version));
            try(var worker=new WorkerClient(runtime,assets.root(),options,cancellation)){return worker.catalog();}
        });
    }
    /**
     * 既定ItemStackの透過PNGとmanifestを出力する。experimentalへの明示opt-inが必要。
     * 個別失敗や途中取消は未完了のExportResultへ記録し、準備・起動失敗はFutureを失敗させる。
     */
    public CompletableFuture<ExportResult> render(RenderRequest request,WorkerOptions options) {
        return submit(()-> {
            VersionInfo version=versions.resolve(request.version());
            if(!request.allowExperimental())throw new McIconException("UNVERIFIED_VERSION","Rendering adapters are experimental; opt in with --allow-experimental",2);
            progress.onProgress(new ProgressListener.ProgressEvent("prepare",version.id(),0,1));
            var runtime=new RuntimePreparer(assets,versions,cancellation).prepare(version);
            try(var worker=new WorkerClient(runtime,assets.root(),options,cancellation)) {
                return new IconExporter(cancellation,progress).export(runtime,worker,worker.catalog(),request);
            }
        });
    }
    /** 元JARのPNG・付随mcmetaを元のbytesで保存する。Dockerと描画workerは使用しない。 */
    public CompletableFuture<ExportResult> extract(ExtractRequest request) {
        return submit(()-> {
            VersionInfo version=versions.resolve(request.version());
            progress.onProgress(new ProgressListener.ProgressEvent("prepare",version.id(),0,1));
            Path client=versions.client(version);
            return new RawExporter(cancellation,progress).export(version.id(),client,request);
        });
    }
    private synchronized <T> CompletableFuture<T> submit(Callable<T> task) {
        if(closed)return CompletableFuture.failedFuture(new McIconException("GENERATOR_CLOSED","Generator is closed; create a new instance",1));
        CompletableFuture<T> future=new CompletableFuture<>();pending.add(future);
        future.whenComplete((result,error)->pending.remove(future));
        executor.execute(()-> {
            if(future.isDone())return;
            try {cancellation.check();future.complete(task.call());}
            catch(Throwable error) {
                future.completeExceptionally(error instanceof RuntimeException||error instanceof Error?error
                        :new McIconException("IO_FAILED","Input/output operation failed",1,error));
            }
        });
        return future;
    }
    /**
     * 実行中・待機中のFutureを取り消し、executorとworkerの終了を要求する。
     * 完了済み結果は保持する。以後のジョブはGENERATOR_CLOSEDで失敗する。
     */
    @Override public synchronized void close() {
        if(closed)return;
        closed=true;cancellation.cancel();
        pending.forEach(future->future.cancel(false));executor.shutdownNow();
    }
}
