// #############################################################################
// MARK: スコープ定義


// リソースグループの中にデプロイすると宣言
targetScope = 'resourceGroup'


// #############################################################################
// MARK: パラメータ定義 (インターフェイスのようなもの)


@description('Container App 名。小文字英数字とハイフンを使用する')
@minLength(2)
@maxLength(32)
param appName string = 'find-anime-detail'

@description('Container Apps Environment 名')
param environmentName string = '${appName}-env'

// 配置先リージョン
// 未指定の場合、リソースグループのリージョンを使用する
param location string = resourceGroup().location

// 公開 GHCR のビルド済みイメージを digest 付き (必須)
@description('公開イメージの参照。例: ghcr.io/owner/find-anime-detail@sha256:...')
@minLength(1)
param containerImage string

// 同じイメージでも、Actions の実行ごとに revision を更新して秘密値を反映する。
param deploymentId string = ''

// AnnictのAPIキー
@secure()
@minLength(1)
param annictApiToken string

// LINE BOTのチャンネルシークレット
@secure()
@minLength(1)
param lineBotChannelSecret string

// LINE BOTのチャンネルトークン
@secure()
@minLength(1)
param lineBotChannelToken string

// Annict API のベース URL
param annictApiUrl string = 'https://api.annict.com/graphql'

// LINE APIのベース URL
param lineApiBaseUrl string = 'https://api.line.me'

// 優先する放送局のID
param annictApiChannelPriority string = '19,188,7,5,4,6,3,2,1'


// =============================================================================
// MARK: コンテナリソースの定義変数


// パターン
var resourceSizes = {
  small: {
    cpu: json('0.25')
    memory: '0.5Gi'
  }
  medium: {
    cpu: json('0.5')
    memory: '1Gi'
  }
}

// 使用するCPU・メモリのパターン名
@description('small: 0.25 vCPU / 0.5 GiB、medium: 0.5 vCPU / 1 GiB。')
@allowed([
  'small'
  'medium'
])
param resourceSize string = 'small'


// #############################################################################
// MARK: 実行環境の定義


// Container Apps Environmentを宣言
resource appEnv 'Microsoft.App/managedEnvironments@2025-07-01' = {
  name: environmentName
  location: location
  properties: {
    appLogsConfiguration: {
      // ログを保存しない
      destination: 'none'
    } 
    workloadProfiles: [
      // Consumption方式を準備
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
  }
}


// #############################################################################
// MARK: アプリケーションの定義


// App宣言
resource app 'Microsoft.App/containerApps@2025-07-01' = {
  name: appName
  location: location
  properties: {
    environmentId: appEnv.id
    workloadProfileName: 'Consumption'
    configuration: {
      // Single = アプリの新しい版ができたらそちらに切り替える
      activeRevisionsMode: 'Single'
      ingress: {
        // インターネットからアクセス許可
        external: true
        // HTTPをHTTPSにリダイレクト
        allowInsecure: false
        // 8080に転送
        targetPort: 8080
        // HTTP通信方式の自動判別
        transport: 'auto'
      }

      // シークレット値を ACAシークレットとして登録
      secrets: [
        {
          name: 'annict-api-token'
          value: annictApiToken
        }
        {
          name: 'line-bot-channel-secret'
          value: lineBotChannelSecret
        }
        {
          name: 'line-bot-channel-token'
          value: lineBotChannelToken
        }
      ]
    }

    template: {
      // コンテナ設定
      containers: [
        {
          name: 'app'
          image: containerImage
          // CPU設定等
          resources: resourceSizes[resourceSize]
          
          // アプリの環境変数を登録
          env: [
            {
              name: 'DEPLOYMENT_ID'
              value: deploymentId
            }
            {
              name: 'ANNICT_API_TOKEN'
              secretRef: 'annict-api-token'
            }
            {
              name: 'LINE_BOT_CHANNEL_SECRET'
              secretRef: 'line-bot-channel-secret'
            }
            {
              name: 'LINE_BOT_CHANNEL_TOKEN'
              secretRef: 'line-bot-channel-token'
            }
            {
              name: 'ANNICT_API_URL'
              value: annictApiUrl
            }
            {
              name: 'LINE_API_BASE_URL'
              value: lineApiBaseUrl
            }
            {
              name: 'ANNICT_API_CHANNEL_PRIORITY'
              value: annictApiChannelPriority
            }
          ]

          // ACAがコンテナ状態を確認する構成
          probes: [
            // 8080にTCP接続できるか起動時監視する
            // 失敗した場合、起動失敗として再起動する
            {
              type: 'Startup'
              tcpSocket: {
                port: 8080
              }
              periodSeconds: 10
              timeoutSeconds: 1
              failureThreshold: 30
            }
            // リクエスト転送が可能であるか常時確認する
            // 失敗した場合、該当のコンテナを転送先から一時的に外す
            {
              type: 'Readiness'
              httpGet: {
                path: '/api/sample'
                port: 8080
                scheme: 'HTTP'
              }
              periodSeconds: 10
              timeoutSeconds: 2
              failureThreshold: 3
            }
          ]
        }
      ]

      // 実行台数などスケール設定
      scale: {
        // 稼働台数の有効範囲
        minReplicas: 0
        maxReplicas: 1
        rules: [
          {
            name: 'http'
            http: {
              metadata: {
                // 1レプリカあたりの同時リクエスト数の目標値
                concurrentRequests: '10'
              }
            }
          }
        ]
      }
    }
  }
}


// #############################################################################
// MARK: デプロイ結果の返却定義


// アプリ名
output containerAppName string = app.name

// アプリのURL
// "app.properties.configuration.ingress" = アプリの公開設定
output appUrl string = 'https://${app.properties.configuration.ingress!.fqdn}'

// LINE用のWebhook URL
output webhookUrl string = 'https://${app.properties.configuration.ingress!.fqdn}/api/line/webhook'
