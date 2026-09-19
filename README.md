# FindAnimeDetail

## アニメ検索

```text
/find #word 作品名
/find #word 作品名 #from 2026-10-01T00:00
/find #word 作品名 #from 2026-10
/find #format md #word 作品名
/find #word 作品名 #complete
```


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

- Azure Storageエミュレータ起動

```powershell
azurite --location . --debug azurite-debug.log
```

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


