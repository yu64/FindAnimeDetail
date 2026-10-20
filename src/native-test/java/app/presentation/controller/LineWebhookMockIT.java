package app.presentation.controller;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** モックテストを Native 実行ファイルに対して行う。リフレクション設定の漏れなどを検出する。 */
@QuarkusIntegrationTest
class LineWebhookMockIT extends LineWebhookMockTest {
}
