[CmdletBinding()]
param(
    [string]$JavaUrl = "http://127.0.0.1:8080",
    [string]$PythonUrl = "http://127.0.0.1:8000",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "",
    [switch]$TestRateLimit,
    [int]$RateLimitMax = 5,
    [int]$RateLimitChecks = 12
)

$ErrorActionPreference = "Continue"
$passed = 0
$failed = 0

function Pass([string]$message) { $script:passed++; Write-Host "[PASS] $message" -ForegroundColor Green }
function Fail([string]$message) { $script:failed++; Write-Host "[FAIL] $message" -ForegroundColor Red }

# 用 HttpWebRequest 发起请求：显式禁用代理、容忍自签证书，返回
# (StatusCode, Body)。所有 2xx/4xx/5xx 都原样返回；网络级失败返回 (-1, $null)。
function Send-WebRequest([string]$method, [string]$uri, [hashtable]$headers = @{}, [string]$body = $null, [int]$timeout = 20) {
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
    $req = [System.Net.HttpWebRequest]::Create($uri)
    $req.Method = $method
    $req.Timeout = $timeout * 1000
    $req.ReadWriteTimeout = $timeout * 1000
    $req.Proxy = $null
    $req.ContentType = "application/json"
    foreach ($k in $headers.Keys) { $req.Headers[$k] = $headers[$k] }
    $respBody = $null
    try {
        if (-not [string]::IsNullOrEmpty($body)) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $reqStream = $req.GetRequestStream()
            $reqStream.Write($bytes, 0, $bytes.Length)
            $reqStream.Close()
        }
        $resp = $req.GetResponse()
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $respBody = $reader.ReadToEnd()
        $code = [int]$resp.StatusCode
        $reader.Close()
        $resp.Close()
        return @($code, $respBody)
    } catch [System.Net.WebException] {
        $webErr = $_.Exception
        if ($webErr.Response) {
            $errResp = $webErr.Response
            try {
                $errReader = New-Object System.IO.StreamReader($errResp.GetResponseStream())
                $respBody = $errReader.ReadToEnd()
                $errReader.Close()
            } catch { $respBody = $null }
            return @([int]$errResp.StatusCode, $respBody)
        }
        return @(-1, $null)
    } catch {
        return @(-1, $null)
    }
}

# 执行请求并返回 HTTP 状态码。网络级失败返回 -1。
function Get-Status([string]$method, [string]$uri, [hashtable]$headers = @{}, [string]$body = $null, [int]$timeout = 20) {
    return (Send-WebRequest $method $uri $headers $body $timeout)[0]
}

# 请求并返回原始响应体；网络级失败返回空串。
function Invoke-Json([string]$method, [string]$uri, [hashtable]$headers = @{}, [string]$body = $null, [int]$timeout = 20) {
    $result = Send-WebRequest $method $uri $headers $body $timeout
    if ($null -eq $result[1]) { return "" }
    return $result[1]
}

if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            $trimmed = $line.Trim()
            if ($trimmed -match '^AUTH_ADMIN_PASSWORD=') {
                $AdminPassword = (($trimmed -split '=', 2)[1]).Trim().Trim('"').Trim("'")
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
        Write-Host "请通过 -AdminPassword 或 .env 的 AUTH_ADMIN_PASSWORD 提供管理员密码。" -ForegroundColor Yellow
        exit 2
    }
}

Write-Host "目标: Java=$JavaUrl Python=$PythonUrl 管理员=$AdminUsername"

# ---------------------------------------------------------------------------
# 1. 认证
# ---------------------------------------------------------------------------
Write-Host "`n== 认证 ==" -ForegroundColor Cyan

$code = Get-Status GET "$JavaUrl/api/gis/context"
if ($code -eq 401) { Pass "无令牌访问 /api/gis/context -> 401" } else { Fail "无令牌期望 401 实得 $code" }

$badLogin = '{"username":"admin","password":"wrong-password-xyz"}'
$code = Get-Status POST "$JavaUrl/api/auth/login" @{} $badLogin
if ($code -eq 401) { Pass "错误密码登录 -> 401" } else { Fail "错误密码期望 401 实得 $code" }

$token = $null
$loginContent = Invoke-Json POST "$JavaUrl/api/auth/login" @{} (@{ username = $AdminUsername; password = $AdminPassword } | ConvertTo-Json -Compress)
try {
    $login = $loginContent | ConvertFrom-Json
    if ($login.token -and $login.role -eq "ADMIN") {
        $token = $login.token
        Pass "管理员登录拿到 JWT (role=$($login.role))"
    } else { Fail "登录响应缺少 token/ADMIN" }
} catch { Fail "登录解析失败: $loginContent" }

$headers = @{ Authorization = "Bearer $token" }
if ($token) {
    $meContent = Invoke-Json GET "$JavaUrl/api/auth/me" $headers
    try {
        $me = $meContent | ConvertFrom-Json
        if ($me.username -eq $AdminUsername) { Pass "/api/auth/me 返回 $($me.username) (role=$($me.role))" } else { Fail "/api/auth/me 期望 $AdminUsername 实得 $($me.username)" }
    } catch { Fail "/api/auth/me 解析失败: $meContent" }
}

# ---------------------------------------------------------------------------
# 2. RBAC
# ---------------------------------------------------------------------------
Write-Host "`n== RBAC ==" -ForegroundColor Cyan

$code = Get-Status POST "$JavaUrl/api/knowledge/reload" @{} '{}'
if ($code -eq 401) { Pass "无令牌访问 /api/knowledge/reload -> 401" } else { Fail "无令牌期望 401 实得 $code" }

if ($token) {
    $code = Get-Status POST "$JavaUrl/api/knowledge/reload" $headers '{}' 40
    if ($code -eq 200) { Pass "管理员访问 /api/knowledge/reload -> 200" } else { Fail "管理员期望 200 实得 $code" }
}

# ---------------------------------------------------------------------------
# 3. 上传深度格式校验
# ---------------------------------------------------------------------------
Write-Host "`n== 上传安全 ==" -ForegroundColor Cyan
if ($token) {
    $tmpPdf = Join-Path $env:TEMP "fake-$(Get-Random).pdf"
    "this is not a real pdf, just text content" | Set-Content -LiteralPath $tmpPdf -Encoding UTF8
    $pdfCode = & curl.exe -sk -o - -w "|%{http_code}" -X POST "$JavaUrl/api/knowledge/upload" -H "Authorization: Bearer $token" -F "file=@$tmpPdf" 2>$null
    if ($pdfCode -match 'pdf_magic_mismatch' -and $pdfCode -match '\|413') {
        Pass "伪造 PDF (文本改后缀) 被深度格式校验拦截 (413 pdf_magic_mismatch)"
    } else {
        Fail "伪造 PDF 期望 413/pdf_magic_mismatch 实得 $pdfCode"
    }
    Remove-Item -LiteralPath $tmpPdf -ErrorAction SilentlyContinue

    $tmpMd = Join-Path $env:TEMP "notes-$(Get-Random).md"
    "# Title`nSome markdown text" | Set-Content -LiteralPath $tmpMd -Encoding UTF8
    $mdResp = & curl.exe -sk -o - -w "|%{http_code}" -X POST "$JavaUrl/api/knowledge/upload" -H "Authorization: Bearer $token" -F "file=@$tmpMd" 2>$null
    if ($mdResp -match '\|200' -and $mdResp -match 'success') {
        Pass "合法 .md 上传通过 -> 200 success"
    } else {
        Fail "合法 .md 上传异常: $mdResp"
    }
    Remove-Item -LiteralPath $tmpMd -ErrorAction SilentlyContinue
} else {
    Fail "缺少 token，跳过上传检查"
}

# ---------------------------------------------------------------------------
# 4. /analysis 代理
# ---------------------------------------------------------------------------
Write-Host "`n== /analysis 代理 ==" -ForegroundColor Cyan

$code = Get-Status GET "$JavaUrl/analysis/runtime"
if ($code -eq 401) { Pass "/analysis/runtime 无令牌 -> 401" } else { Fail "/analysis/runtime 无令牌期望 401 实得 $code" }

if ($token) {
    $runtimeRaw = Invoke-Json GET "$JavaUrl/analysis/runtime" $headers $null 20
    try {
        $runtime = $runtimeRaw | ConvertFrom-Json
        if ($runtime.status -eq "Success") { Pass "/analysis/runtime 有令牌 -> 200 (backend=$($runtime.preferred_backend))" } else { Fail "/analysis/runtime 返回 $($runtime.status)" }
    } catch { Fail "/analysis/runtime 解析失败: $runtimeRaw" }
}

# ---------------------------------------------------------------------------
# 5. 全局限流（可选）
# ---------------------------------------------------------------------------
if ($TestRateLimit) {
    Write-Host "`n== 限流 (阈值=$RateLimitMax) ==" -ForegroundColor Cyan
    $got429 = $false
    for ($i = 1; $i -le $RateLimitChecks; $i++) {
        $code = Get-Status GET "$JavaUrl/api/gis/context"
        if ($code -eq 429) { $got429 = $true; break }
    }
    if ($got429) { Pass "窗口内超过阈值后返回 429" } else { Fail "未观察到 429（确认后端已用 AUTH_RATE_LIMIT_MAX=$RateLimitMax 启动）" }
}

Write-Host "`n验收结果: $passed passed, $failed failed"
if ($failed -gt 0) { exit 1 } else { Write-Host "安全加固验收全部通过。" -ForegroundColor Green }