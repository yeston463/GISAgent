# 生成生产独立密钥并写入 .env（模板来自 .env.production.example）。
#
# 用法（服务器或开发机）：
#   powershell -ExecutionPolicy Bypass -File scripts/gen-prod-secrets.ps1
#
# 行为：
#   - 对 AUTH_JWT_SECRET / POSTGRES_PASSWORD / REDIS_PASSWORD / AUTH_ADMIN_PASSWORD /
#     LLM Key 等留空项生成强随机值并写回 .env
#   - 已填的值保持不变（不覆盖）
#   - 生成后打印：哪些是新增、哪些需人工填写（如 GeoScene 门户凭据）
# 安全要求：本脚本只写 .env，绝不打印明文密钥。

[CmdletBinding()]
param(
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"
if (-not $EnvFile) { $EnvFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env' }
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host "未找到 $EnvFile，请先：cp .env.production.example .env" -ForegroundColor Yellow
    exit 1
}

function New-StrongSecret([int]$bytes = 48) {
    $buf = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buf)
    # URL-safe，避免 .env 解析/URL 转义问题
    return [Convert]::ToBase64String($buf).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

# 需要自动生成的键（生成长度 bytes）
$autoKeys = @{
    "AUTH_JWT_SECRET"      = 48
    "POSTGRES_PASSWORD"    = 32
    "REDIS_PASSWORD"       = 32
    "AUTH_ADMIN_PASSWORD"  = 24
}

# 需要人工填写的键（模板留空）
$manualKeys = @("GEOSCENE_PORTAL_PASSWORD", "QWEN-APIKEY", "DEEPSEEK_API_KEY", "AMAP_KEY")

$lines = Get-Content -LiteralPath $EnvFile -Encoding UTF8
$newCount = 0
$manualMissing = @()

for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -match '^\s*([A-Z0-9_-]+)\s*=') {
        $key = $Matches[1]
        $value = ($line -split '=', 2)[1].Trim()
        if ($autoKeys.ContainsKey($key) -and ([string]::IsNullOrWhiteSpace($value))) {
            $lines[$i] = "$key=" + (New-StrongSecret $autoKeys[$key])
            $newCount++
        } elseif ($manualKeys -contains $key -and ([string]::IsNullOrWhiteSpace($value))) {
            $manualMissing += $key
        }
    }
}

[System.IO.File]::WriteAllLines($EnvFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "完成。自动生成并写入 $newCount 个密钥（仅写入 .env，未回显明文）。" -ForegroundColor Green
if ($manualMissing.Count -gt 0) {
    Write-Host "以下项仍需人工填写（模板未留随机值）：$($manualMissing -join ', ')" -ForegroundColor Yellow
}
Write-Host "提示：GeoScene 门户与 LLM Key 必须填生产专用账号，勿复用开发值。" -ForegroundColor Yellow
