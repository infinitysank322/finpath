<#
.SYNOPSIS
  Stops what .\start-local.ps1 started: the API, ng serve and the compose Postgres.

.DESCRIPTION
  Ends the two PowerShell windows start-local.ps1 opened together with the API and ng serve processes under them,
  then runs `docker compose stop` (Docker shuts Postgres down with SIGTERM). The Postgres data volume is kept:
  nothing is deleted. Only processes this script can tie to this repo are stopped.
#>
[CmdletBinding()]
param()

$root      = $PSScriptRoot
$stateFile = Join-Path $root '.local-run\pids.json'

function Step([string]$message) {
    Write-Host ''
    Write-Host "== $message" -ForegroundColor Cyan
}

function Get-Listener([int]$port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $c) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId = $($c.OwningProcess)" -ErrorAction SilentlyContinue
}

# End the window's whole process tree (powershell -> mvnw/npm -> java/node). Console windows opened by
# start-local.ps1 have no main window to close, so a polite close does not work; ending the tree is safe
# here because the API holds no state of its own (Postgres is stopped by Docker below).
function Stop-Window([int]$processId, [string]$label) {
    if (-not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        Write-Host "$label window is already closed."
        return
    }
    Write-Host "Stopping $label (window PID $processId and its child processes)..."
    & taskkill /T /F /PID $processId *> $null
}

# Fallback for a process that outlived its window: only if it is a java/node process started from this repo.
function Stop-Leftover([int]$port, [string]$label) {
    $proc = Get-Listener $port
    if (-not $proc) { return }
    $cmd = [string]$proc.CommandLine
    if (($proc.Name -in @('java.exe', 'node.exe')) -and $cmd.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        Write-Host "$label is still listening on port $port ($($proc.Name), PID $($proc.ProcessId)); ending it."
        & taskkill /T /F /PID $proc.ProcessId *> $null
    }
    else {
        Write-Host "Port $port is held by $($proc.Name) (PID $($proc.ProcessId)), which does not look like part of this repo. Left alone." -ForegroundColor Yellow
    }
}

Step 'Stopping the API and ng serve'
$state = $null
if (Test-Path -LiteralPath $stateFile) {
    try { $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } catch { }
}
if ($state -and $state.web) { Stop-Window ([int]$state.web) 'ng serve' }
if ($state -and $state.api) { Stop-Window ([int]$state.api) 'API' }
if (-not $state -or (-not $state.api -and -not $state.web)) {
    Write-Host 'No window PIDs recorded (start-local.ps1 did not start these, or they were already stopped).'
}

Start-Sleep -Seconds 2
Stop-Leftover 4200 'ng serve'
Stop-Leftover 8080 'The API'
Remove-Item -LiteralPath $stateFile -Force -ErrorAction SilentlyContinue

Step 'Stopping Postgres (docker compose)'
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Docker is not running, so there is nothing to stop there.' -ForegroundColor Yellow
}
else {
    Push-Location $root
    try { & docker compose stop } finally { Pop-Location }
    Write-Host 'Postgres stopped. Data is kept in the pgdata volume.'
}

Step 'Result'
foreach ($port in 4200, 8080) {
    $l = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($l) { Write-Host "Port $port is still in use (PID $($l.OwningProcess))." -ForegroundColor Yellow }
    else    { Write-Host "Port $port is free." }
}
