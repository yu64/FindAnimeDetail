# アニメ検索APIの調査

## 結論

プロトタイプには Annict GraphQL API を採用する。タイトルによる作品検索に加え、作品名、公式サイトURL、放送局、各話の放送開始日時を1リクエストで取得でき、日本のアニメ・放送情報を扱う本アプリの要件に最も近い。

利用には無料のAnnictアカウントと読み取り専用の個人用アクセストークンが必要。GraphQL APIはベータ版なので、応答エラーの監視とクライアント側のタイムアウトを設ける。

## 候補比較

| API | 認証 | 部分タイトル検索 | 公式URL | 放送開始 | 日本の放送局 | 判断 |
| --- | --- | --- | --- | --- | --- | --- |
| Annict | 無料トークン | 対応 | 対応 | 日時に対応 | 対応 | 採用 |
| AniList | 公開情報は認証不要 | 対応 | 外部リンクとして取得可能 | 日付・次回放送に対応 | 不足 | 海外名検索や補完用 |
| Jikan | 不要 | 対応 | MAL URL中心 | 放送曜日・時刻、放送期間 | 局名は不足 | 無認証デモ向け |
| しょぼいカレンダー | 主な取得APIは不要 | 対応 | タイトルのリンク情報 | 詳細な番組表 | 対応 | 情報は強いがJSON APIが実験的 |

## プロトタイプの利用方法

環境変数 `ANNICT_API_TOKEN` に読み取り専用トークンを設定し、`AnnictAnimeSearchClient#searchByTitle` を呼び出す。

```java
List<AnimeSearchResult> results = client.searchByTitle("葬送", 10);
```

放送予定は各話単位で返るため、同じ放送局が複数回現れる。作品そのものの公開日だけが必要な場合と、各局・各話の放送日時が必要な場合は、ユースケース層で明示的に区別する。

## 参照先

- Annict Developers: https://developers.annict.com/
- AniList API Docs: https://docs.anilist.co/
- Jikan API Docs: https://docs.jikan.moe/
- しょぼいカレンダー ヘルプ: https://docs.cal.syoboi.jp/spec/feeds/
