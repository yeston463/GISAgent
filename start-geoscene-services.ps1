param(
    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'
$LogFile = Join-Path $PSScriptRoot 'geoscene-services.log'
$TomcatRoot = 'C:\apache-tomcat-9.0.119'
$TomcatStartup = Join-Path $TomcatRoot 'bin\startup.bat'
$TomcatHttpsPort = 443
$ServiceNames = @(
    'GeoScene Data Store',
    'GeoScene Portal',
    'GeoScene Server'
)

function Write-Log {
    param([string]$Message)

    Add-Content -LiteralPath $LogFile -Encoding UTF8 -Value (
        '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    )
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-LocalPort {
    param([int]$Port)

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if (-not (Test-Administrator)) {
    if ($Elevated) {
        Write-Log 'Elevated process still lacks administrator privileges.'
        Write-Host '[ERROR] Administrator privileges were not granted.' -ForegroundColor Red
        exit 1
    }

    Write-Log 'Requesting administrator privileges.'
    Write-Host 'Requesting administrator privileges ...' -ForegroundColor Yellow

    $powerShellExe = Join-Path $PSHOME 'powershell.exe'
    $arguments = @(
        '-NoLogo',
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $PSCommandPath),
        '-Elevated'
    )

    try {
        $process = Start-Process `
            -FilePath $powerShellExe `
            -ArgumentList $arguments `
            -WorkingDirectory $PSScriptRoot `
            -Verb RunAs `
            -PassThru `
            -Wait
        Write-Log ('Administrator process exited with code {0}.' -f $process.ExitCode)
        exit $process.ExitCode
    } catch {
        Write-Log ('UAC launch failed: {0}' -f $_.Exception.Message)
        Write-Host ('[ERROR] UAC launch failed: {0}' -f $_.Exception.Message) -ForegroundColor Red
        exit 1
    }
}

Write-Log 'Administrator process started.'
Write-Host ''
Write-Host 'Starting GeoScene services ...' -ForegroundColor Cyan
Write-Host ('Log: {0}' -f $LogFile)
Write-Host ''

$failed = 0
foreach ($serviceName in $ServiceNames) {
    try {
        $service = Get-Service -Name $serviceName -ErrorAction Stop
        if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
            Write-Host ('[OK]    {0} is already running.' -f $serviceName) -ForegroundColor Green
            Write-Log ('Already running: {0}' -f $serviceName)
            continue
        }

        Write-Host ('[START] {0} ...' -f $serviceName) -ForegroundColor Yellow
        Write-Log ('Starting: {0}' -f $serviceName)
        Start-Service -Name $serviceName -ErrorAction Stop
        $service.WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Running,
            [TimeSpan]::FromSeconds(180)
        )
        $service.Refresh()

        if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
            throw 'Service did not reach RUNNING state.'
        }

        Write-Host ('[OK]    {0} is running.' -f $serviceName) -ForegroundColor Green
        Write-Log ('Running: {0}' -f $serviceName)
    } catch {
        $failed++
        Write-Host ('[ERROR] {0}: {1}' -f $serviceName, $_.Exception.Message) -ForegroundColor Red
        Write-Log ('Failed: {0}: {1}' -f $serviceName, $_.Exception.Message)
        $details = & sc.exe queryex $serviceName 2>&1 | Out-String
        Add-Content -LiteralPath $LogFile -Encoding UTF8 -Value $details.TrimEnd()
    }
}

try {
    if (-not (Test-Path -LiteralPath $TomcatStartup -PathType Leaf)) {
        throw "Tomcat startup script not found: $TomcatStartup"
    }

    if (Test-LocalPort -Port $TomcatHttpsPort) {
        Write-Host ('[OK]    Tomcat is already listening on HTTPS port {0}.' -f $TomcatHttpsPort) -ForegroundColor Green
        Write-Log ('Tomcat already listening: {0}, port {1}' -f $TomcatRoot, $TomcatHttpsPort)
    } else {
        Write-Host ('[START] Tomcat {0} ...' -f $TomcatRoot) -ForegroundColor Yellow
        Write-Log ('Starting Tomcat: {0}' -f $TomcatStartup)
        $env:CATALINA_HOME = $TomcatRoot
        $env:CATALINA_BASE = $TomcatRoot
        & $TomcatStartup | ForEach-Object { Write-Host $_; Write-Log ('Tomcat: {0}' -f $_) }
        if ($LASTEXITCODE -ne 0) {
            throw "Tomcat startup.bat exited with code $LASTEXITCODE."
        }

        $tomcatReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if (Test-LocalPort -Port $TomcatHttpsPort) {
                $tomcatReady = $true
                break
            }
            Start-Sleep -Seconds 1
        }
        if (-not $tomcatReady) {
            throw "Tomcat did not listen on HTTPS port $TomcatHttpsPort within 60 seconds."
        }

        Write-Host ('[OK]    Tomcat is listening on HTTPS port {0}.' -f $TomcatHttpsPort) -ForegroundColor Green
        Write-Log ('Tomcat listening: {0}, port {1}' -f $TomcatRoot, $TomcatHttpsPort)
    }
} catch {
    $failed++
    Write-Host ('[ERROR] Tomcat: {0}' -f $_.Exception.Message) -ForegroundColor Red
    Write-Log ('Failed: Tomcat: {0}' -f $_.Exception.Message)
    $tomcatLog = Join-Path $TomcatRoot 'logs\catalina.log'
    if (Test-Path -LiteralPath $tomcatLog -PathType Leaf) {
        Add-Content -LiteralPath $LogFile -Encoding UTF8 -Value (
            Get-Content -LiteralPath $tomcatLog -Tail 80 | Out-String
        ).TrimEnd()
    }
}

Write-Host ''
Write-Host 'GeoScene service status:' -ForegroundColor Cyan
foreach ($serviceName in $ServiceNames) {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    $status = if ($service) { $service.Status } else { 'NOT_FOUND' }
    Write-Host ('  {0}: {1}' -f $serviceName, $status)
    Write-Log ('Final status: {0}: {1}' -f $serviceName, $status)
}
$tomcatStatus = if (Test-LocalPort -Port $TomcatHttpsPort) { 'LISTENING' } else { 'NOT_LISTENING' }
Write-Host ('  Tomcat {0} (HTTPS {1}): {2}' -f $TomcatRoot, $TomcatHttpsPort, $tomcatStatus)
Write-Log ('Final status: Tomcat {0}, HTTPS {1}: {2}' -f $TomcatRoot, $TomcatHttpsPort, $tomcatStatus)

if ($failed -gt 0) {
    Write-Log ('Startup completed with {0} failure(s).' -f $failed)
    Write-Host ''
    Write-Host ('[ERROR] {0} service(s) failed. See log.' -f $failed) -ForegroundColor Red
    exit 1
}

Write-Log 'All requested GeoScene services and Tomcat are running.'
Write-Host ''
Write-Host 'All requested GeoScene services and Tomcat are running.' -ForegroundColor Green
exit 0
