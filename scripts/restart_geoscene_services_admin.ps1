$ErrorActionPreference = 'Stop'

$logDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'work-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir ('geoscene-admin-restart-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

function Write-Step {
    param([string]$Message)
    $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function Wait-ServiceStatus {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Status,
        [int]$TimeoutSeconds = 180
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $svc = Get-Service -Name $Name
        Write-Step ('{0}: {1}' -f $Name, $svc.Status)
        if ($svc.Status.ToString() -eq $Status) {
            return
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw ('Timed out waiting for {0} to become {1}.' -f $Name, $Status)
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Please run this script from an elevated Administrator PowerShell.'
}

Write-Step 'Stopping GeoScene Server...'
Stop-Service -Name 'GeoScene Server' -Force
Wait-ServiceStatus -Name 'GeoScene Server' -Status 'Stopped' -TimeoutSeconds 180

Write-Step 'Restarting GeoScene Data Store...'
Restart-Service -Name 'GeoScene Data Store' -Force
Wait-ServiceStatus -Name 'GeoScene Data Store' -Status 'Running' -TimeoutSeconds 240

Write-Step 'Starting GeoScene Server...'
Start-Service -Name 'GeoScene Server'
Wait-ServiceStatus -Name 'GeoScene Server' -Status 'Running' -TimeoutSeconds 240

Write-Step 'Final service status:'
Get-Service -Name 'GeoScene Portal','GeoScene Server','GeoScene Data Store' |
    Select-Object Name,Status,StartType |
    Format-Table -AutoSize |
    Out-String |
    Tee-Object -FilePath $logFile -Append

Write-Step ('Done. Log: {0}' -f $logFile)
