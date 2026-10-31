# Manager reader IPC 1

このendpointはroot所有の`/run/mcicon-manager-reader.sock`だけに配置する。通常clientは非root、readerはsocket activationで起動する短命root process。kernelのSO_PEERCRED UIDから本人`user@UID.service`を導出する。requestにUID/PID/pathを指定する入口はない。peer PIDの生存/namespaceやFDの非移譲性は保証しない。

## Request

ASCIIのちょうど40byte：`MCIMR1 `、小文字hex32桁のnonce、LF。その後clientは送信方向をshutdownし、EOFを送る。任意追加field/byte、別version、短文、過大、EOFなしは拒否。request上限64byte、server全体の相対期限1500ms内で読み終える必要がある。

nonceはclientが要求ごとに生成する128bit識別子であり署名鍵ではない。認可はnonceではなくkernel UIDによる。socket FDを別processへ渡しても接続UIDは変更できず、元UIDのreadonly能力が委譲されるだけである。

## Response

UTF-8 JSON object 1件とLF、最大65536byte。clientはEOFまで同じ2秒以下の親期限で読み、重複key・余剰object・未知schema・nonce/自UID不一致・不完全応答を拒否する。

共通fieldは`schemaVersion:1`、`nonce`、`uid`、`complete`。成功時は次を返す。

- `bootId`: 前後一致したhost boot UUID。
- `unit`: Id/ActiveState/ControlGroup/InvocationID。対象はactiveな固定本人user unitだけ。
- `manager`: PID/startTicks/UID/executable/cgroupとuser namespace dev/inode。
- `files`: `run/control/manager/core/shared`各固定pathのdev/inode/size/uid/mode/mtimeNs/ctimeNs/SHA256。

managerのUid4値、executable inode、user namespace、cgroup、観測時点のmapsと固定core/sharedのdevice/inodeはreaderが照合済み。固定5file（core/sharedのpathを含む）・property/process/bootの前後identity一致は連続した非原子的観測であり、maps自体の前後一致、期間中不変、原子的snapshotは保証しない。生maps、資格情報、一般proc情報は返さない。

失敗時は`complete:false`と`error:{stage,message,errno}`。nonce未取得やUID認可前の拒否ではnonce空/uid=4294967295になり得る。これは成功応答ではない。元error/非0/部分frame/通信timeoutをcallerが別々に保持する。期限後や相手切断で失敗frameも送れない場合、clientはremote結果unknownを残し、別要求で成功へ読み替えない。

## Callerの責務

root管理のsocket型/所有者/安全祖先と、固定reader配布物・loader依存の信頼を確認する。ユーザーsocketや別pathへfallbackしない。返却fixed5のsize/SHAを承認済systemd component期待と照合し、boot/uid/namespace/前回manager recordと比較する。root応答という理由だけで観測値を期待へ採用しない。

各manager-before/reobserve/terminalで新しいnonceによるfresh要求を行う。preflight snapshotの後段代用は禁止。親操作endへclampした最大2秒から回収余白250msを先に引き、残不足なら要求/次操作を開始しない。既9秒releaseへの組込みと実時間成立は#96の別受入で、ここでは保証しない。
