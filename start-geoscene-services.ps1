param(
    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'
$LogFile = Join-Path $PSScriptRoot 'geoscene-services.log'
$TomcatRoot = 'C:\apache-tomcat-9.0.119'
$TomcatStartup = Join-Path $TomcatRoot 'bin\startup.bat'
$TomcatHttpsPort = 443
$TomcatServiceName = 'GeoSceneTomcatAutostart'
$PortalHealthUrl = 'http://127.0.0.1:7080/geoscene/portaladmin/healthCheck?f=json'
$WebAdaptorHost = 'product.geosceneenterprise.cn'
$WebAdaptorHealthUrl = 'https://127.0.0.1/geoscene/sharing/rest?f=json'
$ServerWebAdaptorHealthUrl = 'https://127.0.0.1/server/rest/services?f=json'
$MinimumSystemDriveFreeGB = 12
$MinimumCommitHeadroomGB = 3
$MinimumAvailableMemoryGB = 1.5
$PortalWarmupGraceSeconds = 300
$PortalColdStartWaitSeconds = 180
$PortalFailureProbeCount = 3
$PortalFailureProbeIntervalSeconds = 5
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

function Stop-ManagedService {
    param(
        [string]$Name,
        [int]$TimeoutSeconds = 45
    )

    $service = Get-Service -Name $Name -ErrorAction Stop
    if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Stopped) {
        return
    }

    Write-Log ('Stopping service with {0}s timeout: {1}' -f $TimeoutSeconds, $Name)
    try {
        $service.Stop()
        $service.WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds($TimeoutSeconds)
        )
    } catch {
        Write-Log ('Graceful stop timed out for {0}: {1}' -f $Name, $_.Exception.Message)
    }

    $service.Refresh()
    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        $escapedName = $Name.Replace("'", "''")
        $serviceInfo = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $escapedName)
        if ($serviceInfo.ProcessId -le 0) {
            throw ('Service {0} did not stop and has no process ID to terminate.' -f $Name)
        }

        Write-Host ('[FORCE] Service {0} did not stop; terminating PID {1} ...' -f $Name, $serviceInfo.ProcessId) -ForegroundColor Yellow
        Write-Log ('Force-terminating service tree: {0}, PID {1}' -f $Name, $serviceInfo.ProcessId)
        & taskkill.exe /PID $serviceInfo.ProcessId /T /F 2>&1 |
            ForEach-Object { Write-Log ('taskkill: {0}' -f $_) }

        $deadline = (Get-Date).AddSeconds(30)
        do {
            Start-Sleep -Seconds 1
            $service.Refresh()
        } while ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped -and (Get-Date) -lt $deadline)
    }

    if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        throw ('Service {0} did not reach STOPPED state.' -f $Name)
    }
}

function Restart-ManagedService {
    param(
        [string]$Name,
        [int]$StopTimeoutSeconds = 45,
        [int]$StartTimeoutSeconds = 60,
        [double]$RequiredCommitHeadroomGB = $MinimumCommitHeadroomGB,
        [double]$RequiredAvailableMemoryGB = $MinimumAvailableMemoryGB
    )

    Stop-ManagedService -Name $Name -TimeoutSeconds $StopTimeoutSeconds
    Assert-StartupResources -Operation ('starting {0} after restart' -f $Name) `
        -RequiredCommitHeadroomGB $RequiredCommitHeadroomGB `
        -RequiredAvailableMemoryGB $RequiredAvailableMemoryGB
    Write-Log ('Starting service after bounded stop: {0}' -f $Name)
    Start-Service -Name $Name -ErrorAction Stop
    $service = Get-Service -Name $Name -ErrorAction Stop
    $service.WaitForStatus(
        [ServiceProcess.ServiceControllerStatus]::Running,
        [TimeSpan]::FromSeconds($StartTimeoutSeconds)
    )
}

function Test-HttpContent {
    param(
        [string]$Url,
        [string]$Expected,
        [switch]$Insecure,
        [string]$Resolve,
        [int]$TimeoutSeconds = 5
    )

    $previousCertificateCallback = [Net.ServicePointManager]::ServerCertificateValidationCallback
    try {
        if ($Insecure) {
            [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
        }
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.Proxy = $null
        $request.Timeout = $TimeoutSeconds * 1000
        $request.ReadWriteTimeout = $TimeoutSeconds * 1000
        $response = $request.GetResponse()
        try {
            $reader = [IO.StreamReader]::new($response.GetResponseStream())
            try {
                $content = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        } finally {
            $response.Dispose()
        }
        return $content.Contains($Expected)
    } catch {
        return $false
    } finally {
        [Net.ServicePointManager]::ServerCertificateValidationCallback = $previousCertificateCallback
    }
}

function Wait-HttpContent {
    param(
        [string]$Url,
        [string]$Expected,
        [int]$TimeoutSeconds,
        [switch]$Insecure,
        [string]$Resolve
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $attempt = 0
    do {
        $attempt++
        if (Test-HttpContent -Url $Url -Expected $Expected -Insecure:$Insecure -Resolve $Resolve -TimeoutSeconds 5) {
            return $true
        }
        if ($attempt -eq 1 -or $attempt % 3 -eq 0) {
            Write-Host ('[WAIT] Health check pending ({0}s remaining): {1}' -f `
                [Math]::Max(0, [Math]::Round(($deadline - (Get-Date)).TotalSeconds)), $Url) -ForegroundColor Yellow
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-ServiceUptimeSeconds {
    param([string]$Name)

    try {
        $escapedName = $Name.Replace("'", "''")
        $serviceInfo = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $escapedName)
        if (-not $serviceInfo -or $serviceInfo.ProcessId -le 0) {
            return 0
        }
        $process = Get-Process -Id $serviceInfo.ProcessId -ErrorAction Stop
        return [Math]::Max(0, ((Get-Date) - $process.StartTime).TotalSeconds)
    } catch {
        Write-Log ('Could not determine service uptime for {0}: {1}' -f $Name, $_.Exception.Message)
        return 0
    }
}

function Test-ConsecutiveHealth {
    param(
        [string]$Url,
        [string]$Expected,
        [int]$Attempts,
        [int]$IntervalSeconds
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if (Test-HttpContent -Url $Url -Expected $Expected -TimeoutSeconds 5) {
            return $true
        }
        Write-Host ('[CHECK] Health probe {0}/{1} failed.' -f $attempt, $Attempts) -ForegroundColor Yellow
        Write-Log ('Health probe {0}/{1} failed: {2}' -f $attempt, $Attempts, $Url)
        if ($attempt -lt $Attempts) {
            Start-Sleep -Seconds $IntervalSeconds
        }
    }
    return $false
}

function Wait-WebAdaptors {
    param([int]$TimeoutSeconds = 90)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $portalHealthy = Test-HttpContent -Url $WebAdaptorHealthUrl `
            -Expected 'geosceneVersion' -Insecure -Resolve $WebAdaptorHost -TimeoutSeconds 5
        $serverHealthy = Test-HttpContent -Url $ServerWebAdaptorHealthUrl `
            -Expected 'currentVersion' -Insecure -Resolve $WebAdaptorHost -TimeoutSeconds 5
        if ($portalHealthy -and $serverHealthy) {
            return $true
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    Write-Log ('Web Adaptor wait timed out: Portal={0}, Server={1}' -f $portalHealthy, $serverHealthy)
    return $false
}

function Get-ResourceSnapshot {
    $systemDrive = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $samples = (Get-Counter @(
        '\Memory\Committed Bytes',
        '\Memory\Commit Limit',
        '\Memory\Available MBytes'
    )).CounterSamples
    $committed = ($samples | Where-Object { $_.Path -like '*committed bytes' }).CookedValue
    $commitLimit = ($samples | Where-Object { $_.Path -like '*commit limit' }).CookedValue
    $availableMB = ($samples | Where-Object { $_.Path -like '*available mbytes' }).CookedValue

    return [PSCustomObject]@{
        SystemDriveFreeGB = [Math]::Round($systemDrive.FreeSpace / 1GB, 2)
        CommitHeadroomGB = [Math]::Round(($commitLimit - $committed) / 1GB, 2)
        AvailableMemoryGB = [Math]::Round($availableMB / 1024, 2)
    }
}

function Write-ResourceStatus {
    $snapshot = Get-ResourceSnapshot
    $message = 'Resources: C free {0} GB, commit headroom {1} GB, available memory {2} GB.' -f `
        $snapshot.SystemDriveFreeGB,
        $snapshot.CommitHeadroomGB,
        $snapshot.AvailableMemoryGB
    Write-Host $message
    Write-Log $message
    return $snapshot
}

function Assert-StartupResources {
    param(
        [string]$Operation,
        [double]$RequiredCommitHeadroomGB = $MinimumCommitHeadroomGB,
        [double]$RequiredAvailableMemoryGB = $MinimumAvailableMemoryGB
    )

    $snapshot = Write-ResourceStatus
    if ($snapshot.SystemDriveFreeGB -lt $MinimumSystemDriveFreeGB) {
        throw ('Cannot continue {0}: C: requires at least {1} GB free, but only {2} GB is available.' -f `
            $Operation, $MinimumSystemDriveFreeGB, $snapshot.SystemDriveFreeGB)
    }
    if ($snapshot.CommitHeadroomGB -lt $RequiredCommitHeadroomGB -or `
        $snapshot.AvailableMemoryGB -lt $RequiredAvailableMemoryGB) {
        $message = 'Cannot continue {0}: close Docker Desktop/WSL or other memory-heavy programs first. Required: {1} GB commit headroom and {2} GB available memory.' -f `
            $Operation, $RequiredCommitHeadroomGB, $RequiredAvailableMemoryGB
        throw $message
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
$null = Write-ResourceStatus

$failed = 0
$serviceStartedThisRun = @{}
foreach ($serviceName in $ServiceNames) {
    try {
        $service = Get-Service -Name $serviceName -ErrorAction Stop
        $serviceStartedThisRun[$serviceName] = $false
        if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
            Write-Host ('[OK]    {0} is already running.' -f $serviceName) -ForegroundColor Green
            Write-Log ('Already running: {0}' -f $serviceName)
            continue
        }

        Write-Host ('[START] {0} ...' -f $serviceName) -ForegroundColor Yellow
        Write-Log ('Starting: {0}' -f $serviceName)
        Assert-StartupResources -Operation ('starting {0}' -f $serviceName)
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
        $serviceStartedThisRun[$serviceName] = $true
    } catch {
        $failed++
        Write-Host ('[ERROR] {0}: {1}' -f $serviceName, $_.Exception.Message) -ForegroundColor Red
        Write-Log ('Failed: {0}: {1}' -f $serviceName, $_.Exception.Message)
        $details = & sc.exe queryex $serviceName 2>&1 | Out-String
        Add-Content -LiteralPath $LogFile -Encoding UTF8 -Value $details.TrimEnd()
    }
}

$portalReady = $false
try {
    $portalStartedThisRun = $serviceStartedThisRun['GeoScene Portal'] -eq $true
    $portalUptimeSeconds = Get-ServiceUptimeSeconds -Name 'GeoScene Portal'
    $portalInWarmup = $portalStartedThisRun -or $portalUptimeSeconds -lt $PortalWarmupGraceSeconds

    if ($portalInWarmup) {
        Write-Host '[WAIT] GeoScene Portal is in cold-start warmup; automatic restart is disabled.' -ForegroundColor Yellow
        Write-Log ('Portal warmup only: startedThisRun={0}, uptimeSeconds={1:N0}. No restart allowed.' -f `
            $portalStartedThisRun, $portalUptimeSeconds)
        $portalReady = Wait-HttpContent -Url $PortalHealthUrl -Expected '"status":"success"' `
            -TimeoutSeconds $PortalColdStartWaitSeconds
        if (-not $portalReady) {
            throw ('Portal is still warming up after {0} seconds; it was left running and was not restarted.' -f `
                $PortalColdStartWaitSeconds)
        }
    } else {
        $portalReady = Test-ConsecutiveHealth -Url $PortalHealthUrl -Expected '"status":"success"' `
            -Attempts $PortalFailureProbeCount -IntervalSeconds $PortalFailureProbeIntervalSeconds
        if (-not $portalReady) {
            Write-Host '[RESTART] Long-running Portal failed all health probes ...' -ForegroundColor Yellow
            Write-Log ('Long-running Portal failed {0} consecutive probes; restart allowed.' -f $PortalFailureProbeCount)
            Restart-ManagedService -Name 'GeoScene Portal'
            $portalReady = Wait-HttpContent -Url $PortalHealthUrl -Expected '"status":"success"' `
                -TimeoutSeconds $PortalColdStartWaitSeconds
            if (-not $portalReady) {
                throw ('Portal health check did not recover within {0} seconds after restart.' -f `
                    $PortalColdStartWaitSeconds)
            }
        }
    }
    Write-Host '[OK]    GeoScene Portal health check passed.' -ForegroundColor Green
    Write-Log 'Portal health check passed.'
} catch {
    $failed++
    Write-Host ('[ERROR] GeoScene Portal health: {0}' -f $_.Exception.Message) -ForegroundColor Red
    Write-Log ('Failed: GeoScene Portal health: {0}' -f $_.Exception.Message)
}

if (-not $portalReady) {
    Write-Host '[SKIP]  Tomcat/Web Adaptor left unchanged because Portal is still warming up.' -ForegroundColor Yellow
    Write-Log 'Skipped Tomcat/Web Adaptor changes because Portal is not ready.'
} else {
  try {
    if (-not (Test-Path -LiteralPath $TomcatStartup -PathType Leaf)) {
        throw "Tomcat startup script not found: $TomcatStartup"
    }

    $portalAdaptorHealthy = Test-HttpContent -Url $WebAdaptorHealthUrl -Expected 'geosceneVersion' -Insecure -Resolve $WebAdaptorHost
    $serverAdaptorHealthy = Test-HttpContent -Url $ServerWebAdaptorHealthUrl -Expected 'currentVersion' -Insecure -Resolve $WebAdaptorHost
    if ($portalAdaptorHealthy -and $serverAdaptorHealthy) {
        Write-Host '[OK]    Portal and Server Web Adaptor health checks passed.' -ForegroundColor Green
        Write-Log 'Portal and Server Web Adaptor health checks passed.'
    } else {
        if (Test-LocalPort -Port $TomcatHttpsPort) {
            Write-Host '[RESTART] Tomcat is listening but Web Adaptor is unhealthy ...' -ForegroundColor Yellow
            Write-Log 'Tomcat port is open but Web Adaptor health failed; terminating the stale instance.'
            $tomcatPids = Get-NetTCPConnection -State Listen -LocalPort $TomcatHttpsPort -ErrorAction Stop |
                Select-Object -ExpandProperty OwningProcess -Unique
            foreach ($tomcatPid in $tomcatPids) {
                Write-Log ('Stopping stale Tomcat PID {0}.' -f $tomcatPid)
                Stop-Process -Id $tomcatPid -Force -ErrorAction Stop
            }
            for ($attempt = 0; $attempt -lt 30 -and (Test-LocalPort -Port $TomcatHttpsPort); $attempt++) {
                Start-Sleep -Seconds 1
            }
            if (Test-LocalPort -Port $TomcatHttpsPort) {
                throw 'Stale Tomcat process did not release HTTPS port 443.'
            }
        }

        $tomcatService = Get-Service -Name $TomcatServiceName -ErrorAction SilentlyContinue
        $tomcatStartedByService = $false
        if ($tomcatService) {
            try {
                if ($tomcatService.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
                    Restart-ManagedService -Name $TomcatServiceName `
                        -RequiredCommitHeadroomGB 0.75 -RequiredAvailableMemoryGB 0.75
                } else {
                    Assert-StartupResources -Operation 'starting Tomcat/Web Adaptor' `
                        -RequiredCommitHeadroomGB 0.75 -RequiredAvailableMemoryGB 0.75
                    Start-Service -Name $TomcatServiceName -ErrorAction Stop
                }
                $tomcatStartedByService = $true
                Write-Host ('[START] Tomcat Windows service {0} ...' -f $TomcatServiceName) -ForegroundColor Yellow
                Write-Log ('Started Tomcat Windows service: {0}' -f $TomcatServiceName)
            } catch {
                Write-Host '[WARN] Tomcat Windows service failed; using startup.bat fallback.' -ForegroundColor Yellow
                Write-Log ('Tomcat Windows service failed; using startup.bat fallback: {0}' -f $_.Exception.Message)
            }
        }

        if (-not $tomcatStartedByService) {
            Assert-StartupResources -Operation 'starting Tomcat/Web Adaptor' `
                -RequiredCommitHeadroomGB 0.75 -RequiredAvailableMemoryGB 0.75
            Write-Host ('[START] Tomcat {0} ...' -f $TomcatRoot) -ForegroundColor Yellow
            Write-Log ('Starting Tomcat: {0}' -f $TomcatStartup)
            $env:CATALINA_HOME = $TomcatRoot
            $env:CATALINA_BASE = $TomcatRoot
            & $TomcatStartup | ForEach-Object { Write-Host $_; Write-Log ('Tomcat: {0}' -f $_) }
            if ($LASTEXITCODE -ne 0) {
                throw "Tomcat startup.bat exited with code $LASTEXITCODE."
            }
        }

        if (-not (Wait-WebAdaptors -TimeoutSeconds 90)) {
            throw 'Portal/Server Web Adaptor did not become healthy within the shared 90-second timeout.'
        }
        Write-Host '[OK]    Portal and Server Web Adaptor health checks passed.' -ForegroundColor Green
        Write-Log 'Portal and Server Web Adaptor health checks passed after restart.'
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
