# Azure Functions への手動デプロイ調査

調査日: 2026-09-17

> **状態：採用見送り**  
> 必須の Azure Storage が別途課金対象となるため、Functions 案は採用を見送る。デプロイ先は [Azure Container Apps](container-apps-research.md) に変更し、Bicep と手動実行の GitHub Actions を使って無料枠内での運用を目指す。以下は Azure Functions を候補としていた時点の設計記録。

## 結論と前提

**Bicep・GitHub Actions・GitHub Secrets を組み合わせて、Azure Functions への手動デプロイフローを構築できる。**

デプロイ先は **Azure Functions / Flex Consumption / Linux** とし、アプリの実行方式は既存の Custom Handler を使う。GitHub の「Run workflow」で開始し、その後のビルド・Azure 環境の更新・アプリ配布を Actions に任せる。起動イベントは `workflow_dispatch` のみとする。

調査で方式の実現性を確認した段階。次の作業は Bicep と workflow の実装、および実際の Azure Functions での動作確認となる。

> **補足：Custom Handler の対応**  
> Flex Consumption の公式対応表には Custom handlers 1.0 が掲載されている。このアプリの Linux 用実行ファイルは、初回デプロイで起動と HTTP 応答を確認する。[Flex Consumption の公式案内](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-plan#supported-language-stack-versions)

## 無料枠でも Azure Storage は必要か

**必要。Functions の実行が毎月の無料枠に収まっていても、Storage Account を用意する。** このアプリが検索結果を保存しなくても、Functions 自体がホストの管理情報や配布ファイルの保存に利用する。[Storage の要件](https://learn.microsoft.com/en-us/azure/azure-functions/storage-considerations)

Flex Consumption では配布 ZIP を Blob Storage に保存する。ホスト用と配布用には同じ Storage Account を使用できるため、今回の構成では一つにまとめられる。[Flex の配布方式](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-plan#deployment)

**Functions の無料枠で実行料金を抑えられるが、Storage の保存容量・操作などは別途課金対象となる。** そのため、この構成全体の永続的な料金ゼロは保証できない。[公式料金表](https://azure.microsoft.com/en-us/pricing/details/functions/)

> **補足：今回使う Flex Consumption の無料枠**  
> 従量課金サブスクリプションの On Demand では、サブスクリプション内の対象アプリ合計で毎月25万実行・100,000 GB-s が無料枠となる。Always Ready は無料枠の対象外なので、無料枠を利用する構成では On Demand を使う。これは調査時点の毎月の無料割当。[公式料金表](https://azure.microsoft.com/en-us/pricing/details/functions/)

### Azure Storage を外部の無料ストレージに置き換えられるか

**今回の Flex Consumption では、Azure Storage 全体を外部ストレージに置き換える公式対応の構成はない。** ホスト用の `AzureWebJobsStorage` は Azure Storage を使用し、配布先の設定も Azure Blob Storage の `blobContainer` が対応対象となる。S3・Cloudflare R2・GitHub Releases を指定して Storage の課金をなくす方式は採用できない。[ホスト用 Storage の要件](https://learn.microsoft.com/en-us/azure/azure-functions/storage-considerations)、[配布先の設定仕様](https://learn.microsoft.com/en-us/azure/templates/microsoft.web/sites#functionsdeploymentstorage)

アプリ自身が保存するデータには外部サービスを選べるが、Functions の実行基盤用 Storage は引き続き必要。そのため、外部ストレージへの変更で完全無料にする案は成立しない。

> **補足：Azure Files と Azurite**  
> Flex Consumption は Azure Files の共有領域を使わず、配布 ZIP を Blob Storage に置く。ローカル開発で使用する Azurite は開発・テスト向けのエミュレーターであり、今回のクラウド配布先の代替にはならない。[Azure Files の省略](https://learn.microsoft.com/en-us/azure/azure-functions/storage-considerations#create-an-app-without-azure-files)、[Azurite の用途](https://learn.microsoft.com/ja-jp/azure/storage/common/storage-use-azurite)

## 三つの役割

| 技術 | 担当する処理 | このプロジェクトでの用途 |
| --- | --- | --- |
| Bicep | Azure リソースの作成・更新 | Function App、プラン、Storage、アプリ設定を定義する |
| GitHub Actions | デプロイ手順の実行 | 手動実行を受けて、テスト・ビルド・Bicep 適用・アプリ配布を順に実行する |
| GitHub Secrets | 認証情報の保管 | LINE・Annict の秘密値を保管し、デプロイ時に Azure のアプリ設定へ渡す |

Bicep で実行環境を整え、`Azure/functions-action` でビルド済みアプリを配布する。この二つを一つの workflow にまとめる。[Bicep と Actions](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/deploy-github-actions)、[Functions Action](https://github.com/Azure/functions-action)

> **補足：Azure へのログイン**  
> Actions から Azure への認証には OIDC を使う。初回に GitHub リポジトリと Azure の信頼関係を設定すると、実行ごとに一時的な認証情報でログインできる。LINE・Annict の秘密値は GitHub Secrets で管理する。[Azure 向け OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-azure)

## 手動実行後の流れ

1. GitHub の **Actions → デプロイ用 workflow → Run workflow** を押す。
2. Actions の Linux runner で Gradle のテストを実行し、Linux 用のネイティブ実行ファイルをビルドする。
3. 実行ファイルと Functions の設定ファイルを ZIP にまとめる。
4. OIDC で Azure にログインする。
5. Bicep を適用し、Azure リソースとアプリ設定を作成・更新する。このとき Secrets の値も反映する。
6. `Azure/functions-action` で ZIP を Function App に配布する。
7. `GET /api/sample` の応答を確認し、デプロイ結果を表示する。

テスト・ビルド・デプロイでエラーが発生したら、後続の処理を停止して失敗を表示する。

> **補足：手動実行の設定**  
> `.github/workflows/deploy.yml` に `on: workflow_dispatch` を設定する。GitHub 画面で実行できるようにするには、workflow ファイルをリポジトリのデフォルトブランチに配置する。[手動実行の公式手順](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

## 初回に必要な設定

| 設定場所 | 用意するもの | 目的 |
| --- | --- | --- |
| Azure | デプロイ先の subscription、resource group、Function App 名、リージョン | Flex Consumption 対応リージョンでリソースを作成・更新する場所を決める |
| Azure | デプロイ用 ID と GitHub 向け OIDC の信頼設定 | Actions から Azure にログインする |
| Azure | デプロイ用 ID に対象 resource group の更新権限 | Bicep の適用とアプリ配布を許可する |
| GitHub | 下記の Secrets / Variables | workflow から設定値を参照する |

Azure のデプロイ用 ID・信頼設定・権限は、管理権限を持つ利用者が初回に用意する。以後は「Run workflow」で同じ設定を利用できる。

### GitHub Secrets

リポジトリの **Settings → Secrets and variables → Actions** に登録する。

| 名前 | 用途 |
| --- | --- |
| `LINE_BOT_CHANNEL_SECRET` | LINE Webhook の署名検証 |
| `LINE_BOT_CHANNEL_TOKEN` | LINE API の認証 |
| `ANNICT_API_TOKEN` | Annict API の認証 |

Actions がこれらの値を Bicep に渡し、Function App のアプリ設定へ同名で登録する。アプリは実行時に環境変数として読み取る。現在の `LineConfig` / `AnnictConfig` に対応した名前なので、この方式で既存コードに設定を渡せる。

> **補足：秘密値の渡し方**  
> Bicep の受け取り側には `@secure()` を付け、ARM のデプロイ履歴で値を保護する。workflow では値をログに出さず、一時パラメータファイルを使う場合は処理後に削除する。[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[Bicep の secure parameters](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/parameters#secure-parameters)

### GitHub Variables

同じ画面の Variables に登録する。

| 名前 | 用途 |
| --- | --- |
| `AZURE_CLIENT_ID` | デプロイ用 ID の識別子 |
| `AZURE_TENANT_ID` | Azure の認証先 tenant |
| `AZURE_SUBSCRIPTION_ID` | デプロイ先 subscription |
| `AZURE_RESOURCE_GROUP` | デプロイ先 resource group |
| `AZURE_FUNCTIONAPP_NAME` | Function App 名 |
| `AZURE_LOCATION` | デプロイ先リージョン |

> **補足：Secrets と Variables の区別**  
> トークンなどの秘密値は Secrets、リソース名や識別子は Variables に置く。Annict の URL と放送局優先順位は、現在のアプリの既定値を利用できる。

## 既存アプリで必要な対応

現在は Java 25 / Quarkus 3.37.2 を使い、`host.json` から Windows 用の `.exe` を起動する構成になっている。Flex Consumption への配布に向けて、次の対応を行う。

| 確認点 | 対応方法 |
| --- | --- |
| Azure Functions の構成 | Bicep に Flex Consumption と Custom Handler の設定、配布用 Blob コンテナを定義する |
| ビルド環境 | Java 25 / Quarkus 3.37.2 に対応する Linux 用 native-image ビルダーを用意し、Flex Consumption 上で依存ライブラリの互換性と起動を確認する |
| `host.json` の実行パス | 配布用設定は ZIP 直下の Linux 実行ファイル `runner` を指すようにし、作業ディレクトリも ZIP の配置に合わせる |
| 配布ファイル | Linux 実行ファイル `runner`、ZIP 直下の `host.json`、`Quarkus-Sample/function.json` を含める。`runner` の実行権限を保持する |
| アプリの認証情報 | ローカルの `local.settings.json` に代わり、GitHub Secrets から Azure のアプリ設定へ渡す |

この対応により、既存の Custom Handler 方式で Flex Consumption への配布を進められる。ローカルの Windows 用 `host.json` は開発用として維持し、Linux 向けの設定を `deploy/host.json` に用意する。[Custom Handler の配布要件](https://learn.microsoft.com/en-us/azure/azure-functions/functions-custom-handlers#deploying)

> **補足：Flex Consumption 用の設定**  
> Bicep では `functionAppConfig.runtime` にランタイム、`functionAppConfig.deployment` に配布用 Storage と認証方法を設定する。Actions ではビルド済み ZIP を渡し、Azure 側の再ビルドは無効にする。[Flex の設定項目](https://learn.microsoft.com/en-us/azure/azure-functions/functions-app-settings#flex-consumption-plan-deprecations)、[Functions Action](https://github.com/Azure/functions-action)

初回の動作確認では `/api/sample` の応答に加え、`/api/line/webhook` の署名検証と、実際の検索・LINE 返信まで確認する。

## リポジトリ内のファイル配置

デプロイ用の Bicep と `host.json` は `deploy/` にまとめ、Actions の workflow は `.github/workflows/` に置く。以下のパスはリポジトリのルートからの相対パス。

| ファイル | 内容 |
| --- | --- |
| `deploy/main.bicep` | Flex Consumption の Azure Functions と関連リソース、アプリ設定の定義 |
| `deploy/host.json` | Linux 用の実行ファイルと配布先の配置に合わせた起動設定 |
| `.github/workflows/deploy.yml` | 手動実行、テスト、ビルド、ZIP 作成、Azure ログイン、Bicep 適用、配布、応答確認 |

> **補足：配布 ZIP 内の配置**  
> Actions が `deploy/main.bicep` を Azure に適用し、`deploy/host.json` は配布 ZIP の直下へコピーする。ZIP には実行ファイルと Functions の設定ファイルをまとめる。リポジトリ直下の `host.json` はローカル開発用として維持する。

実装を始める際に確定する項目は、**既存の Flex Consumption の Function App を使うか、新規作成するか、およびデプロイ先の識別情報・リージョン**。これらに合わせて Bicep を具体化する。
