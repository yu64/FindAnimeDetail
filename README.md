# FindAnimeDetail

## アニメ検索

```text
/find #word 作品名
/find #word 作品名 #from 2026-10-01T00:00
/find #format md #word 作品名
```

`#from` は初回放送日時の下限（指定日時を含む）です。省略時は検索実行時の現在日時を使います。
時差の指定がなければ日本時間です。`2026-10-01T00:00:00+09:00` の形式も使えます。
放送日時や放送局が不明な作品は検索結果から除外します。
TSVは「放送開始日・時刻・曜日・放送局・タイトル・公式URL」の順で出力します。公式URLが未登録の場合は空欄です。
`#format md` を指定すると、同じ列の表を [Kitware Markdown Viewer](https://github.com/Kitware/markdown-viewer) で開くURLを返します。
検索結果は圧縮してURLに含めます。URLが長すぎる場合は、検索範囲を絞る案内を返します。
過去に初回放送された作品を検索するときは、過去の日時を指定してください。

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


