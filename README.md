# FindAnimeDetail

## アニメ検索

```text
/find #word 作品名
/find #word 作品名1, 作品名2 #season next
/find #word 作品名 #from 2027-01 #to 2027-04
/find #season 2027-winter #hit #plain
/find #format md #word 作品名
/find #word 作品名1, 作品名2 #miss
```

作品名や期間からアニメの初回放送を Annict で探し、「放送予定」「放送済み」「対象外」「期間外」「未登録」を区別して返します。期間は `#season`（`2027-winter`・`now`・`next`）か `#from`・`#to` で指定し、省略時は現在以降です。`#hit`・`#done`・`#skip`・`#out`・`#miss` で出力する状態を選び、`#plain` で状態・検索語の列を省けます。仕様は [docs/find-plan.md](docs/find-plan.md) を参照してください。

## EPG自動予約

```text
/reserve
（/find の結果を貼り付け、予約しない行を消す）
```

```text
/reserve
作品名
作品名
```

EDCB（Material WebUI）に作品ごとの EPG 自動予約を登録し、結果を TSV で返します。保存先は作品ごとのフォルダに分けます。仕様は [docs/reserve-plan.md](docs/reserve-plan.md) を参照してください。

## 開発

### 環境変数

ルート直下の `local.settings.example.json` をもとに `local.settings.json` を作成してください。

### 環境
- proto (moonrepo)を推奨。下記で必要なツールをセットアップ。

```project
proto use
```

### テスト

```powershell
./gradlew test    # テスト
./gradlew check   # テスト + 書式チェック（規約: docs/コード規約.md）
```

- 単体テスト: 各クラスを直接呼び出す。外部APIには接続しない。
- モックテスト（`LineWebhookMockTest`）: アプリを起動し、署名付きの LINE Webhook を送って返信までを確認する。LINE・Annict の API は WireMock（Dev Service）に置き換えるため、トークンやネットワークは不要。
  - 接続先とテスト用の秘密値は `application.properties` の `%test.` 設定にある。
  - ケースを足すときは、Annict の応答を `annictReturnsWorks` などで用意し、`sendText` で送って、`onlyReply` で返信を検証する。
  - Native 実行ファイルに対しても同じケースを実行できる（`src/native-test` の `LineWebhookMockIT`）。リフレクション設定の漏れなど、Native だけで起きる不具合を検出する。実行ファイルも `test` プロファイルで起動する（`quarkus.test.integration-test-profile`）。

Native のモックテストは Native ビルドが必要なため、Linux（コンテナ内など）で実行する。`testNative` は `build` / `check` には含まれないが、`container/aca-deploy/Dockerfile` の Native ビルドでは毎回実行する。

```bash
./gradlew testNative
```

依存ライブラリの取得で `PKIX path building failed` が出る場合は、ウイルス対策ソフトなどが HTTPS を検査している可能性がある。Windows の証明書ストアを使うよう指定して実行する。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
```

### 実行 - Container
#### 前提
- Docker (Podman)
- Docker Compose (Podman Compose)

#### 手順

- モード選択(native = GraalVM AOT)

```powershell
$env:DEV_MODE = 'native' # default
# $env:DEV_MODE = 'jvm'
```

- コンテナのビルトと実行

```powershell
docker compose -f container/dev/compose.yaml up --build
```

- エンドポイント

```text
http://localhost:8080/api/{path}
```


### 実行 - Quarkus (JVM)
#### 前提
- proto (moonrepo)

#### 手順

- Quarkus実行

```powershell
./gradlew quarkusDev
```

- エンドポイント

```
http://localhost:8080/api/{path}
```


### 実行 - Local Azure Function (Native)
#### 前提
- Windows11
- proto (moonrepo)
- Visual Stadio Build Tools 2026 (C++)

#### 手順

- Azure Storageエミュレータ起動

```
azurite --location . --debug azurite-debug.log
```

- ネイティブビルド(MSVC)

```
gradlew build -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

- Azure Functionエミュレータ起動

```
func start --verbose
```

- エンドポイント

```
http://localhost:7071/api/{path}
```

## デプロイ

GitHub Actions を手動実行し、GHCR へのイメージ公開と Azure Container Apps へのデプロイを行います。
事前に Azure のリソースグループと OIDC 認証・権限を設定してください。

デプロイ完了後、実行結果の Summary に表示される LINE Webhook URL を LINE 側に登録してください。

### 実行するワークフロー

| 項目 | 説明 |
| --- | --- |
| [Deploy to Azure Container Apps](.github/workflows/deploy.yml) | GitHub の Actions から選択し、Run workflow で手動実行します。 |
| `mode` | 実行方式。既定は `native`（GraalVM AOT）。`jvm` も選択できます。 |
| `publish_only` | イメージ公開だけを行う場合に有効にします。|

### 必須 Secrets

リポジトリの Settings → Environments で `production` を作成し、Environment secrets に登録します。

| 名前 | 設定する値 |
| --- | --- |
| `ANNICT_API_TOKEN` | Annict のアクセストークン。 |
| `LINE_BOT_CHANNEL_SECRET` | LINE Messaging API チャネルの Channel secret。 |
| `LINE_BOT_CHANNEL_TOKEN` | LINE Messaging API のチャネルアクセストークン。 |
| `LINE_BOT_ALLOWED_USER_IDS` | ボットを利用できる LINE の userId（カンマ区切り）。ここにないユーザーには、権限がない旨だけを返信する。 |
| `EDCB_API_URL` | EDCB Material WebUI のベース URL（例: `https://edcb.example.com`）。`/api/...` は含めない。 |
| `EDCB_API_HEADERS` | EDCB へのリクエストに付けるヘッダーを JSON オブジェクトで指定する。認証が不要なら `{}`。 |
| `AZURE_CLIENT_ID` | OIDC 認証に使用する Microsoft Entra アプリまたはマネージド ID のクライアント ID。 |
| `AZURE_TENANT_ID` | Azure のテナント ID。 |
| `AZURE_SUBSCRIPTION_ID` | デプロイ先のサブスクリプション ID。 |
| `AZURE_RESOURCE_GROUP` | デプロイ先の作成済みリソースグループ名。 |

`EDCB_API_HEADERS` の例。値はログに出力しません。

```json
{"CF-Access-Client-Id":"xxxx.access","CF-Access-Client-Secret":"xxxx"}
```

| 構成 | 指定するヘッダー |
| --- | --- |
| Cloudflare Access（サービストークン） | `CF-Access-Client-Id`、`CF-Access-Client-Secret` |
| Basic 認証のリバースプロキシ | `Authorization`（`Basic …`） |
| 認証なし（LAN・VPN 経由など） | `{}` |




