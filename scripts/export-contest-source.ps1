# Sync a clean copy of the project source into the contest submission folder.
#
# The contest submission folder lives at C:\Users\<user>\Desktop\contest-submission.
# C700开发源代码 is rebuilt as a PLAIN DIRECTORY from `git ls-files` (tracked
# files only), so it never contains .env / logs / work / node_modules / venv
# or any other local artifact, and this script NEVER touches the project root.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\export-contest-source.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\export-contest-source.ps1 -SubmissionRoot "C:\path\to\contest-submission"

param(
    [string]$SubmissionRoot = ""
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($SubmissionRoot)) {
    $desktop = [Environment]::GetFolderPath('Desktop')
    $SubmissionRoot = Join-Path $desktop 'contest-submission'
}
if (-not (Test-Path -LiteralPath $SubmissionRoot)) {
    throw "Submission folder not found: $SubmissionRoot"
}
$submissionRoot = (Resolve-Path -LiteralPath $SubmissionRoot).Path

function Write-Step([string]$msg) { Write-Host "$(Get-Date -Format 'HH:mm:ss')  $msg" }

$target = Join-Path $submissionRoot 'C700开发源代码'

# 安全模式：从 git 跟踪列表重建独立副本（不触碰项目根）。
$tracked = & git -C $projectRoot ls-files 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "git ls-files failed under $projectRoot"
}

# 若目标是 junction（旧方案遗留），先移除，避免误删项目根文件。
if (Test-Path -LiteralPath $target) {
    $item = Get-Item -LiteralPath $target -Force
    if ($item.LinkType -eq 'Junction') {
        Write-Step "removing leftover junction (avoid touching project root)"
        Remove-Item -LiteralPath $target -Force -Recurse
    } else {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
New-Item -ItemType Directory -Path $target -Force | Out-Null

$count = 0
foreach ($file in $tracked) {
    $from = Join-Path $projectRoot $file
    $to = Join-Path $target $file
    if ([System.IO.File]::Exists($from)) {
        $toDir = Split-Path -Parent $to
        if (-not (Test-Path -LiteralPath $toDir)) {
            New-Item -ItemType Directory -Path $toDir -Force | Out-Null
        }
        Copy-Item -LiteralPath $from -Destination $to -Force
        $count++
    }
}

Write-Step "Rebuilt $count tracked files into $target"
Write-Step "Submission dir is a plain copy: git-tracked source only, no .env/logs/work/deps."
Write-Step "Run git status in the project to confirm the working tree is clean before submitting."
