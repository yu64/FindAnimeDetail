podman build --platform linux/amd64 -f container/aca-deploy/Dockerfile -t localhost/find-anime-detail:production .

$settings = Get-Content ./local.settings.json -Raw -Encoding UTF8 | ConvertFrom-Json

$containerArgs = @(
    'run', '--rm', '--pull=never',
    '-p', '127.0.0.1:8080:8080'
)

foreach ($property in $settings.Values.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable(
        $property.Name, [string]$property.Value, 'Process'
    )
    $containerArgs += @('-e', $property.Name)
}

$containerArgs += 'localhost/find-anime-detail:production'
podman @containerArgs