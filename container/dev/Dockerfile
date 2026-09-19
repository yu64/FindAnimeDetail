# 共通の開発環境。コンパイラー・Gradle・ソースを保持する。
FROM ubuntu:24.04 AS sources

# ローカルおよび native-image プラグインと互換性のある proto バージョン。
ARG PROTO_VERSION=0.59.0

# ビルド用ライブラリと、Azure CLI の導入に必要な Python。
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl git unzip gzip xz-utils jq build-essential zlib1g-dev \
    python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

# proto 本体の導入とコマンド検索パス。
ENV PROTO_HOME=/opt/proto
ENV PATH="/opt/proto/shims:/opt/proto/bin:${PATH}"
RUN curl -fsSL https://moonrepo.dev/install/proto.sh -o /tmp/install-proto.sh \
    && bash /tmp/install-proto.sh "${PROTO_VERSION}" --yes --no-profile \
    && rm /tmp/install-proto.sh

# リポジトリの定義を使い、ローカルと同じツールを導入する。
WORKDIR /workspace
COPY .prototools ./
RUN proto use

# コンテナ外からアクセスできる HTTP 待ち受け設定。
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080

# ビルド定義とアプリケーションのソース。
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ ./gradle/
COPY build-logic/ ./build-logic/
COPY src/ ./src/

# 両モード共通の起動処理。local.settings.json を環境変数に読み込む。
COPY container/dev/entrypoint.sh /usr/local/bin/dev-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["bash", "/usr/local/bin/dev-entrypoint.sh"]

# JVM モード用のビルドステージ。
FROM sources AS jvm

# proto の環境変数を有効にしてテストと JAR ビルドを行う。
RUN bash -e -o pipefail -c 'activation="$(proto activate bash --export)" && eval "$activation" \
    && bash ./gradlew --no-daemon test quarkusBuild \
    -Dquarkus.native.enabled=false -Dquarkus.package.jar.enabled=true'

# 開発モードで起動し、ソース変更を次のリクエスト時に反映する。
CMD ["bash", "./gradlew", "--no-daemon", "quarkusDev", "-Dquarkus.native.enabled=false", "-Dquarkus.package.jar.enabled=true", "-Ddebug=false"]

# Native モード。最後のステージなので、target 未指定時の既定となる。
FROM sources AS native

# proto の環境変数を有効にしてテストと Native ビルドを行う。
RUN bash -e -o pipefail -c 'activation="$(proto activate bash --export)" && eval "$activation" \
    && bash ./gradlew --no-daemon build \
    -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
    -Dquarkus.package.output-name=application'

# ビルド済みの実行ファイルを起動する。ソース変更の反映には再ビルドが必要。
CMD ["./build/application-runner"]
