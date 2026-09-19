# 共通のビルド環境。実行用イメージにはこのステージのツールを含めない。
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

# ビルド定義とアプリケーションのソース。
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ ./gradle/
COPY build-logic/ ./build-logic/
COPY src/ ./src/

# JVM 版のビルドステージ。
FROM sources AS jvm-build

# テストと JAR ビルドを行い、同じ JDK から実行用 Java ランタイムを作る。
RUN bash -e -o pipefail -c 'activation="$(proto activate bash --export)" && eval "$activation" \
    && bash ./gradlew --no-daemon test quarkusBuild \
    -Dquarkus.native.enabled=false -Dquarkus.package.jar.enabled=true \
    -Dquarkus.package.jar.type=fast-jar \
    && "$JAVA_HOME/bin/jlink" --module-path "$JAVA_HOME/jmods" \
    --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.management,jdk.naming.dns \
    --strip-debug --no-header-files --no-man-pages --output /opt/java-runtime'

# Native 版のビルドステージ。
FROM sources AS native-build

# テストと Native ビルドを行う。ビルドマシン固有の CPU 命令への依存を避ける。
RUN bash -e -o pipefail -c 'activation="$(proto activate bash --export)" && eval "$activation" \
    && bash ./gradlew --no-daemon build \
    -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false \
    -Dquarkus.native.march=compatibility -Dquarkus.package.output-name=application'

# 両モード共通の実行環境。ビルド環境と libc のバージョンを合わせる。
FROM ubuntu:24.04 AS runtime

# 実行用ライブラリ・CA 証明書を導入し、非 root の実行ユーザーを作る。
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --home-dir /app --shell /usr/sbin/nologin app

# 作業ディレクトリと HTTP 待ち受け設定。ACA の targetPort は 8080 に合わせる。
WORKDIR /app
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080
EXPOSE 8080

# 以降の実行ステージは共通の非 root ユーザーで起動する。
USER 10001:10001

# --target jvm で選択する JVM 実行用イメージ。
FROM runtime AS jvm

# Java ランタイムとビルド成果物だけをコピーする。
COPY --from=jvm-build /opt/java-runtime/ /opt/java/
COPY --from=jvm-build /workspace/build/quarkus-app/ /app/

# ビルド時に生成した Java ランタイムを使用する。
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"

# 開発モードを経由せず、JAR を直接起動する。
ENTRYPOINT ["/opt/java/bin/java", "-jar", "/app/quarkus-run.jar"]

# 既定の Native 実行用イメージ。Java やビルドツールは含めない。
FROM runtime AS native

# 実行ファイルだけをコピーし、実行権限を付ける。
COPY --from=native-build --chmod=755 /workspace/build/application-runner /app/application

# Native 実行ファイルを直接起動する。
ENTRYPOINT ["/app/application"]
