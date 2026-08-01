param(
    [switch]$Elevated,
    [switch]$RestartServer,
    [switch]$RestartDataStore,
    [switch]$RestartPortal,
    [switch]$PinLocalHostname,
    [switch]$AllowServerWhileDataStoreRecovering
)

$ErrorActionPreference = 'Stop'
$LogFile = Join-Path $PSScriptRoot 'geoscene-services.log'
$TomcatRoot = 'C:\apache-tomcat-9.0.119'
$TomcatStartup = Join-Path $TomcatRoot 'bin\startup.bat'
$TomcatHttpsPort = 443
$TomcatServiceName = 'GeoSceneTomcatAutostart'
$DataStoreDescribeTool = 'C:\Program Files\GeoScene\DataStore\tools\describedatastore.bat'
$DataStoreOzoneLog = 'C:\geoscenedatastore\logs\PRODUCT.GEOSCENEENTERPRISE.CN\ozone\ozone.log'
$DataStoreOzoneConfig = 'C:\geoscenedatastore\ozonedata\etc\hadoop\ozone-site.xml'
$PortalEnvFile = Join-Path $PSScriptRoot '.env'
$PortalHealthUrl = 'http://127.0.0.1:7080/geoscene/portaladmin/healthCheck?f=json'
$WebAdaptorHost = 'product.geosceneenterprise.cn'
$WebAdaptorHealthUrl = 'https://127.0.0.1/geoscene/sharing/rest?f=json'
$ServerWebAdaptorHealthUrl = 'https://127.0.0.1/server/rest/services?f=json'
$ServerAdminBaseUrl = 'https://product.geosceneenterprise.cn:6443/geoscene/admin'
$MinimumSystemDriveFreeGB = 12
$MinimumCommitHeadroomGB = 3
$MinimumAvailableMemoryGB = 1.5
$PortalWarmupGraceSeconds = 300
$PortalColdStartWaitSeconds = 180
$PortalFailureProbeCount = 3
$PortalFailureProbeIntervalSeconds = 5
$DataStoreWarmupSeconds = 360
$DataStoreProbeIntervalSeconds = 15
$DataStoreOzoneQuietSeconds = 60
$DataStoreOzoneQuietTimeoutSeconds = 360
$ObjectStoreValidateTimeoutSeconds = 180
$ObjectStoreValidateIntervalSeconds = 15
$GeoSceneHostname = 'product.geosceneenterprise.cn'
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

function Get-DotEnvValue {
    param([string]$Name)

    $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }

    if (-not (Test-Path -LiteralPath $PortalEnvFile -PathType Leaf)) {
        return $null
    }

    foreach ($line in (Get-Content -LiteralPath $PortalEnvFile -ErrorAction SilentlyContinue)) {
        if ($line -match '^\s*' + [regex]::Escape($Name) + '\s*=\s*(?<value>.*)\s*$') {
            return $Matches['value'].Trim().Trim('"').Trim("'")
        }
    }
    return $null
}

function Get-OzoneConfigDiagnosis {
    if (-not (Test-Path -LiteralPath $DataStoreOzoneConfig -PathType Leaf)) {
        return $null
    }

    try {
        [xml]$document = Get-Content -LiteralPath $DataStoreOzoneConfig -Raw -ErrorAction Stop
    } catch {
        throw ('Could not read Ozone config {0}: {1}' -f $DataStoreOzoneConfig, $_.Exception.Message)
    }

    $properties = @{}
    foreach ($property in @($document.configuration.property)) {
        $name = [string]$property.name
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        $properties[$name.Trim()] = ([string]$property.value).Trim()
    }

    $policy = $properties['ozone.http.policy']
    $issues = [System.Collections.Generic.List[string]]::new()
    if ($policy -eq 'HTTPS_ONLY') {
        foreach ($flagName in @(
            'hdds.datanode.http.enabled',
            'ozone.scm.http.enabled',
            'ozone.om.http.enabled'
        )) {
            $flagValue = ''
            if ($properties.ContainsKey($flagName)) {
                $flagValue = $properties[$flagName]
            }
            if ($flagValue.ToLowerInvariant() -ne 'true') {
                $issues.Add(('{0}={1}' -f $flagName, $(if ($flagValue) { $flagValue } else { '<missing>' })))
            }
        }
    }

    return [PSCustomObject]@{
        Path = $DataStoreOzoneConfig
        Policy = $policy
        Issues = $issues.ToArray()
        IsValid = $issues.Count -eq 0
    }
}

function Assert-OzoneConfigReady {
    $diagnosis = Get-OzoneConfigDiagnosis
    if ($null -eq $diagnosis) {
        Write-Log ('Ozone config file not found; skip config validation: {0}' -f $DataStoreOzoneConfig)
        return
    }
    if ($diagnosis.IsValid) {
        return
    }

    $issueSummary = $diagnosis.Issues -join ', '
    throw ((
        'GeoScene Data Store Ozone config is inconsistent: ozone.http.policy={0} but {1}. ' +
        'Fix {2} and retry (set the affected *.http.enabled flags to true before restarting Data Store).'
    ) -f $diagnosis.Policy, $issueSummary, $diagnosis.Path)
}

function Get-ObjectStoreDescriptor {
    $objectStoreId = Get-DotEnvValue -Name 'GEOSCENE_OBJECT_STORE_ID'
    $objectStoreMachine = Get-DotEnvValue -Name 'GEOSCENE_OBJECT_STORE_MACHINE'
    if (-not [string]::IsNullOrWhiteSpace($objectStoreId) -and `
        -not [string]::IsNullOrWhiteSpace($objectStoreMachine)) {
        return [PSCustomObject]@{
            Id = $objectStoreId
            Machine = $objectStoreMachine
        }
    }

    $description = Invoke-DataStoreDescription
    $objectStoreMatch = [regex]::Match(
        $description,
        '(?im)object store\s+(?<id>[A-Za-z0-9_]+)\b'
    )
    if (-not $objectStoreMatch.Success) {
        $objectStoreMatch = [regex]::Match(
            $description,
            '(?im)object-store\s+(?<id>[A-Za-z0-9_]+)\b'
        )
    }
    $machineMatches = [regex]::Matches(
        $description,
        '(?im)Registered machines\.+\s+(?<machine>[A-Za-z0-9_.-]+)\b'
    )
    if (-not $objectStoreMatch.Success) {
        throw 'describedatastore did not expose object-store id.'
    }
    $machineName = if ($machineMatches.Count -gt 0) {
        $machineMatches[$machineMatches.Count - 1].Groups['machine'].Value
    } elseif (-not [string]::IsNullOrWhiteSpace($objectStoreMachine)) {
        $objectStoreMachine
    } else {
        $GeoSceneHostname
    }
    return [PSCustomObject]@{
        Id = $objectStoreMatch.Groups['id'].Value
        Machine = $machineName
    }
}

function Invoke-ObjectStoreValidate {
    $portalUrl = Get-DotEnvValue -Name 'GEOSCENE_PORTAL_URL'
    $username = Get-DotEnvValue -Name 'GEOSCENE_PORTAL_USERNAME'
    $password = Get-DotEnvValue -Name 'GEOSCENE_PORTAL_PASSWORD'
    $serverAdminUrl = Get-DotEnvValue -Name 'GEOSCENE_SERVER_ADMIN_URL'
    if ([string]::IsNullOrWhiteSpace($serverAdminUrl)) {
        $serverAdminUrl = $script:ServerAdminBaseUrl
    }
    if ([string]::IsNullOrWhiteSpace($portalUrl) -or `
        [string]::IsNullOrWhiteSpace($username) -or `
        [string]::IsNullOrWhiteSpace($password)) {
        throw 'GeoScene Portal credentials are missing from process environment or .env.'
    }

    $tokenRaw = & curl.exe -k -sS --connect-timeout 8 --max-time 20 `
        -X POST `
        --data-urlencode ('username={0}' -f $username) `
        --data-urlencode ('password={0}' -f $password) `
        --data-urlencode 'client=referer' `
        --data-urlencode ('referer={0}' -f $portalUrl) `
        --data-urlencode 'f=json' `
        ('{0}/sharing/rest/generateToken' -f $portalUrl) 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ('Portal token request failed with curl exit code {0}.' -f $LASTEXITCODE)
    }
    $tokenResponse = ($tokenRaw -join "`n") | ConvertFrom-Json
    if ($tokenResponse.error -or [string]::IsNullOrWhiteSpace($tokenResponse.token)) {
        throw ('Portal token request failed: {0}' -f (($tokenRaw -join ' ') -replace "`r?`n", ' '))
    }

    $descriptor = Get-ObjectStoreDescriptor
    $validatePath = 'data/items/cloudStores/AGSDataStore_objectstore_{0}/machines/{1}/validate' -f `
        $descriptor.Id, $descriptor.Machine
    $validateRaw = & curl.exe -k -sS --connect-timeout 8 --max-time 45 `
        -X POST `
        --data-urlencode 'f=json' `
        --data-urlencode ('token={0}' -f $tokenResponse.token) `
        ('{0}/{1}' -f $serverAdminUrl, $validatePath) 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ('Object-store validate request failed with curl exit code {0}.' -f $LASTEXITCODE)
    }
    return (($validateRaw -join "`n") | ConvertFrom-Json)
}

function Wait-ObjectStoreHealthy {
    param([int]$TimeoutSeconds = $ObjectStoreValidateTimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $validation = Invoke-ObjectStoreValidate
            $machine = @($validation.machines)[0]
            $healthy = $validation.status -eq 'success' -and `
                $validation.'datastore.overallhealth' -eq 'Healthy' -and `
                $machine.'machine.overallhealth' -eq 'Healthy' -and `
                $machine.status -eq 'Started' -and `
                $machine.isSCMHealthy -eq $true -and `
                $machine.isOMHealthy -eq $true -and `
                $machine.isDataNodeHealthy -eq $true -and `
                $machine.s3gStatus.isS3GHealthy -eq $true
            if ($healthy) {
                Write-Host '[OK]    GeoScene object store official validate is Healthy.' -ForegroundColor Green
                Write-Log ('Object store validate healthy: id={0}, machine={1}, overallhealth={2}, SCM={3}, OM={4}, DataNode={5}, S3G={6}' -f `
                    (Get-ObjectStoreDescriptor).Id,
                    $machine.name,
                    $validation.'datastore.overallhealth',
                    $machine.isSCMHealthy,
                    $machine.isOMHealthy,
                    $machine.isDataNodeHealthy,
                    $machine.s3gStatus.isS3GHealthy)
                return $true
            }
            Write-Host '[WAIT] Object store official validate is not healthy yet.' -ForegroundColor Yellow
            Write-Log ('Object store validate pending: status={0}, overallhealth={1}' -f `
                $validation.status, $validation.'datastore.overallhealth')
        } catch {
            Write-Host ('[WAIT] Object store validate pending: {0}' -f $_.Exception.Message) -ForegroundColor Yellow
            Write-Log ('Object store validate pending: {0}' -f $_.Exception.Message)
        }
        Start-Sleep -Seconds $ObjectStoreValidateIntervalSeconds
    } while ((Get-Date) -lt $deadline)
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

function Invoke-DataStoreDescription {
    if (-not (Test-Path -LiteralPath $DataStoreDescribeTool -PathType Leaf)) {
        throw "Data Store describe tool not found: $DataStoreDescribeTool"
    }

    $command = '/d /s /c ""{0}""' -f $DataStoreDescribeTool
    $captureId = [Guid]::NewGuid().ToString('N')
    $stdoutPath = Join-Path $env:TEMP ("geoscene-describe-{0}.out" -f $captureId)
    $stderrPath = Join-Path $env:TEMP ("geoscene-describe-{0}.err" -f $captureId)
    try {
        $process = Start-Process `
            -FilePath 'cmd.exe' `
            -ArgumentList $command `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -WindowStyle Hidden `
            -PassThru
        if (-not $process.WaitForExit(60000)) {
            try {
                $process.Kill()
            } catch {
                Write-Log ('Could not kill timed-out Data Store describe process: {0}' -f $_.Exception.Message)
            }
            throw 'describedatastore timed out after 60 seconds.'
        }
        # Complete redirected stream reads before inspecting ExitCode/output.
        $process.WaitForExit()
        $process.Refresh()
        $exitCode = $process.ExitCode
        $stdout = if (Test-Path -LiteralPath $stdoutPath) {
            Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue
        } else { '' }
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue
        } else { '' }
        $output = @($stdout, $stderr) -join [Environment]::NewLine
        $flattenedOutput = $output.Trim() -replace "`r?`n", ' | '
        Write-Log ('Data Store describe exit code {0}: {1}' -f $exitCode, $flattenedOutput)
        if ([string]::IsNullOrWhiteSpace($output)) {
            throw ('describedatastore exited with code {0} and produced no output.' -f $exitCode)
        }
        if ($exitCode -ne 0) {
            # When GeoScene Server is intentionally stopped, describedatastore
            # still reports valid local READWRITE modes but exits non-zero
            # because it cannot contact the owning site. Let the caller parse
            # those local modes; official Server Admin validate is the health gate.
            Write-Log ('Data Store describe returned non-zero; local store modes will still be evaluated.')
        }
        return $output
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Test-DataStoreReadWrite {
    try {
        $description = Invoke-DataStoreDescription
        $hasRelational = $description -match 'Relational Data Store'
        $hasObjectStore = $description -match 'Object Store'
        $readWriteCount = ([regex]::Matches($description, 'Data store mode\.+READWRITE')).Count
        $ready = $hasRelational -and $hasObjectStore -and $readWriteCount -ge 2
        if (-not $ready) {
            Write-Log ('Data Store describe did not prove relational/object READWRITE: relational={0}, object={1}, readwriteCount={2}' -f `
                $hasRelational, $hasObjectStore, $readWriteCount)
        }
        return $ready
    } catch {
        Write-Log ('Data Store readiness probe failed: {0}' -f $_.Exception.Message)
        return $false
    }
}

function Wait-DataStoreReadWrite {
    param([int]$TimeoutSeconds = $DataStoreWarmupSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-DataStoreReadWrite) {
            Write-Host '[OK]    GeoScene Data Store relational/object stores are READWRITE.' -ForegroundColor Green
            Write-Log 'Data Store relational/object stores are READWRITE.'
            return $true
        }
        Write-Host ('[WAIT] Data Store relational/object stores not ready ({0}s remaining).' -f `
            [Math]::Max(0, [Math]::Round(($deadline - (Get-Date)).TotalSeconds))) -ForegroundColor Yellow
        Start-Sleep -Seconds $DataStoreProbeIntervalSeconds
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Get-OzoneRecentErrorTime {
    param([datetime]$Since)

    if (-not (Test-Path -LiteralPath $DataStoreOzoneLog -PathType Leaf)) {
        return $null
    }

    $currentTimestamp = $null
    $lastErrorTimestamp = $null
    $errorPattern = 'ServerNotReadyException|AlreadyClosedException|RaftRetryFailureException|Failed to flush|current state is STARTING'
    foreach ($line in (Get-Content -LiteralPath $DataStoreOzoneLog -Tail 800 -ErrorAction SilentlyContinue)) {
        if ($line -match '^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),') {
            try {
                $currentTimestamp = [datetime]::ParseExact(
                    $Matches['ts'],
                    'yyyy-MM-dd HH:mm:ss',
                    [Globalization.CultureInfo]::InvariantCulture
                )
            } catch {
                $currentTimestamp = $null
            }
        }
        if ($currentTimestamp -and $currentTimestamp -gt $Since -and $line -match $errorPattern) {
            $lastErrorTimestamp = $currentTimestamp
        }
    }
    return $lastErrorTimestamp
}

function Wait-DataStoreOzoneQuiet {
    param(
        [datetime]$Since,
        [int]$TimeoutSeconds = $DataStoreOzoneQuietTimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $quietWindowStart = Get-Date
    do {
        $now = Get-Date
        $lastError = Get-OzoneRecentErrorTime -Since $Since
        if ($lastError -and $lastError -gt $quietWindowStart) {
            $quietWindowStart = $lastError
        }
        $quietForSeconds = [Math]::Max(0, [Math]::Round(($now - $quietWindowStart).TotalSeconds))
        if ($quietForSeconds -ge $DataStoreOzoneQuietSeconds) {
            Write-Host '[OK]    GeoScene Data Store Ozone/Ratis log is quiet.' -ForegroundColor Green
            Write-Log ('Data Store Ozone/Ratis log is quiet for {0} seconds.' -f $quietForSeconds)
            return $true
        }
        $lastErrorText = if ($lastError) { $lastError.ToString('HH:mm:ss') } else { 'none in this window' }
        Write-Host ('[WAIT] Data Store Ozone/Ratis quiet window {0}/{1}s; last error: {2}.' -f `
            $quietForSeconds, $DataStoreOzoneQuietSeconds, $lastErrorText) -ForegroundColor Yellow
        Start-Sleep -Seconds 10
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Assert-DataStoreReady {
    param([datetime]$Since)

    Assert-OzoneConfigReady
    if (-not (Wait-DataStoreReadWrite)) {
        throw ('Data Store relational/object stores did not become READWRITE within {0} seconds.' -f $DataStoreWarmupSeconds)
    }
    if (-not (Wait-DataStoreOzoneQuiet -Since $Since)) {
        throw ('Data Store Ozone/Ratis log did not stay quiet for {0} seconds within {1} seconds.' -f `
            $DataStoreOzoneQuietSeconds, $DataStoreOzoneQuietTimeoutSeconds)
    }
    return $true
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
    if ($RestartServer) {
        $arguments += '-RestartServer'
    }
    if ($RestartDataStore) {
        $arguments += '-RestartDataStore'
    }
    if ($RestartPortal) {
        $arguments += '-RestartPortal'
    }
    if ($PinLocalHostname) {
        $arguments += '-PinLocalHostname'
    }
    if ($AllowServerWhileDataStoreRecovering) {
        $arguments += '-AllowServerWhileDataStoreRecovering'
    }

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

if ($PinLocalHostname) {
    $hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
    $hostsLines = @(Get-Content -LiteralPath $hostsPath -ErrorAction Stop)
    $hostPattern = '(?i)^\s*[^#].*\b' + [regex]::Escape($GeoSceneHostname) + '\b'
    $hostsLines = @($hostsLines | Where-Object { $_ -notmatch $hostPattern })
    $hostsLines += ('127.0.0.1 {0}' -f $GeoSceneHostname)
    Set-Content -LiteralPath $hostsPath -Value $hostsLines -Encoding ASCII -ErrorAction Stop
    Clear-DnsClientCache -ErrorAction SilentlyContinue
    Write-Host ('[OK]    Pinned {0} to 127.0.0.1.' -f $GeoSceneHostname) -ForegroundColor Green
    Write-Log ('Pinned local GeoScene hostname to loopback: {0}' -f $GeoSceneHostname)
}

Write-Host ''
Write-Host 'Starting GeoScene services ...' -ForegroundColor Cyan
Write-Host ('Log: {0}' -f $LogFile)
Write-Host ''
$null = Write-ResourceStatus

$failed = 0
$serviceStartedThisRun = @{}
$serverStoppedForDataStoreRestart = $false
$dataStoreReady = $false

if ($RestartDataStore) {
    try {
        $serverService = Get-Service -Name 'GeoScene Server' -ErrorAction Stop
        if ($serverService.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
            Write-Host '[STOP]  GeoScene Server before Data Store restart ...' -ForegroundColor Yellow
            Write-Log 'Stopping GeoScene Server before Data Store restart to avoid object-store writes during warmup.'
            Stop-ManagedService -Name 'GeoScene Server' -TimeoutSeconds 60
            $serverStoppedForDataStoreRestart = $true
        }
    } catch {
        $failed++
        Write-Host ('[ERROR] GeoScene Server pre-stop: {0}' -f $_.Exception.Message) -ForegroundColor Red
        Write-Log ('Failed: GeoScene Server pre-stop before Data Store restart: {0}' -f $_.Exception.Message)
    }
}

foreach ($serviceName in $ServiceNames) {
    try {
        $service = Get-Service -Name $serviceName -ErrorAction Stop
        $serviceStartedThisRun[$serviceName] = $false
        if ($serviceName -eq 'GeoScene Server' -and -not $dataStoreReady) {
            throw 'GeoScene Server start skipped because Data Store object store is not stable.'
        }
        $explicitRestart =
            ($RestartServer -and $serviceName -eq 'GeoScene Server') -or
            ($RestartDataStore -and $serviceName -eq 'GeoScene Data Store') -or
            ($RestartPortal -and $serviceName -eq 'GeoScene Portal')

        if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Running -and $explicitRestart) {
            Write-Host ('[RESTART] {0} requested explicitly ...' -f $serviceName) -ForegroundColor Yellow
            Write-Log ('Explicit restart requested: {0}' -f $serviceName)
            $serviceStabilitySince = Get-Date
            Restart-ManagedService -Name $serviceName
            $service.Refresh()
            if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
                throw ('{0} did not return to RUNNING after explicit restart.' -f $serviceName)
            }
            Write-Host ('[OK]    {0} restarted.' -f $serviceName) -ForegroundColor Green
            Write-Log ('Restarted successfully: {0}' -f $serviceName)
            $serviceStartedThisRun[$serviceName] = $true
            if ($serviceName -eq 'GeoScene Data Store') {
                Assert-DataStoreReady -Since $serviceStabilitySince
                $dataStoreReady = $true
            }
            continue
        }

        if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
            Write-Host ('[OK]    {0} is already running.' -f $serviceName) -ForegroundColor Green
            Write-Log ('Already running: {0}' -f $serviceName)
            if ($serviceName -eq 'GeoScene Data Store') {
                Assert-DataStoreReady -Since (Get-Date)
                $dataStoreReady = $true
            }
            continue
        }

        Write-Host ('[START] {0} ...' -f $serviceName) -ForegroundColor Yellow
        Write-Log ('Starting: {0}' -f $serviceName)
        $serviceCommitHeadroomGB = if ($serviceName -eq 'GeoScene Server') { 1.5 } else { $MinimumCommitHeadroomGB }
        Assert-StartupResources -Operation ('starting {0}' -f $serviceName) `
            -RequiredCommitHeadroomGB $serviceCommitHeadroomGB
        $serviceStabilitySince = Get-Date
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
        if ($serviceName -eq 'GeoScene Data Store') {
            Assert-DataStoreReady -Since $serviceStabilitySince
            $dataStoreReady = $true
        }
    } catch {
        $failed++
        Write-Host ('[ERROR] {0}: {1}' -f $serviceName, $_.Exception.Message) -ForegroundColor Red
        Write-Log ('Failed: {0}: {1}' -f $serviceName, $_.Exception.Message)
        $details = & sc.exe queryex $serviceName 2>&1 | Out-String
        Add-Content -LiteralPath $LogFile -Encoding UTF8 -Value $details.TrimEnd()
    }
}

$objectStoreHealthy = $false
try {
    $serverService = Get-Service -Name 'GeoScene Server' -ErrorAction Stop
    if ($dataStoreReady -and $serverService.Status -eq [ServiceProcess.ServiceControllerStatus]::Running) {
        $objectStoreHealthy = Wait-ObjectStoreHealthy
        if (-not $objectStoreHealthy) {
            throw ('GeoScene object store official validate did not become Healthy within {0} seconds.' -f `
                $ObjectStoreValidateTimeoutSeconds)
        }
    } else {
        Write-Log 'Skipped object-store official validate because Data Store or GeoScene Server is not ready.'
    }
} catch {
    $failed++
    Write-Host ('[ERROR] GeoScene object store validate: {0}' -f $_.Exception.Message) -ForegroundColor Red
    Write-Log ('Failed: GeoScene object store validate: {0}' -f $_.Exception.Message)
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

$serverReadyForWebAdaptor = $false
try {
    $serverReadyForWebAdaptor = (Get-Service -Name 'GeoScene Server' -ErrorAction Stop).Status -eq `
        [ServiceProcess.ServiceControllerStatus]::Running
} catch {
    Write-Log ('Could not determine GeoScene Server status for Web Adaptor check: {0}' -f $_.Exception.Message)
}

if (-not $portalReady) {
    Write-Host '[SKIP]  Tomcat/Web Adaptor left unchanged because Portal is still warming up.' -ForegroundColor Yellow
    Write-Log 'Skipped Tomcat/Web Adaptor changes because Portal is not ready.'
} elseif (-not $serverReadyForWebAdaptor) {
    Write-Host '[SKIP]  Tomcat/Web Adaptor left unchanged because GeoScene Server is not running.' -ForegroundColor Yellow
    Write-Log 'Skipped Tomcat/Web Adaptor changes because GeoScene Server is not running.'
} elseif (-not $objectStoreHealthy) {
    Write-Host '[SKIP]  Tomcat/Web Adaptor left unchanged because object-store official validate is not Healthy.' -ForegroundColor Yellow
    Write-Log 'Skipped Tomcat/Web Adaptor changes because object-store official validate is not Healthy.'
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
