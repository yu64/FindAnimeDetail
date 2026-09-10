# FindAnimeDetail

## 開発

### 前提

- Windows11
- proto (moonrepo)
- Visual Stadio Build Tools 2026 (C++)

### Run (JVM)

- Azure Storageエミュレータ起動

```
azurite --location . --debug azurite-debug.log
```

- Quarkus実行

```
./gradlew quarkusDev
```

- エンドポイント

```
http://localhost:8080/api/{path}
```

### Run (Native)

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


