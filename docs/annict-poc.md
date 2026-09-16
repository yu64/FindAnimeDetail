# AnnictサンプルのPoC

## 宣言型クライアント `IAnnictDef`

`ILineClientDef` と同じく、`@RegisterRestClient` と `DiRelay` で
`IAnnictClient` を注入できる。URLとトークンはAnnict設定を共有する。

```java
var condition = new IAnnictClient.SearchCondition(
    "SHIROBAKO", List.of(), null, false);
var works = annictClient.search(condition, annictConfig.channelPriority());
```

条件はタイトル、クール一覧（例: `List.of("2026-autumn")`）、初回日時の下限
（`OffsetDateTime`、未指定はnull）、未来限定（boolean）。タイトルかクールを必須とする。
作品は全ページ取得し、各作品の放送データも最優先局が見つかるか末尾までページングする。
まず指定局の優先順で局を選び、その局でAPIに登録されている最初の放送日時を採用する。
過去分を取得前に除外しない。話数・各話タイトルは取得しない。

下限は「以上」、未来限定は検索開始時刻より「後」で判定し、両指定なら両方を満たす作品を返す。
指定局の放送情報がない作品は日時条件の有無にかかわらず除外する。
局名・放送日時が欠落または空欄の放送枠、日時を解釈できない放送枠は候補から除外する。
初回日時はAsia/Tokyoに変換済み。TSVの放送開始日・時刻・曜日はそこから取り出せる。
返却値は作品ID・作品名・公式URL・初回放送（局ID、局名、日時）。
`FindUsecase` は `IAnnictClient` と `AnnictConfig` を注入して、各入力タイトルを検索する。
同じ作品IDは1行にまとめ、日本時間の「放送開始日・時刻・曜日・放送局・タイトル・公式URL」を
ヘッダー付きTSVで返す。放送日時・放送局が不明な作品は除外し、0作品の場合はヘッダーのみ返す。
外部の局名・タイトルに含まれるタブ・改行は空白に置き換える。
公式URLが未登録の場合は最終列を空欄にする。
`FindInput.fmt` が `MD` の場合は同じ6列のMarkdown表を生成し、Kitware Markdown ViewerのURLを返す。
UTF-8の本文をgzip圧縮し、Base64化・URLエンコードして `mdz` パラメータに入れる。
公式URLはリンクとして表示し、0作品なら該当なしのメッセージを表示する。
URLが5000文字を超える場合は、結果を切り捨てず検索範囲を絞る案内を返す。
省略時はTSV。クール条件はコマンド入力にはまだ追加していない。

`/find #word 作品名 #from 2026-10-01T00:00` で初回放送日時の下限（境界を含む）を指定できる。
時差のない日時は日本時間として扱い、`2026-10-01T00:00:00+09:00` や
`2026-09-30T15:00:00Z` も指定できる。日付だけの指定や不正な日時は入力エラーとする。
省略時は検索実行時の現在日時を使い、複数タイトルでも同じ下限で検索する。
過去に初回放送された作品を検索する場合は、過去の日時を明示する。

通常の検証: `./gradlew.bat test --tests 'app.infrastructure.client.IAnnictDefTest'`

実API確認は後述の環境変数を設定し、
`./gradlew.bat test --tests 'app.infrastructure.client.IAnnictDefLiveTest' --rerun` を実行する。
QuarkusのCDIと生成クライアントを使用し、全結果を
`build/reports/annict-poc/first-broadcast-results.json` に保存する。
同じテストクラスで `FindUsecase` 経由の実検索も行い、
`build/reports/annict-poc/find-results.tsv` にTSVを保存する。

注意: 「初回」はAPIに登録された放送データの最小日時であり、欠損がある場合の真の初回までは保証しない。
多数の作品・放送枠を検索するとリクエスト数が増える。

## Annict設定

`app.config.AnnictConfig` で設定をまとめる。Azureの環境変数で上書きできる。

| 環境変数 | 型 | 既定値 |
| --- | --- | --- |
| `ANNICT_API_URL` | `URI` | `https://api.annict.com/graphql` |
| `ANNICT_API_TOKEN` | `String` | `prototype-token-not-configured`（接続には実トークンが必要） |
| `ANNICT_API_CHANNEL_PRIORITY` | `List<Integer>` | `19,188,7,5,4,6,3,2,1` |

局IDはAnnictの `channel.annictId`。東京向けの既定順位はTOKYO MX、MX2、テレビ東京、
TBS、日本テレビ、テレビ朝日、フジテレビ、NHK Eテレ、NHK総合。
カンマ区切りの値はQuarkusが指定順を保って整数リストへ変換する。
`IAnnictDef.search` にこのリストを渡すと、検索結果に局の優先順位を適用する。

## 局IDの実API確認（2026-09-12）

SHIROBAKOを検索し、`programs` 内で `channel { annictId id name }` を取得できた。
重複を除いた結果は `build/reports/annict-poc/channel-ids.json`、
GraphQL応答全体は `build/reports/annict-poc/channel-ids-response.json` に保存。

| annictId | name |
| --- | --- |
| 17 | BSフジ |
| 19 | TOKYO MX |
| 20 | AT-X |
| 48 | MBS毎日放送 |
| 59 | テレビ愛知 |
| 165 | ニコニコチャンネル |

`annictId` は整数で、`id` はGraphQLのID文字列（例：TOKYO MXは `Q2hhbm5lbC0xOQ==`）。
Annictの局優先順位は `channel.annictId` で管理できる。しょぼいカレンダーのChIDとは別物。
これは取得可否の確認で、サンプル本体の検索結果型にはまだ局IDを追加していない。

本番実装を変更せず、`AnnictAnimeSearchClient.searchByTitle` をJUnitから検証する。

## 外部APIに依存しないテスト

```powershell
./gradlew.bat test --tests 'app.infrastructure.AnnictAnimeSearchClientTest'
```

JDKのHTTPサーバーをループバックアドレスの空きポートで起動する。Quarkusの起動や実トークンは不要。
POST先・認証ヘッダー・JSON変数、日本語と引用符、複数作品・放送情報・日時・nullの変換、
検索上限の境界、空の検索結果、不正入力、HTTPエラー、HTTP 200のGraphQLエラー、不正JSONを検証する。
このテスト単独ではAnnictのスキーマとの互換性は証明できない。

## 実Annict APIに接続するテスト

通常はスキップされる。Java 25が利用できるPowerShellで、次を実行する。
`local.settings.json` の設定は通常のGradle `test` には自動注入されないため、明示的に渡す。

```powershell
$annictSettings = Get-Content -Raw local.settings.json | ConvertFrom-Json
$env:ANNICT_API_URL = $annictSettings.Values.ANNICT_API_URL
$env:ANNICT_API_TOKEN = $annictSettings.Values.ANNICT_API_TOKEN
$env:ANNICT_LIVE_TEST = 'true'
try {
    ./gradlew.bat test --tests 'app.infrastructure.AnnictAnimeSearchClientLiveTest' --rerun
} finally {
    Remove-Item Env:ANNICT_LIVE_TEST, Env:ANNICT_API_TOKEN, Env:ANNICT_API_URL -ErrorAction SilentlyContinue
}
```

`--rerun` は前回のテスト結果を再利用せず、接続を実行するために指定する。
既知のタイトル `SHIROBAKO` を最大5作品検索し、結果が空でないこと、タイトル・ID・取得できた放送情報を確認する。
標準出力には作品・公式URL・放送情報の件数だけを記録する。認証情報は記録しない。
これは外部サービスに依存する結合テストで、認証・ネットワーク・データ変更の影響を受ける。
タイトル部分一致の厳密な仕様、ページング、タイムアウト、全作品の欠損データへの対応は、このPoCの検証対象外。

結果は `build/reports/tests/test/index.html` で確認できる。
実APIから取得して `AnimeSearchResult` に変換した全件データは、
`build/reports/annict-poc/search-results.json` に整形したJSONで出力する。

## `/me/programs` の追加調査（2026-09-11）

同じトークンでGETした結果は、次の3条件ともHTTP 200・`programs: []`・`total_count: 0`。
生レスポンスは同じ出力ディレクトリに保存した。

| 条件 | ファイル |
| --- | --- |
| SHIROBAKO（4168）、日時制限なし、古い順、3件 | `me-programs-past.json` |
| SHIROBAKO（4168）、実行時の現在UTC日時より後、近い順、3件 | `me-programs-future-shirobako.json` |
| 作品制限なし、実行時の現在UTC日時より後、近い順、3件 | `me-programs-future.json` |

公式公開ソースでは、`/me/programs` はトークンのユーザーが「見たい」「見てる」にした作品に限定され、
さらにライブラリに紐づけた `program_id` の放送枠に絞られる。その後に作品ID・日時フィルターが適用される。
したがって、作品ID指定でユーザー別の制限を解除することはできない。
今回の0件はAnnict全体に放送予定がないことを意味しない。ユーザー設定の変更は行っていない。

- https://github.com/annict/annict/blob/main/rails/app/controllers/api/v1/me/slots_controller.rb
- https://github.com/annict/annict/blob/main/rails/app/queries/deprecated/user_slots_query.rb
- https://github.com/annict/annict/blob/main/rails/app/services/deprecated/api/v1/me/slot_index_service.rb
