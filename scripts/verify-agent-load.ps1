[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(1, 10)] [int]$Concurrency = 3,
    [ValidateRange(2, 60)] [int]$Requests = 9,
    [ValidateRange(10, 120)] [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'

$worker = {
    param($apiBaseUrl, $index, $timeoutSeconds)
    $message = if ($index % 2 -eq 0) { '获取DEM' } else { '进行洪水分析' }
    $memoryId = "load-$index-$([Guid]::NewGuid().ToString('N'))"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $payload = @{ message = $message; memoryId = $memoryId } | ConvertTo-Json -Compress
        $response = Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec $timeoutSeconds `
            -Uri "$apiBaseUrl/api/agent/chat/agentic" -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes($payload))
        $body = $response.Content | ConvertFrom-Json
        [PSCustomObject]@{
            request = $message
            status = $body.outcome.status
            analysisType = $body.outcome.analysisType
            elapsedMs = [Math]::Round($watch.Elapsed.TotalMilliseconds)
            error = $null
        }
    } catch {
        [PSCustomObject]@{
            request = $message
            status = 'Error'
            analysisType = $null
            elapsedMs = [Math]::Round($watch.Elapsed.TotalMilliseconds)
            error = $_.Exception.Message
        }
    } finally {
        $watch.Stop()
    }
}

$jobs = @()
$results = @()
for ($index = 0; $index -lt $Requests; $index++) {
    $jobs += Start-Job -ScriptBlock $worker -ArgumentList $ApiBaseUrl, $index, $TimeoutSeconds
    if ($jobs.Count -ge $Concurrency -or $index -eq ($Requests - 1)) {
        $jobs | Wait-Job | Out-Null
        $results += $jobs | Receive-Job
        $jobs | Remove-Job -Force
        $jobs = @()
    }
}

$invalid = @($results | Where-Object {
    ($_.request -eq '获取DEM' -and ($_.status -ne 'Success' -or $_.analysisType -ne 'ground_dem_request')) -or
    ($_.request -eq '进行洪水分析' -and ($_.status -notin @('Success', 'NeedsClarification') -or $_.analysisType -ne 'flood_analysis'))
})
$latencies = @($results | Where-Object { $_.status -ne 'Error' } | ForEach-Object elapsedMs | Sort-Object)
$p95 = if ($latencies.Count) { $latencies[[Math]::Min($latencies.Count - 1, [Math]::Ceiling($latencies.Count * 0.95) - 1)] } else { 0 }

$results | Format-Table request, status, analysisType, elapsedMs, error -AutoSize
Write-Host "Load result: $($results.Count) requests, concurrency=$Concurrency, p95=$p95 ms"
if ($invalid.Count -gt 0) {
    Write-Error "Agent load verification failed for $($invalid.Count) request(s)."
    exit 1
}
