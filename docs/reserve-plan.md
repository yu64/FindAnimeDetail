# /reserve（EDCB 自動予約）設計

`/find` で検索したアニメを、EDCB に EPG 自動予約として登録するコマンド。

## 1. 仕様

### 入力

```text
/reserve
放送開始日	時刻	曜日	放送局	タイトル	公式URL
2026-10-05	23:00	日	TOKYO MX	○○	https://example.com
```

- `/find` の TSV をそのまま貼り付け、予約しない行を消して送る。
- 先頭行に `タイトル` 列があれば見出しとして扱い、列名で列を探す。使うのは `タイトル` 列だけで、ほかの列は無視する。
- 見出しがなければ、1行を1タイトルとして扱う。見出しがないのにタブを含む行はエラー。
- 空行は無視する。行数の上限は 20 行（LINE の1メッセージ 5000 文字に結果を収めるため）。
- 1 行の先頭の空白・タブはパーサーが取り除く。`/find` の先頭列（放送開始日）は常に値か `N/A` が入るため、列はずれない。

### 出力

```text
結果	タイトル	キーワード	EPGヒット	備考
登録	○○ 第2期	○○	3	
重複	△△	△△	2	既存の自動予約あり
登録	□□	□□	0	EPG未取得。放送が近づけば一致する見込み
失敗	××	××	-	EDCBに接続できません
```

- `結果` は `登録`・`失敗`・`重複` の3種類。入力の行ごとに1行を返す。
- `EPGヒット` は登録時点で一致した番組数。取得できなければ `-`。

### 登録する自動予約

| 項目 | 値 |
| --- | --- |
| キーワード (`andKey`) | Annict のタイトル。末尾の「第2期」「Season 2」などは除いて緩める |
| 曖昧検索 (`aimaiFlag`) | 有効 |
| ジャンル (`contentList`) | `2047`（アニメ/特撮の大分類） |
| 局 (`serviceList`) | 指定しない（全局）。録画漏れを防ぐため局は絞らない |
| 録画済みチェック (`chkRecEnd` / `chkRecDay`) | 有効 / 6 日。同じ回を別の局で重複して録画しない |
| 録画設定 | 既定プリセット（`EnumRecPreset` の id=0）の値をすべて送る |
| 保存先 (`recFolder`) | `基準フォルダ/タイトル`。フォルダ名に使えない文字は置き換える。プラグインとファイル名書式はプリセットのもの |

送らなかった録画設定の項目はプリセットではなく初期値になるため、プリセットの値をすべて送る。

## 2. EDCB の API

EDCB 本体には REST 風の API がないため、EDCB Material WebUI の `api/` を使う。`?json=1` を付けると JSON を返す。

| API | メソッド | 用途 |
| --- | --- | --- |
| `api/Common?ctok=setautoadd&json=1` | GET | 登録用の CSRF トークン（12〜24 時間有効）を取得する |
| `api/EnumRecPreset?json=1` | GET | 録画プリセット |
| `api/EnumAutoAdd?json=1` | GET | 既存の自動予約。重複の判定に使う |
| `api/SearchEvent?andKey=…&json=1&count=1` | GET | キーワードの EPG ヒット件数（`total`） |
| `api/SetAutoAdd?json=1` | POST | 自動予約の登録（`addchg=1`）。フォーム形式 |

- 登録の応答は `{"success":"…"}` または `{"err":"…"}`。
- EDCB が Linux で動作する場合もあるため、保存先のパスの区切りは `/` とする。

## 3. 設定

| 環境変数 | 内容 |
| --- | --- |
| `EDCB_API_URL` | Material WebUI のベース URL |
| `EDCB_API_HEADERS` | リクエストに付けるヘッダー（JSON オブジェクト）。Cloudflare Access などの認証に使う。不要なら `{}` |
| `LINE_BOT_ALLOWED_USER_IDS` | ボットを利用できる LINE の userId（カンマ区切り）。`/reserve` に限らず全コマンドに適用する。未設定なら誰も利用できない |
| 基準フォルダ（名称未定） | 保存先の親フォルダ。既定は `/record` |

ヘッダーの値はログに出さない。

## 4. 構成

| 層 | クラス | 役割 |
| --- | --- | --- |
| presentation | `ReserveCommandDef` | TSV を解釈して `ReserveInput` を作る。ヘルプ |
| presentation | `LineController` | `ReserveInput` を `ReserveUsecase` へ渡す |
| usecase | `IInput.ReserveInput` | 予約するタイトルの一覧。形式が不正な行はエラーとして保持する |
| usecase | `ReserveUsecase` | キーワード作成 → 重複判定 → ヒット件数 → 登録 → 結果 TSV |
| usecase | `IEdcbClient` | EDCB 操作のインターフェイス |
| infrastructure | `IEdcbDef` | Quarkus REST Client。ヘッダー付与、`ctok` の取得、フォーム POST。JSON は `JsonNode` で扱う |
| infrastructure | `EdcbException` | 接続・認証・EDCB のエラー |
| config | `EdcbConfig` | URL・ヘッダー・基準フォルダ |

## 5. エラー処理

- EDCB の PC が停止・スリープしているときは、接続タイムアウトを短くし、全行を `失敗` で返す。
- 認証に失敗すると Cloudflare Access などはログイン画面へリダイレクトする。JSON 以外の応答は認証エラーとして扱う。

## 6. 範囲外

- 自動予約の一覧表示と削除（`/unreserve`）。
