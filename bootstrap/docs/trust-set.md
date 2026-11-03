# 運用鍵・回復鍵の信頼集合

## 形式と署名

信頼根は同じ所有者が管理するEd25519公開鍵2件（運用鍵・回復鍵）の集合である。既存JCAのEd25519と既存sharedソースを再利用し、外部依存は追加しない。固定2件なので鍵ID付き署名や可変件数の鍵管理は導入せず、64byteの署名を各鍵で検証して一方が成功すれば受理する。両方で失敗、署名の長さ違い・改ざん・無関係な鍵は拒否する。署名対象はmanifest/descriptorの元bytesのまま。

配布ファイル名は`trust-set.bin`、形式は固定96bytesである。

| 範囲（0始まり） | 内容 |
| --- | --- |
| 0–7 | ASCII `MCITRS1\n`（末尾LF、形式version 1） |
| 8–51 | 正規Ed25519 SubjectPublicKeyInfo DER 44bytes |
| 52–95 | 正規Ed25519 SubjectPublicKeyInfo DER 44bytes |

DERは12byteの固定prefix `302a300506032b6570032100` と32byteの公開鍵。DER全体の符号なし辞書順で厳密な昇順とする。大小関係が等しい重複、逆順、件数違い、余剰bytes、不正DER、未知versionを拒否する。運用・回復という役割や鍵IDはファイルに保存しない。入力時の役割を入れ替えても同じbytesになる。秘密鍵は受け取らない。旧`trust.der`の単一鍵DERは新しいファイル名へ改名しても拒否する。

Javaの読込みでは両鍵を`KeyFactory`で生成し、`Signature.initVerify`まで成立することを要求する。曲線上にない公開点を含む集合は全体を拒否する。検証時は鍵ごとに検証例外を扱い、他方が成功すれば受理する。Pythonの公開DER入力・包装入口でも[RFC 8032 §5.1.3](https://www.rfc-editor.org/rfc/rfc8032.html#section-5.1.3)の公開点復号条件を検査する。既存の標準PythonにはEd25519公開点検査APIがないため、標準整数演算による点の成立条件だけを追加し、署名検証は既存JCAを再利用する。

## core・seed・packageの結合

coreのpins schema 1と`trustKeyPath`というfield名、runtime descriptor schema 1、Minecraft worker/helper protocol 1、ManagedOptions等の公開APIは維持する。`trustKeyPath`の参照先だけを新形式に置き換え、resource生成時とmanaged起動時に形式と署名を検証する。発行入力の全hostに同一集合を要求する。

package verifierは全hostのruntime信頼集合とpackage信頼集合の96bytes完全一致を要求する。正規順で唯一の表現になるため、順序差を吸収する比較や識別子は不要である。seed manifestとruntime descriptorはそれぞれ2鍵のいずれかで検証する。同じ鍵で署名されている必要はない。ただし同梱core resourceとseed内descriptor/.sigは従来どおり元bytes完全一致、payload coreは現在buildしたJAR全体のsize/hash一致を要求する。

Mac・Windows packagerの`--trust`とGradleの`bootstrapTrustKey`は新形式のfileを受け取る。出力metadataのschemaVersionは1、bootstrapProtocolは4。Windows installerもprotocol 4と新trust fileを検査し、前段image/ZIPのbytes結合を維持する。Windows security reader独自のschema 3は変更しない。

## bootstrap・保存状態

bootstrapとCLIの制御protocolは4、stable manifest schema 1の`minimumBootstrapProtocol`は4を要求する。旧protocol 2/3を受け付けない。active stateは[bootstrap-state-v4.schema.json](../../schemas/bootstrap-state-v4.schema.json)とし、`trustSetSha256`に正規信頼集合96bytesのSHA-256を保存する。起動時に現在のpackage信頼集合の指紋と照合する。鍵集合が違うstateを再利用せず、既存stateを消失扱いで再作成しない。

command receiptの外形はschema 3、FAILED証拠内の制御protocolは4とする。preparation receipt/configはschema 1、slot referenceのfieldは維持する。旧state schema 2/3は明確に拒否し、移行・互換fallbackを実装しない。履歴schemaは残すが現行validatorには採用しない。旧QA rootはreceipt/slotを必要に応じて保全し、別のfresh rootで再生成・検証する。

## 公開鍵の差し込み

ユーザーから提供された公開DER2件だけで次を実行する。本番鍵が未提供でも、JVM内の一時KeyPairによるテストと合成公開bytesによる包装テストを実行できる。

```sh
python3 bootstrap/scripts/trust_set.py \
  --operational-key /absolute/operational-public.der \
  --recovery-key /absolute/recovery-public.der \
  --output /absolute/trust-set.bin
```

両方に同じ鍵を指定すると失敗し、既存出力も上書きしない。同じ`trust-set.bin`をruntime発行入力の`trustKeyPath`へ配置し、packagerの`--trust`または`-PbootstrapTrustKey`にも指定する。生成順序はartifact/inventory → descriptor/署名 → pins/resources → core JAR → payload ZIP → stable manifest/署名。実鍵の生成・探索・秘密鍵の読込みはこのツールの責務外である。

## 紛失・漏洩時

通常のリリースは運用鍵で署名し、運用鍵を紛失した場合は回復鍵で署名できる。署名はリリース作業時のみ行い、秘密鍵をCI/クラウドへ置かない。鍵集合の変更は新しいライブラリ・bootstrap packageに同梱して配布し、対応するcore resources、descriptor/seedとfresh stateを再生成する。manifest内の鍵で信頼根を自動差し替えしない。

いずれかの秘密鍵が漏洩した場合、その公開鍵を含む旧配布物は漏洩鍵の署名を受理し続ける。回復鍵による署名だけでは旧鍵を失効できない。漏洩鍵を除外した新しい2鍵集合を持つライブラリ・bootstrapへ信頼済みの配布経路で置き換える。回復鍵自体の漏洩・紛失も同じく新版の鍵集合差し替えで対応する。
