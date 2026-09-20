using './main.bicep'

// パラメータ入力。アプリのデプロイ関連
param appName = readEnvironmentVariable('ACA_APP_NAME', 'find-anime-detail')
param environmentName = readEnvironmentVariable('ACA_ENVIRONMENT_NAME', '${appName}-env')
param location = readEnvironmentVariable('AZURE_LOCATION', 'japaneast')
param resourceSize = readEnvironmentVariable('ACA_RESOURCE_SIZE', 'small')

// パラメータ入力。実行対象の digest 付きの公開 GHCR イメージ参照。
param containerImage = readEnvironmentVariable('CONTAINER_IMAGE')

// Actions の実行ID。手動で Bicep を適用する場合は省略できる。
param deploymentId = readEnvironmentVariable('ACA_DEPLOYMENT_ID', '')

// パラメータ入力。シークレット値
param annictApiToken = readEnvironmentVariable('ANNICT_API_TOKEN')
param lineBotChannelSecret = readEnvironmentVariable('LINE_BOT_CHANNEL_SECRET')
param lineBotChannelToken = readEnvironmentVariable('LINE_BOT_CHANNEL_TOKEN')

// パラメータ入力。設定値
param annictApiUrl = readEnvironmentVariable('ANNICT_API_URL', 'https://api.annict.com/graphql')
param lineApiBaseUrl = readEnvironmentVariable('LINE_API_BASE_URL', 'https://api.line.me')
param annictApiChannelPriority = readEnvironmentVariable('ANNICT_API_CHANNEL_PRIORITY', '19,188,7,5,4,6,3,2,1')
