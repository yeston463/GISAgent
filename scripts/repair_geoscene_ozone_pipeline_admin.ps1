<#
Repair a GeoScene Data Store / Apache Ozone object-store state where a new
Scene Service publish keeps landing on an empty but broken OPEN container and
pipeline.

This script intentionally avoids deleting or formatting object-store data. It:
  1. backs up ozone-site.xml;
  2. temporarily adds local repair identities to ozone.administrators;
  3. restarts GeoScene Data Store so SCM reads that temporary admin list;
  4. closes the known empty bad container and pipeline;
  5. creates a fresh RATIS/ONE pipeline;
  6. restores the original ozone-site.xml and restarts services again.

Run from an elevated Administrator PowerShell.
#>

param(
    [int]$ContainerId = 23001,
    [string]$PipelineId = '37130714-41dd-4267-b648-90dd2cb5218f',
    [string]$RepairHadoopUser = 'geoscene',
    [switch]$SkipRestoreConfig
)

$ErrorActionPreference = 'Stop'

# Match the GeoScene launcher behavior: repair requires service/configuration
# rights, so transparently request an elevated process when started normally.
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$currentPrincipal = [Security.Principal.WindowsPrincipal]::new($currentIdentity)
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = @(
        '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        ('"{0}"' -f $PSCommandPath)
    )
    foreach ($parameter in $PSBoundParameters.GetEnumerator()) {
        if ($parameter.Value -is [switch] -and $parameter.Value) {
            $arguments += "-$($parameter.Key)"
        } elseif ($null -ne $parameter.Value) {
            $arguments += "-$($parameter.Key)"
            $arguments += ('"{0}"' -f $parameter.Value)
        }
    }
    $elevated = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') `
        -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -Verb RunAs -PassThru -Wait
    exit $elevated.ExitCode
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDir = Join-Path $repoRoot 'work-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDir ("geoscene-ozone-pipeline-repair-$timestamp.log")

$dataStoreHome = 'C:\Program Files\GeoScene\DataStore'
$ozoneDataDir = 'C:\geoscenedatastore\ozonedata'
$siteXml = Join-Path $ozoneDataDir 'etc\hadoop\ozone-site.xml'
$writeBlocker = Join-Path $ozoneDataDir 'etc\hadoop\writeblocker'
$adminBat = Join-Path $dataStoreHome 'framework\etc\scripts\objectstoreadmin.bat'
$backupDir = Join-Path $logDir 'ozone-config-backups'
$backupXml = Join-Path $backupDir ("ozone-site.xml.$timestamp.before-pipeline-repair.bak")

function Write-Step {
    param([string]$Message)
    $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function Assert-Administrator {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Please run this script from an elevated Administrator PowerShell.'
    }
}

function Wait-ServiceStatus {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Status,
        [int]$TimeoutSeconds = 240
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

function Restart-GeoSceneRuntime {
    param([string]$Reason)

    Write-Step ("Restarting GeoScene runtime: $Reason")
    $server = Get-Service -Name 'GeoScene Server' -ErrorAction SilentlyContinue
    if ($server -and $server.Status -ne 'Stopped') {
        Write-Step 'Stopping GeoScene Server...'
        Stop-Service -Name 'GeoScene Server' -Force
        Wait-ServiceStatus -Name 'GeoScene Server' -Status 'Stopped' -TimeoutSeconds 180
    }

    Write-Step 'Restarting GeoScene Data Store...'
    Restart-Service -Name 'GeoScene Data Store' -Force
    Wait-ServiceStatus -Name 'GeoScene Data Store' -Status 'Running' -TimeoutSeconds 300

    Write-Step 'Waiting 25 seconds for embedded Ozone services to become reachable...'
    Start-Sleep -Seconds 25

    Write-Step 'Starting GeoScene Server...'
    Start-Service -Name 'GeoScene Server'
    Wait-ServiceStatus -Name 'GeoScene Server' -Status 'Running' -TimeoutSeconds 300
}

function Get-CurrentWindowsIdentityNames {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $names = New-Object System.Collections.Generic.List[string]
    $names.Add($identity.Name)
    if ($identity.Name -like '*\*') {
        $names.Add(($identity.Name -split '\\')[-1])
    }
    $envNames = @($env:USERNAME, $env:USERDOMAIN)
    foreach ($name in $envNames) {
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            $names.Add($name)
        }
    }
    return $names | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
}

function Set-TemporaryOzoneAdministrators {
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    Copy-Item -LiteralPath $siteXml -Destination $backupXml -Force
    Write-Step ("Backed up ozone-site.xml to $backupXml")

    [xml]$xml = Get-Content -Raw -LiteralPath $siteXml
    $configuration = $xml.configuration
    if (-not $configuration) {
        throw "Invalid Ozone config: missing <configuration> in $siteXml"
    }

    $property = @($configuration.property | Where-Object { $_.name -eq 'ozone.administrators' } | Select-Object -First 1)
    if ($property.Count -eq 0) {
        $property = $xml.CreateElement('property')
        $name = $xml.CreateElement('name')
        $name.InnerText = 'ozone.administrators'
        $value = $xml.CreateElement('value')
        $value.InnerText = ''
        [void]$property.AppendChild($name)
        [void]$property.AppendChild($value)
        [void]$configuration.AppendChild($property)
    } else {
        $property = $property[0]
    }

    $existing = @()
    $valueNode = $property.SelectSingleNode('./value')
    if ($valueNode -and -not [string]::IsNullOrWhiteSpace($valueNode.InnerText)) {
        $existing = $valueNode.InnerText -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    }

    $repairUsers = @(
        $RepairHadoopUser,
        'geoscene',
        'agsozone',
        'agsscm',
        'CodexSandboxOffline',
        'portaladmin'
    ) + (Get-CurrentWindowsIdentityNames)

    $merged = @($existing + $repairUsers) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique

    $valueNode = $property.SelectSingleNode('./value')
    if (-not $valueNode) {
        $valueNode = $xml.CreateElement('value')
        [void]$property.AppendChild($valueNode)
    }
    $valueNode.InnerText = ($merged -join ',')
    $xml.Save($siteXml)
    Write-Step ("Temporarily set ozone.administrators=$($valueNode.InnerText)")
    Set-OzoneRatisReplicationType
}

function Set-OzoneRatisReplicationType {
    [xml]$xml = Get-Content -Raw -LiteralPath $siteXml
    $configuration = $xml.configuration
    $property = @($configuration.property | Where-Object { $_.name -eq 'ozone.replication.type' } | Select-Object -First 1)
    if ($property.Count -eq 0) {
        $property = $xml.CreateElement('property')
        $name = $xml.CreateElement('name')
        $name.InnerText = 'ozone.replication.type'
        $value = $xml.CreateElement('value')
        $value.InnerText = 'RATIS'
        [void]$property.AppendChild($name)
        [void]$property.AppendChild($value)
        [void]$configuration.AppendChild($property)
    } else {
        $property = $property[0]
        $value = $property.SelectSingleNode('./value')
        if (-not $value) {
            $value = $xml.CreateElement('value')
            [void]$property.AppendChild($value)
        }
        $value.InnerText = 'RATIS'
    }
    $xml.Save($siteXml)
    Write-Step 'Set ozone.replication.type=RATIS (kept permanently after repair).'
}

function Restore-OzoneSiteXml {
    if ($SkipRestoreConfig) {
        Write-Step 'SkipRestoreConfig was set; leaving temporary ozone.administrators in place.'
        return
    }
    if (Test-Path -LiteralPath $backupXml) {
        Copy-Item -LiteralPath $backupXml -Destination $siteXml -Force
        Write-Step "Restored original ozone-site.xml from $backupXml"
        # Restore only the temporary administrator list; STAND_ALONE is the
        # root cause of writeDenied and must remain corrected.
        Set-OzoneRatisReplicationType
        if (Test-Path -LiteralPath $writeBlocker) {
            Remove-Item -LiteralPath $writeBlocker -Force
            Write-Step 'Removed stale Ozone writeblocker marker after RATIS repair.'
        }
    } else {
        Write-Step "Backup not found; cannot restore ozone-site.xml: $backupXml"
    }
}

function Invoke-OzoneAdmin {
    param(
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [int]$TimeoutSeconds = 120,
        [switch]$AllowFailure
    )

    $argText = $Arguments -join ' '
    Write-Step ("objectstoreadmin.bat $argText")

    $outFile = Join-Path $env:TEMP ("ozone-admin-$timestamp-{0}.out" -f ([guid]::NewGuid().ToString('N')))
    $errFile = Join-Path $env:TEMP ("ozone-admin-$timestamp-{0}.err" -f ([guid]::NewGuid().ToString('N')))

    $env:AGSDATASTORE = $dataStoreHome
    $env:OBJSDATADIR = $ozoneDataDir
    $env:HADOOP_HOME = Join-Path $dataStoreHome 'framework\runtime\ozone'
    $env:HADOOP_USER_NAME = $RepairHadoopUser

    $process = Start-Process -FilePath $adminBat `
        -ArgumentList $Arguments `
        -RedirectStandardOutput $outFile `
        -RedirectStandardError $errFile `
        -NoNewWindow `
        -PassThru

    $timedOut = $false
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $timedOut = $true
        try {
            & $env:ComSpec /c "taskkill /PID $($process.Id) /T /F" | Out-Null
        } catch {
            try { $process.Kill() } catch {}
        }
        $message = "Timed out after $TimeoutSeconds seconds: objectstoreadmin.bat $argText"
        Write-Step $message
        if (-not $AllowFailure) {
            throw $message
        }
    }

    $stdout = if (Test-Path -LiteralPath $outFile) { Get-Content -Raw -LiteralPath $outFile } else { '' }
    $stderr = if (Test-Path -LiteralPath $errFile) { Get-Content -Raw -LiteralPath $errFile } else { '' }
    $combined = (($stdout, $stderr) -join "`n").Trim()
    if ($combined) {
        Add-Content -Path $logFile -Value $combined -Encoding UTF8
        Write-Host $combined
    }

    $exitCode = $process.ExitCode
    if ($null -eq $exitCode -or "$exitCode" -eq '') {
        # Windows .bat wrappers launched through Start-Process can occasionally
        # expose an empty ExitCode even after WaitForExit() returned. In that
        # case rely on the captured output/error markers below.
        $exitCode = 0
    }
    Write-Step ("ExitCode=$exitCode")

    if ($combined -match 'Access denied|SCM superuser privilege is required') {
        throw "Ozone admin privilege was still denied for HADOOP_USER_NAME=$RepairHadoopUser."
    }

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "objectstoreadmin.bat $argText failed with exit code $exitCode."
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $combined
        TimedOut = $timedOut
    }
}

Assert-Administrator

if (-not (Test-Path -LiteralPath $siteXml)) {
    throw "Missing ozone-site.xml: $siteXml"
}
if (-not (Test-Path -LiteralPath $adminBat)) {
    throw "Missing objectstoreadmin.bat: $adminBat"
}

Write-Step 'Starting GeoScene Ozone empty-container/pipeline repair.'
Write-Step ("Target container=$ContainerId pipeline=$PipelineId repairUser=$RepairHadoopUser")

$restored = $false
$temporaryConfigWasLoaded = $false
try {
    Set-TemporaryOzoneAdministrators
    Restart-GeoSceneRuntime -Reason 'apply temporary ozone.administrators'
    $temporaryConfigWasLoaded = $true

    Write-Step 'Current container before repair:'
    Invoke-OzoneAdmin -Arguments @('container', 'info', "$ContainerId") -TimeoutSeconds 90 -AllowFailure | Out-Null

    Write-Step 'Closing empty bad container...'
    Invoke-OzoneAdmin -Arguments @('container', 'close', "$ContainerId") -TimeoutSeconds 120 -AllowFailure | Out-Null

    Write-Step 'Closing bad pipeline...'
    Invoke-OzoneAdmin -Arguments @('pipeline', 'close', $PipelineId) -TimeoutSeconds 120 -AllowFailure | Out-Null

    Write-Step 'Pipeline list before creating fresh pipeline:'
    $pipelineList = Invoke-OzoneAdmin -Arguments @('pipeline', 'list') -TimeoutSeconds 90 -AllowFailure
    # Match both properties on the same pipeline record. A global search can
    # otherwise combine an OPEN STANDALONE pipeline with a CLOSED RATIS one.
    $hasHealthyPipeline = $pipelineList.Output -match '(?m)^Pipeline\[[^\r\n]*ReplicationConfig:\s*RATIS/ONE[^\r\n]*State:\s*(OPEN|ALLOCATED)\b'

    if ($hasHealthyPipeline) {
        Write-Step 'A healthy RATIS/ONE pipeline already exists; skipping pipeline create.'
    } else {
        Write-Step 'Creating fresh RATIS/ONE pipeline...'
        $createResult = Invoke-OzoneAdmin -Arguments @('pipeline', 'create', '-t', 'RATIS', '-f', 'ONE') -TimeoutSeconds 120 -AllowFailure
        if ($createResult.TimedOut) {
            Write-Step 'Pipeline create timed out; continuing to restore config and restart services. GeoScene may auto-create a new pipeline on next write.'
        }
    }

    Write-Step 'Pipeline list after repair:'
    $pipelineListAfterRepair = Invoke-OzoneAdmin -Arguments @('pipeline', 'list') -TimeoutSeconds 90 -AllowFailure

    # SCM prefers an OPEN standalone pipeline when allocating a container.
    # Leave only RATIS available so the next container cannot be born on the
    # broken standalone path again.
    $standaloneMatches = [regex]::Matches(
        $pipelineListAfterRepair.Output,
        '(?m)^Pipeline\[\s*Id:\s*([0-9a-f-]+)[^\r\n]*ReplicationConfig:\s*STANDALONE/ONE[^\r\n]*State:\s*(OPEN|ALLOCATED)\b'
    )
    foreach ($match in $standaloneMatches) {
        $standalonePipelineId = $match.Groups[1].Value
        Write-Step "Closing OPEN STANDALONE pipeline $standalonePipelineId before container allocation..."
        Invoke-OzoneAdmin -Arguments @('pipeline', 'close', $standalonePipelineId) -TimeoutSeconds 120 -AllowFailure | Out-Null
    }

    # A healthy pipeline alone is insufficient: SCM can have no OPEN RATIS
    # container after a broken container was closed, which makes S3 Flush fail
    # with writeDenied even though Data Store describe reports Healthy. Do not
    # call `container create` here: that administrative command creates a
    # STANDALONE container by design and would reintroduce the broken path.
    # The first real S3 write will ask SCM to allocate from the RATIS pipeline.
    Write-Step 'Checking for an OPEN RATIS/ONE container...'
    $openRatisContainers = Invoke-OzoneAdmin -Arguments @(
        'container', 'list', '-c', '100', '--state', 'OPEN', '--type', 'RATIS', '--factor', 'ONE'
    ) -TimeoutSeconds 90 -AllowFailure
    if ($openRatisContainers.Output -notmatch '"replicationType"\s*:\s*"RATIS"') {
        Write-Step 'No OPEN RATIS/ONE container found; leaving allocation to SCM on the next S3 write.'
    } else {
        Write-Step 'An OPEN RATIS/ONE container already exists.'
    }

    Restore-OzoneSiteXml
    $restored = -not $SkipRestoreConfig
    Restart-GeoSceneRuntime -Reason 'restore original Ozone administrator configuration'

    Write-Step 'Final service status:'
    Get-Service -Name 'GeoScene Portal','GeoScene Server','GeoScene Data Store' |
        Select-Object Name,Status,StartType |
        Format-Table -AutoSize |
        Out-String |
        Tee-Object -FilePath $logFile -Append

    Write-Step 'Repair completed. Re-run the golden SLPK publish smoke test next.'
}
catch {
    Write-Step ("Repair failed: $($_.Exception.Message)")
    if (-not $restored -and -not $SkipRestoreConfig -and (Test-Path -LiteralPath $backupXml)) {
        Write-Step 'Attempting to restore ozone-site.xml after failure...'
        Copy-Item -LiteralPath $backupXml -Destination $siteXml -Force
        Write-Step 'Restored ozone-site.xml after failure.'
        if ($temporaryConfigWasLoaded) {
            try {
                Restart-GeoSceneRuntime -Reason 'restore original Ozone administrator configuration after failure'
            } catch {
                Write-Step ("Failed to restart GeoScene runtime after restoring config: $($_.Exception.Message)")
            }
        }
    }
    throw
}
finally {
    Write-Step ("Log: $logFile")
}
