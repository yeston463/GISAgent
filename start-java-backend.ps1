[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = $PSScriptRoot
}
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$mavenWrapper = Join-Path $ProjectRoot 'mvnw.cmd'
$mainClassFile = Join-Path $ProjectRoot 'target\classes\org\example\Lc4j1Application.class'
$classpathFile = Join-Path $ProjectRoot 'target\runtime-classpath.txt'

# The standalone Java launcher must receive the same local configuration as
# start-dev.bat. Do not overwrite environment variables supplied by the user.
$envFile = Join-Path $ProjectRoot '.env'
if (Test-Path -LiteralPath $envFile -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $pair = $trimmed -split '=', 2
        if ($pair.Count -ne 2 -or [string]::IsNullOrWhiteSpace($pair[0])) { continue }
        $key = $pair[0].Trim()
        $value = $pair[1].Trim().Trim('"').Trim("'")
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}

# This launcher is for local development. Keep the application default closed
# for shared deployments, but make the repeatable spatial sample available
# unless the developer explicitly set SPATIAL_DEMO_ENABLED.
if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SPATIAL_DEMO_ENABLED', 'Process'))) {
    [Environment]::SetEnvironmentVariable('SPATIAL_DEMO_ENABLED', 'true', 'Process')
    Write-Host '[Java] SPATIAL_DEMO_ENABLED was not configured; using true for local development.'
}

function Get-Java17Executable {
    $candidates = [System.Collections.Generic.List[string]]::new()

    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe'))
    }

    $javaHomes = @(
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft')
    )
    foreach ($javaHome in $javaHomes) {
        if (-not (Test-Path -LiteralPath $javaHome)) {
            continue
        }
        Get-ChildItem -LiteralPath $javaHome -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\java.exe')) }
    }

    Get-Command java.exe -All -ErrorAction SilentlyContinue |
        ForEach-Object { $candidates.Add($_.Source) }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            continue
        }

        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $versionOutput = (& $candidate -version 2>&1 | Out-String)
        $versionExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousPreference
        if ($versionExitCode -ne 0) {
            continue
        }

        $versionMatch = [regex]::Match($versionOutput, 'version\s+"(?<version>[^"]+)"')
        if (-not $versionMatch.Success) {
            continue
        }
        $version = $versionMatch.Groups['version'].Value
        $major = if ($version.StartsWith('1.')) {
            [int]($version.Split('.')[1])
        } else {
            [int]($version.Split('.')[0])
        }
        if ($major -ge 17) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw 'Java 17 or newer was not found. Set JAVA_HOME to a valid JDK installation.'
}

if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "Maven wrapper not found: $mavenWrapper"
}

$javaExe = Get-Java17Executable
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
Set-Location -LiteralPath $ProjectRoot

if (-not $SkipBuild) {
    Write-Host '[Java] Compiling backend...'
    & $mavenWrapper -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Java compilation failed with exit code $LASTEXITCODE."
    }

    Write-Host '[Java] Resolving runtime dependencies...'
    & $mavenWrapper -q dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-Dmdep.includeScope=runtime'
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime classpath generation failed with exit code $LASTEXITCODE."
    }
}

if (-not (Test-Path -LiteralPath $mainClassFile -PathType Leaf)) {
    throw "Compiled main class not found: $mainClassFile"
}
if (-not (Test-Path -LiteralPath $classpathFile -PathType Leaf)) {
    throw "Runtime classpath file not found: $classpathFile"
}

$runtimeClasspath = (Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8).Trim()
if (-not $runtimeClasspath) {
    throw "Runtime classpath file is empty: $classpathFile"
}

$missingDependencies = @(
    $runtimeClasspath -split [IO.Path]::PathSeparator |
        Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
)
if ($missingDependencies.Count -gt 0) {
    $preview = ($missingDependencies | Select-Object -First 3) -join [Environment]::NewLine
    throw "Runtime classpath contains $($missingDependencies.Count) missing dependencies:`n$preview"
}

$classpath = "target\classes;$runtimeClasspath"
Write-Host "[Java] JDK: $javaExe"
Write-Host '[Java] Starting backend at http://127.0.0.1:8080 ...'

# Direct Java launch avoids Spring Boot Maven Plugin classpath issues on Windows paths.
& $javaExe -cp $classpath org.example.Lc4j1Application
exit $LASTEXITCODE
