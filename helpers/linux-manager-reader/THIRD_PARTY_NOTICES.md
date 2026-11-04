# 依存と配布境界

reader sourceはリポジトリのMIT licenseに従う。runtimeのlibc/libsystemd/libcryptoはUbuntuのroot管理packageへ動的依存し、DEBへ同梱しない。各system packageのcopyright/licenseと公式sourceをその配布元で保持する。

- systemd 255.4 / libsystemd：LGPL-2.1-or-later等。Ubuntu source `.dsc`、orig、debian patch由来と、実loader closureを固定する。
- OpenSSL 3.0：Apache-2.0。SHA256は設定自動loadを無効化したlibcrypto APIで行う。
- crossbuildにZig/LLVM libc++/compiler-rtを用いる場合、compiler入力のlicenseとELFに含まれるruntime licenseをbuild時に固定して配布へ添える。toolchain source/version/hash不明の成果は配布候補にしない。

私用toolchain、Linux SDK、Ubuntu/ゲーム資産、秘密情報をこのpackageへ同梱しない。source/対応license不足はbuild/配布の停止条件であり、正式署名や公開済みの宣言をしない。
