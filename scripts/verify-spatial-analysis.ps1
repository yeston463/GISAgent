[CmdletBinding()]
param(
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [hashtable]$Payload
    )

    $json = $Payload | ConvertTo-Json -Depth 12 -Compress
    $body = [System.Text.Encoding]::UTF8.GetBytes($json)
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec 45 `
        -Uri "$ApiBaseUrl$Path" -ContentType 'application/json; charset=utf-8' -Body $body
    return $response.Content | ConvertFrom-Json
}

function Require {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$memoryId = "spatial-contract-$([Guid]::NewGuid().ToString('N'))"
$demo = Invoke-JsonRequest '/api/gis/demo-context' @{ memoryId = $memoryId }
Require ($demo.status -eq 'Success') 'Demo context was not saved.'
Require ($demo.hasAoi -and $demo.hasBuildings -and $demo.buildingCount -eq 3) 'Demo context is incomplete.'

$results = @()
foreach ($request in @(
    @{ label = 'skyline'; message = 'skyline analysis'; expected = 'skyline_analysis' },
    @{ label = 'sunlight'; message = 'sunlight analysis'; expected = 'sunlight_analysis' },
    @{ label = 'flood'; message = 'flood analysis'; expected = 'flood_analysis' }
)) {
    $response = Invoke-JsonRequest '/api/agent/chat/agentic' @{
        memoryId = $memoryId
        message = $request.message
    }
    $acceptedStatuses = if ($request.label -eq 'flood') { @('Success', 'NeedsClarification') } else { @('Success') }
    Require ($acceptedStatuses -contains $response.outcome.status) "$($request.label) returned $($response.outcome.status)"
    Require ($response.outcome.analysisType -eq $request.expected) "$($request.label) used an unexpected capability."
    Require (@($response.trace | Where-Object { $_.phase -eq 'plan' }).Count -gt 0) "$($request.label) has no plan trace."
    if ($response.outcome.status -eq 'Success') {
        Require (-not [string]::IsNullOrWhiteSpace($response.resultEnvelope.provenance.runId)) "$($request.label) has no provenance run id."
        Require ($null -ne $response.metrics) "$($request.label) returned no analysis metrics."
    } else {
        $needsRainfall = @($response.outcome.missingData) -contains 'rainfall_scenario'
        $needsGridDem = $response.outcome.code -eq 'execution_data_required' -and
            @($response.outcome.provenance.result.missing_data) -contains 'hydrologic_dem_grid'
        Require ($needsRainfall -or $needsGridDem) 'flood clarification did not identify rainfall or hydrologic DEM requirements.'
    }
    $results += [PSCustomObject]@{
        analysis = $request.label
        status = $response.outcome.status
        capability = $response.outcome.analysisType
        runId = $response.resultEnvelope.provenance.runId
    }
}

$results | Format-Table -AutoSize
