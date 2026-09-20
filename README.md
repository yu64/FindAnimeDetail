# FindAnimeDetail

## アニメ検索

```text
/find #word 作品名
/find #word 作品名 #from 2026-10-01T00:00
/find #word 作品名 #from 2026-10
/find #format md #word 作品名
/find #word 作品名 #all
```


既定では放送日時・放送局が揃った作品だけを表示します。`#all` を指定すると、不明な情報がある作品も含め、不明な欄は `N/A` と表示します。公式URLの有無は除外条件に含めません。

## 開発

### 環境変数

ルート直下の `local.settings.example.json` をもとに `local.settings.json` を作成してください。

### 環境
- proto (moonrepo)を推奨。下記で必要なツールをセットアップ。

```project
proto use
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

### 必須 Variables

同じ `production` の Environment variables に登録します。

| 名前 | 設定する値 |
| --- | --- |
| `AZURE_CLIENT_ID` | OIDC 認証に使用する Microsoft Entra アプリまたはマネージド ID のクライアント ID。 |
| `AZURE_TENANT_ID` | Azure のテナント ID。 |
| `AZURE_SUBSCRIPTION_ID` | デプロイ先のサブスクリプション ID。 |
| `AZURE_RESOURCE_GROUP` | デプロイ先の作成済みリソースグループ名。 |




