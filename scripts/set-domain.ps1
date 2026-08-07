# 把占位域名 gis.example.com 替换为真实域名，同步 nginx.conf 与 entrypoint.sh。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/set-domain.ps1 -Domain gis.mydomain.com
#
# 修改文件：
#   - docker/nginx/nginx.conf    : ssl_certificate/ssl_certificate_key 路径与注释
#   - docker/nginx/entrypoint.sh : CERT_DIR 检测路径
# 修改后需重新构建 nginx 镜像（域名会烧进镜像），或服务器上直接改这两个文件再 build。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Domain
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if ($Domain -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.\-]*$') {
    Write-Host "域名格式不合法：$Domain" -ForegroundColor Red
    exit 1
}

$nginxConf = Join-Path $root 'docker/nginx/nginx.conf'
$entrypoint = Join-Path $root 'docker/nginx/entrypoint.sh'

# 备份
Copy-Item $nginxConf "$nginxConf.bak" -Force
Copy-Item $entrypoint "$entrypoint.bak" -Force

$c1 = (Get-Content -LiteralPath $nginxConf -Raw -Encoding UTF8).Replace('gis.example.com', $Domain)
[System.IO.File]::WriteAllText($nginxConf, $c1, (New-Object System.Text.UTF8Encoding($false)))

$c2 = (Get-Content -LiteralPath $entrypoint -Raw -Encoding UTF8).Replace('gis.example.com', $Domain)
[System.IO.File]::WriteAllText($entrypoint, $c2, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "已替换域名：$Domain" -ForegroundColor Green
Write-Host "  修改: docker/nginx/nginx.conf" -ForegroundColor Cyan
Write-Host "  修改: docker/nginx/entrypoint.sh" -ForegroundColor Cyan
Write-Host "备份在 *.bak，确认无误后可删除。下一步："
Write-Host "  docker compose -f compose-prod.yaml build nginx && docker compose -f compose-prod.yaml up -d nginx"
Write-Host "若尚未申请证书，entrypoint 会自动用自签证书兜底（HTTPS 可访问但提示不受信）。"
