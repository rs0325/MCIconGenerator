# ADR 0001: 対象版のGUI rendererを別JVMで使う

状態: experimentalとして採用。verifiedへの昇格は保留。

独自モデル描画は、1.21.4以降のitem definitions、特殊モデル、旧版のoverride、色付けを再実装する必要がある。対象版のバニラGUI rendererをJava agentからrender thread上で呼ぶ方法を採用した。ディスク上のゲームJARは改変・配布せず、メモリ上のcallbackと固定contextの呼び出し変換を挿入する。

1.13ではItem/Block自身のREGISTRY、1.13.1/1.13.2では統合Registryを使う。Ornithe Feather build.31とCalamus intermediary gen1のTinyを公式→intermediary→namedへ合成する。JARのURLとSHA-1を固定する。1.14〜1.14.3はFeather build.9とintermediary gen1を同様に固定する。1.14.4以降は公式ProGuard mappingsを使用し、名前付き26.xでは直接参照する。

固定機能OpenGL、PoseStack/JOMLの即時GUI、1.21.5のBlaze3D GPU、1.21.6以降のdeferred GUI、26.2の再設計されたBlaze3D、26.3のRenderPearlを別adapterとする。exact versionごとのprofileはRuntimePreparerで固定し、未登録版を範囲推測しない。1.17のJava16最低要件はimage内Java17 LTSで満たす。26.xの既定componentsはバニラのVanillaRegistries/DATA_COMPONENT_INITIALIZERSで構築する。ユーザーのdata packは読み込まない。

Linux amd64のDocker、Java 8/17/21/25、Xvfb、Mesa llvmpipeでホストARMとの互換性を扱う。26.3はSDL3のGLX初期化が失敗したが、SDL_VIDEO_FORCE_EGL=1で起動できた。[SDLのEGL切替処理](https://github.com/libsdl-org/SDL/blob/main/src/video/x11/SDL_x11opengl.c)を参考にした。旧版のGLFWでは通常のGLXを使う。

rendererを呼べた事実だけでは画質・再現性の保証にならない。時刻・暦日・動的property乱数・初期atlasを固定し、黒白背景の2passからalphaを復元する。deferred GUIでは各passのアトラスの割当だけを初期化し、GPU textureを再利用してslot位置を固定する。読み戻しのfence完了後にGUI frameと動的uniform storageを終了・resetする。26.2/26.3ではcommand encoderのsubmitを明示する。16リクエストごとに通常のバニラフレームへ戻り、presentとGPUの遅延解放を進める。色空間、半透明blend、missing model、時計/コンパス・特殊モデルの基準画像、独立起動のRGBA比較の受入は引き続き版ごとに記録する。host nativeのWindows/macOS/LinuxやDocker ARM64 imageは公表対応にしない。

GitHub Actionsは利用せず、利用者の指定によりローカルDockerで検証する。ベースJDK imageのdigestを固定したが、aptのMesaパッケージはbuild日時によって変わる。固定環境の比較には実際に使用したローカルimageとrendererを保存する。

## 描画外の時計と終了処理

1.13.2はGLFW初期化後にUtilsの時計供給元をGLFW経過時刻へ切り替える。通常フレームの秒単位の追従処理は、この基準と初期値0を前提とする。描画外までSystem.nanoTimeへ置換すると反復が増え、PNG生成後にshutdownを送っても終了待機を超過する。時計呼び出しの結果を受け取り、描画中だけfixed-v1の固定値を選ぶ。終了待機の延長やゲーム終了処理の迂回は追加しない。
