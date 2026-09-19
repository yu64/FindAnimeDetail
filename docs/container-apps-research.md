# Azure Container Apps の無料枠での運用調査

調査日: 2026-09-17

## 結論

**デプロイ先は Azure Container Apps（ACA）の Consumption とする。** Java / Quarkus を維持し、各利用者が少人数向けに個別デプロイする。関連サービスも無料の構成にし、利用量を無料枠に収めて料金ゼロでの運用を目指す。

**無料枠超過時は従量課金される。** 無料枠は同じサブスクリプション内のアプリで共有する。今回のアプリを配置した後も、クォータの範囲内で別のアプリを追加できる。[料金表](https://azure.microsoft.com/en-us/pricing/details/container-apps/)、[クォータ](https://learn.microsoft.com/en-us/azure/container-apps/quotas)

## 今回の構成

| 項目 | 選ぶ構成 | 目的 |
| --- | --- | --- |
| 実行環境 | Container Apps の Consumption workload profile | 毎月の無料枠を利用する |
| アプリ | Java 25 / Quarkus の Linux コンテナ | 既存の検索・LINE 返信処理を維持する |
| スケール | `minReplicas: 0`、当初は `maxReplicas: 1`、単一 revision | アクセスがないときに停止し、実行量を抑える |
| イメージ保存 | 公開 GitHub Container Registry（GHCR） | 公開パッケージの無料利用を使う |
| Storage | コンテナの一時領域のみ | 現在のアプリは永続データ保存を必要としないため、Azure Storage Account の作成を省ける |
| HTTPS | Container Apps が発行する標準 FQDN と HTTP ingress | 独自ドメインの購入を省き、Webhook を HTTPS で公開する |
| ログ | 保存を無効にし、必要時にライブのログストリームを確認 | Log Analytics 等の保存費用を避ける |

Container Apps は Linux コンテナと外部レジストリを利用できる。上の Storage 構成は、永続ボリュームが任意であることと、このアプリの構造に基づく設計判断。[コンテナ仕様](https://learn.microsoft.com/en-us/azure/container-apps/containers)、[一時ストレージ](https://learn.microsoft.com/en-us/azure/container-apps/storage-mounts)、[公開 GHCR の料金](https://docs.github.com/en/billing/concepts/product-billing/github-packages)、[HTTPS ingress](https://learn.microsoft.com/en-us/azure/container-apps/ingress-overview)、[ログ保存の無効化](https://learn.microsoft.com/en-us/azure/container-apps/log-options)

## 無料枠と見積もり

Consumption の毎月の無料枠は、サブスクリプション単位で次の合計となる。

| 項目 | 無料枠 |
| --- | --- |
| CPU | 180,000 vCPU秒 |
| メモリ | 360,000 GiB秒 |
| リクエスト | 200万回 |

例えば 0.25 vCPU / 0.5 GiB の1レプリカなら、CPU・メモリの無料枠はどちらも月200時間分の割当量に相当する。これは計算例であり、このメモリ量でアプリが動くことや、200時間分のリクエスト処理を保証するものではない。起動時間や、処理終了からゼロへ縮退するまでの稼働も考慮する。アプリに必要なメモリは実測して決める。[料金表](https://azure.microsoft.com/en-us/pricing/details/container-apps/)、[スケール動作](https://learn.microsoft.com/en-us/azure/container-apps/scale-app)

外向き通信も無料範囲内に収める。日本を含む対象地域ではインターネット送信に最初の月100 GBの無料枠がある。少人数であっても、同じ契約の他リソースの利用分と併せて確認する。[通信料金](https://azure.microsoft.com/en-us/pricing/details/bandwidth/)

> **補足：追加費用を避ける設定**  
> Azure Container Registry、永続ボリューム、有料のログ保存先を作らず、基本の Consumption 構成にする。Dedicated profile、Private Endpoint、NAT Gateway 等の追加機能は今回の構成に含めない。初期構築ツールが自動作成するリソースも Bicep で明示的に管理する。[Container Apps の課金](https://learn.microsoft.com/en-us/azure/container-apps/billing)

> **補足：課金の上限**  
> `maxReplicas: 1` は同時稼働数を抑える設定。1レプリカでも長時間稼働すれば無料枠を超える。Azure の予算アラートは通知機能で、到達時に利用を停止する機能はない。[予算の動作](https://learn.microsoft.com/en-us/azure/cost-management-billing/costs/tutorial-acm-create-budgets)

## Bicep とリポジトリ内の配置

Bicep で ACA の実行環境とアプリを作成・更新できる。`container/aca-deploy/main.bicep` に次をまとめる。

| リソース | Bicep で管理する内容 |
| --- | --- |
| `Microsoft.App/managedEnvironments` | Consumption の実行環境、ログ保存設定 |
| `Microsoft.App/containerApps` | イメージ、CPU・メモリ、HTTPS ingress、環境変数・secrets、レプリカ数 |

公式仕様: [Container Apps](https://learn.microsoft.com/en-us/azure/templates/microsoft.app/containerapps)、[Managed Environments](https://learn.microsoft.com/en-us/azure/templates/microsoft.app/managedenvironments)。

```text
container/
  dev/
    Dockerfile
    compose.yaml
    entrypoint.sh
  aca-deploy/
    main.bicep
    Dockerfile
.github/workflows/
  deploy.yml
```

## デプロイ手順

- `container/aca-deploy/main.bicep` に Container Apps Environment と Container App を定義する。
- `container/aca-deploy/Dockerfile` で Quarkus の HTTP サーバーを直接起動する。Functions 用 `host.json` と `function.json` は配布物から外せる。
- `.github/workflows/deploy.yml` は手動実行を維持し、テスト → イメージのビルド・GHCR 公開 → OIDC ログイン → Bicep 適用 → HTTP 確認とする。
- LINE・Annict の値は GitHub Secrets から Container Apps の secrets / 環境変数へ渡す。公開イメージには秘密値を含めない。

## 実装後の確認

Quarkus Native を既定とし、必要なら JVM に切り替えられる構成にする。まず [README の開発用 Docker](../README.md) で起動を確認する。ACA 向けの `container/aca-deploy/Dockerfile` は、開発用の `container/dev/Dockerfile` と分けて実装する。

ゼロレプリカからの初回起動で、LINE の Webhook を受信し、Annict 検索と返信が間に合うことを確認する。実際の必要メモリ・稼働時間・通信量を確認して無料枠内に収まるか判断する。ACA への実デプロイと料金実測は未実施。
