param(
    [switch]$BuildSandbox
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'deploy/compose.dev.yml'
$envFile = Join-Path (Split-Path -Parent $projectRoot) '.env'

$composeArgs = @('--context', 'desktop-linux', 'compose', '--project-name', 'ai-oj-next-dev', '--file', $composeFile)
if (Test-Path -LiteralPath $envFile) {
    $composeArgs += @('--env-file', $envFile)
}
$composeArgs += @('up', '-d')
if ($BuildSandbox) {
    $composeArgs += '--build'
}
$composeArgs += @('mysql', 'redis', 'rabbitmq', 'sandbox')

docker @composeArgs
Write-Host ''
Write-Host 'Development dependencies are running.'
Write-Host 'Run Java services from a separate terminal with Maven or your IDE.'
Write-Host 'Example: cd backend; mvn -pl ai-service -am spring-boot:run'
