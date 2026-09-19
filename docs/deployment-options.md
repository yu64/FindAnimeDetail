# 無料運用に向けたデプロイ先の再検討

調査日: 2026-09-17

## 前提

- OSS として公開するのはコード。各利用者が自分のクラウドアカウントへデプロイする。
- 一つのデプロイを使う人数は少ない。大人数向けの共通サービスは構築しない。
- Java / Quarkus を維持する。
- 試用期間のクレジットに依存せず、必要な関連リソースも含めて無料枠内で継続利用できる構成を目指す。
- GitHub Actions の手動実行でデプロイする。配布設定はリポジトリの `container/aca-deploy/` にまとめる。

## 結論

**デプロイ先は Azure Container Apps の Consumption とする。** Java / Quarkus を維持し、Bicep と手動実行の GitHub Actions で配布する。無料枠内で料金ゼロを目指す方針とし、超過時は従量課金となる。[採用する構成とデプロイ方針](container-apps-research.md)

以下は選定時の比較記録。OCI は代替候補として残す。実デプロイは未検証。

## 候補の比較

| 候補 | 無料で使う条件 | Java / Quarkus の動かし方 | 今回の判断 |
| --- | --- | --- | --- |
| Azure Container Apps | Consumption の無料枠内に収め、公開 GHCR・標準 HTTPS・ログ保存なしで構成する。超過時は課金 | Linux コンテナで Quarkus を直接起動 | 採用方針。Bicep・手動 Actions で構築する |
| OCI Always Free | 対象の VM・ディスク・通信量などを無料枠内に収める。有料アカウントへアップグレードせず運用する | Linux VM 上で JVM アプリまたはコンテナを起動 | 代替候補。少人数向けでも低利用 VM の回収条件を確認する |
| Render Free | Free Web Service を使用。支払い方法を登録しない運用では、転送量等の上限到達時にサービスやビルドが停止する | Docker で既存アプリを配布 | 導入は簡単だが、休止後の LINE 返信を検証する必要がある |
| Koyeb Free Instance | 無料インスタンスの範囲に収め、アカウントの契約・超過料金を別途確認する | コンテナで既存アプリを配布 | 優先度を下げる。公式 FAQ にカード必須・登録時の選択プラン課金の説明がある |

根拠: [OCI 無料リソース](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[Render Free](https://render.com/docs/free)、[Render Docker](https://render.com/docs/docker)、[Koyeb Free Instance](https://www.koyeb.com/docs/reference/instances#free-instances)、[Koyeb 料金 FAQ](https://www.koyeb.com/docs/faqs/pricing)。

### OCI を選ぶ場合

無料 VM に Quarkus アプリを常駐させ、HTTPS 経由で `/api/line/webhook` を公開する。最初は Java 25 の JVM で動かす案とし、既存の検索・コマンド解析・LINE 返信処理を維持する。ビルドは Actions 側で行い、VM は実行に使う。

無料枠には起動ディスクを含むストレージもある。利用者は Always Free 対象のリソース・リージョンを選ぶ。登録時にはカードによる本人確認が必要で、一時的な与信枠確保が行われる。[無料リソースの条件](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[登録条件](https://www.oracle.com/cloud/free/faq/)

> **補足：少人数利用と VM 回収**  
> 無料 VM は空き容量不足で作成できない場合があり、低利用の VM は回収対象になることがある。少人数向けでも、この条件は残る。停止・再作成が必要になった場合に備えて、構成と再デプロイ手順をコードで再現できるようにする。[OCI の容量・回収条件](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

### Render を選ぶ場合

無料の HTTPS 公開と Docker 配布を利用でき、VM の管理を減らせる。無料 Web Service は15分間アクセスがないと休止し、次のアクセス時の起動には約1分かかる。少人数利用ではこの休止に入りやすい。[Render Free](https://render.com/docs/free)

現状の `LineController` は検索と LINE 返信の処理が完了してから HTTP 応答を返す。休止後の初回アクセスでは起動待ちが加わるため、Webhook の受信・返信に失敗する可能性がある、というのが今回の判断。実際の LINE イベントで検証するまでは、通常運用の第一候補にはしない。

> **補足：LINE の返信時間**  
> LINE は返信トークンを Webhook 受信後1分以内、できるだけ早く使うよう案内している。起動待ちに加えて外部 API の検索時間も考慮する必要がある。[LINE 返信 API](https://developers.line.biz/en/reference/messaging-api/#send-reply-message)

## デプロイ設計への反映

- Azure Functions 向け Bicep と配布用 `host.json` の実装は見送る。Functions 案は [過去の調査](azure-deployment-research.md) として残す。Container Apps を採用する場合は、そのリソースを Bicep で定義する。
- OCI / Render では Quarkus の HTTP サーバーを直接起動する構成にする。今回読んだアプリ本体には Azure Storage の直接利用が見当たらず、Azure 用の起動設定を外して移す方針を取れる。
- `.github/workflows/deploy.yml` の手動実行と、`container/aca-deploy/` に配布設定をまとめる方針を維持する。
- LINE・Annict の秘密値をデプロイ先の環境変数へ渡す。公開するソースや配布イメージに埋め込まない。

## OCI 採用前に確認すること

1. 利用者のアカウントで Always Free 対象 VM を確保できること。
2. VM、起動ディスク、IP・通信、公開 URL・TLS を含む構成に必須の有料サービスがないこと。無料で使えるホスト名・証明書の具体的な構成は未検証。
3. Java 25 / Quarkus が VM の CPU・メモリ内で動作し、LINE 署名検証・Annict 検索・返信が成功すること。
4. VM 再起動後の自動起動と、Actions からの手動再デプロイができること。

> **補足：無料という条件の範囲**  
> ここでの無料は、現在提供されている無料プラン・無料枠の条件内で利用料金をゼロに収める意味。無料枠の変更や、休止・回収を含む運用上の制限は別途ある。少人数という前提だけで、料金と可用性の両方を保証する扱いにはしない。
