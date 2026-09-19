# Azure Container Apps のデプロイ設定

ACA 向けの実行用イメージを `Dockerfile` で作成します。リソースを定義する `main.bicep` と GitHub Actions は今後実装予定です。

## ビルド

リポジトリのルートで実行します。Docker の代わりに Podman も使用できます。

```powershell
# Native（既定）
docker build --platform linux/amd64 -f container/aca-deploy/Dockerfile -t find-anime-detail:native .

# JVM
docker build --platform linux/amd64 -f container/aca-deploy/Dockerfile --target jvm -t find-anime-detail:jvm .
```

ビルド時は `.prototools` をそのまま `proto use` で読み込み、テストとコンパイルを行います。Native はビルドマシン固有の CPU 命令への依存を避けるため `march=compatibility` を指定します。

最終イメージには Native 実行ファイル、または JAR と同じ JDK から生成した Java ランタイムを配置します。ソースコード、Gradle、proto、Azure CLI、`local.settings.json` は含めません。どちらも非 root ユーザーで HTTP サーバーを直接起動します。

## 実行時の設定

ACA の ingress の `targetPort` は **8080** に設定します。アプリのパスは `/api` です。

設定は ACA の環境変数から渡します。トークンとチャネルシークレットには ACA secrets の参照を使用します。

| 環境変数 | 内容 |
| --- | --- |
| `ANNICT_API_TOKEN` | Annict トークン |
| `LINE_BOT_CHANNEL_SECRET` | LINE チャネルシークレット |
| `LINE_BOT_CHANNEL_TOKEN` | LINE チャネルアクセストークン |
| `ANNICT_API_CHANNEL_PRIORITY` | 放送局 ID の優先順位（カンマ区切り、省略時はアプリの既定値） |
| `ANNICT_API_URL` | Annict API URL（省略時は公式 API） |
| `LINE_API_BASE_URL` | LINE API URL（省略時は公式 API） |

開発用の `local.settings.json` の読み込み処理は使用しません。ローカルで実行用イメージを試す場合も、必要な値を環境変数として渡します。

```powershell
# 上記の必須3変数をホストの環境変数に設定した状態で実行
docker run --rm -p 127.0.0.1:8080:8080 `
  -e ANNICT_API_TOKEN -e LINE_BOT_CHANNEL_SECRET -e LINE_BOT_CHANNEL_TOKEN `
  find-anime-detail:native
```

確認用 URL は `http://localhost:8080/api/sample`、LINE Webhook は `/api/line/webhook` です。

> **補足：公開・デプロイ**  
> GHCR への登録と ACA へのデプロイは別途行います。Native／JVM はイメージのビルド時に選択し、ACA には生成したイメージの digest を指定する方針です。

開発用のコンテナ設定は [`../dev/`](../dev/)、デプロイ方針は [調査資料](../../docs/container-apps-research.md) を参照してください。
