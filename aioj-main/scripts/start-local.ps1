$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'deploy/compose.yml'
$aiComposeFile = Join-Path $projectRoot 'deploy/compose.local-legacy-ai.yml'
$envFile = Join-Path (Split-Path -Parent $projectRoot) '.env'
$projectName = 'ai-oj-next'

if (-not (Test-Path -LiteralPath $composeFile)) {
    throw "Compose file not found: $composeFile"
}
if (-not (Test-Path -LiteralPath $aiComposeFile)) {
    throw "Fixed AI Compose file not found: $aiComposeFile"
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Local environment file not found: $envFile`nCopy .env.example to .env and replace the local placeholder values first."
}

Write-Host "Starting AIOJ local environment ($projectName)..." -ForegroundColor Cyan

$composeArgs = @(
    '--context', 'desktop-linux',
    'compose',
    '--project-name', $projectName,
    '--env-file', $envFile,
    '--file', $composeFile,
    '--file', $aiComposeFile,
    'up', '-d'
)

docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose failed to start the AIOJ environment.'
}

Write-Host ''
Write-Host 'AIOJ local environment is running.' -ForegroundColor Green
Write-Host 'User:    http://localhost:5175'
Write-Host 'Admin:   http://127.0.0.1:5176'
Write-Host 'Gateway: http://127.0.0.1:8101'
Write-Host 'Health:  http://127.0.0.1:8101/actuator/health'
Write-Host ''
Write-Host 'Useful commands:' -ForegroundColor DarkGray
Write-Host "  docker compose --project-name $projectName --env-file `"$envFile`" --file `"$composeFile`" ps"
Write-Host "  docker compose --project-name $projectName --env-file `"$envFile`" --file `"$composeFile`" logs -f gateway-service"
