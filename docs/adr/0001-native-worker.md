# ADR 0001: 対象版のGUI rendererを別JVMで使う

状態: experimentalとして採用。verifiedへの昇格は保留。

独自モデル描画は、1.21.4以降のitem definitions、特殊モデル、旧版のoverride、色付けを再実装する必要がある。対象版のバニラGUI rendererをJava agentからrender thread上で呼ぶ方法を採用した。ディスク上のゲームJARは改変・配布せず、メモリ上のcallbackだけを挿入する。

1.13ではItem/Block自身のREGISTRY、1.13.1/1.13.2では統合Registryを使う。Ornithe Feather build.31とCalamus intermediary gen1のTinyを公式→intermediary→namedへ合成する。JARのURLとSHA-1を固定する。1.21.4は公式ProGuard mappings、26.3は名前付きクラスを直接参照する。

1.13の固定機能OpenGL、1.21.4のGuiGraphics、26.3のGuiGraphicsExtractor/RenderPearlは別adapterとする。26.3の既定componentsはバニラのVanillaRegistries/DATA_COMPONENT_INITIALIZERSで構築する。ユーザーのdata packは読み込まない。

Linux amd64のDocker、Java 8/17/21/25、Xvfb、Mesa llvmpipeでホストARMとの互換性を扱う。26.3はSDL3のGLX初期化が失敗したが、SDL_VIDEO_FORCE_EGL=1で起動できた。[SDLのEGL切替処理](https://github.com/libsdl-org/SDL/blob/main/src/video/x11/SDL_x11opengl.c)を参考にした。旧版のGLFWでは通常のGLXを使う。

rendererを呼べた事実だけでは画質・再現性の保証にならない。色空間、半透明blend、missing model、時計/コンパス、季節チェスト、glint、アニメーション固定の検証が残る。host nativeのWindows/macOS/LinuxやDocker ARM64 imageは公表対応にしない。

GitHub Actionsは利用せず、利用者の指定によりローカルDockerで検証する。ベースJDK imageのdigestを固定したが、aptのMesaパッケージはbuild日時によって変わる。固定環境の比較には実際に使用したローカルimageとrendererを保存する。
