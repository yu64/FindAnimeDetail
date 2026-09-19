#!/usr/bin/env bash

# コマンド失敗・未定義変数・パイプ途中の失敗を検出して終了する。
set -euo pipefail

# proto が生成した設定を適用し、JAVA_HOME・GRAALVM_HOME などを設定する。
activation="$(proto activate bash --export)"
eval "$activation"

# ホストからマウントされた設定ファイルの存在を確認する。
settings=/run/config/local.settings.json
if [[ ! -f "$settings" ]]; then
    echo 'Mount local.settings.json at /run/config/local.settings.json.' >&2
    exit 1
fi

# 展開前に JSON と環境変数の形式を検証する。設定値はシェルコードとして評価しない。
if ! jq -e '
    type == "object" and (.IsEncrypted != true) and
    (.Values | type == "object" and all(to_entries[];
        (.key | test("^[A-Za-z_][A-Za-z0-9_]*$")) and
        (.value | type == "string" and (contains("\u0000") | not))
    ))
' "$settings" >/dev/null 2>&1; then
    echo 'local.settings.json must be unencrypted and contain Values with valid environment names and string values (no NUL characters).' >&2
    exit 1
fi

# NUL 区切りで読み込み、空白・引用符・ドル記号・改行をそのまま保持する。
while IFS= read -r -d '' entry; do
    export "$entry"
done < <(jq -j '.Values | to_entries[] | .key + "=" + .value + "\u0000"' "$settings")

# アプリにプロセスを置き換え、停止シグナルが直接届くようにする。
exec "$@"
