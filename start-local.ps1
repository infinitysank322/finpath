<#
.SYNOPSIS
  Starts the whole local FinPath stack: Postgres (compose), the API, and ng serve.

.DESCRIPTION
  1. Checks Docker is running, .env exists and has the sign-in values the API needs.
  2. Starts the compose Postgres and waits until it is healthy.
  3. Starts the API (local profile, reads .env) in its own PowerShell window and waits for /actuator/health UP.
  4. Starts ng serve in its own PowerShell window (npm install first if web/node_modules is missing).
  5. Opens http://localhost:4200 in the default browser.

  Safe to re-run: a part that is already up is left alone. Stop everything with .\stop-local.ps1.
  Secrets are never printed; the script only reports which .env keys are missing.
#>
[CmdletBinding()]
param()

$root     = $PSScriptRoot
$apiDir   = Join-Path $root 'api'
$webDir   = Join-Path $root 'web'
$envFile  = Join-Path $root '.env'
$stateDir = Join-Path $root '.local-run'
$stateFile = Join-Path $stateDir 'pids.json'

$apiUrl    = 'http://localhost:8080'
$healthUrl = "$apiUrl/actuator/health"
$webUrl    = 'http://localhost:4200'

# .env keys the API refuses to start without (see .env.example). Values are never shown.
$requiredKeys = @('DB_PASSWORD', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'FINPATH_JWT_SECRET')

function Fail([string]$message) {
    Write-Host ''
    Write-Host "STOPPED: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ''
    Write-Host "== $message" -ForegroundColor Cyan
}

function Read-EnvFile([string]$path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $values
}

function Get-Listener([int]$port) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $c) { return $null }
    $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
    $name = 'unknown'
    if ($p) { $name = $p.ProcessName }
    return [pscustomobject]@{ Pid = $c.OwningProcess; Name = $name }
}

function Test-ApiUp {
    try { return ((Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3).status -eq 'UP') } catch { return $false }
}

function Test-WebUp {
    try { return ((Invoke-WebRequest -Uri $webUrl -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) } catch { return $false }
}

function Wait-Until([scriptblock]$ready, [int]$timeoutSec, [System.Diagnostics.Process]$watch, [string]$what) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $dots = 0
    while ((Get-Date) -lt $deadline) {
        if (& $ready) { Write-Host ''; return }
        if ($watch -and $watch.HasExited) {
            Write-Host ''
            Fail "${what}: its window closed before it came up. Re-run it by hand in that folder to see the error."
        }
        Write-Host -NoNewline '.'
        $dots++
        Start-Sleep -Seconds 3
    }
    Write-Host ''
    Fail "$what did not come up within $timeoutSec seconds. Check its window for errors."
}

# ---------------------------------------------------------------- 1. preflight
Step '1/5 Checking Docker and .env'

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Fail 'Docker is not running (or not installed). Start Docker Desktop, wait until it says "running", then run this again.'
}
Write-Host 'Docker is running.'

if (-not (Test-Path -LiteralPath $envFile)) {
    Fail ".env not found in $root. Copy .env.example to .env and fill in the sign-in values (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, FINPATH_JWT_SECRET, DB_PASSWORD)."
}
$envValues = Read-EnvFile $envFile
$missing = @($requiredKeys | Where-Object { -not $envValues.ContainsKey($_) -or -not $envValues[$_] })
if ($missing.Count -gt 0) {
    Fail ".env is missing a value for: $($missing -join ', '). The API will not start without them (see .env.example)."
}
Write-Host '.env found, required keys are set.'

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Fail 'JAVA_HOME is not set to a JDK 21. Set the JAVA_HOME environment variable (the Maven wrapper uses it, not the java on PATH) and open a new terminal.'
}
$javaVersion = (& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "21') {
    Fail "JAVA_HOME ($env:JAVA_HOME) is not a Java 21 JDK. The API needs 21."
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    Fail 'npm was not found on PATH. Install Node.js (LTS) and open a new terminal.'
}

# ---------------------------------------------------------------- 2. database
Step '2/5 Starting Postgres (docker compose)'

Push-Location $root
try {
    & docker compose up -d db
    if ($LASTEXITCODE -ne 0) { Fail 'docker compose could not start the db service (see the message above).' }

    $cid = (& docker compose ps -q db | Select-Object -First 1)
    if (-not $cid) { Fail 'docker compose did not report a db container.' }

    Write-Host -NoNewline 'Waiting for Postgres to be healthy '
    Wait-Until { (& docker inspect --format '{{.State.Health.Status}}' $cid 2>$null) -eq 'healthy' } 120 $null 'Postgres'
}
finally {
    Pop-Location
}
Write-Host "Postgres is healthy on localhost:$(if ($envValues.ContainsKey('DB_PORT')) { $envValues['DB_PORT'] } else { '5433' })."

New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$state = @{ api = $null; web = $null }
if (Test-Path -LiteralPath $stateFile) {
    try {
        $old = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        $state.api = $old.api
        $state.web = $old.web
    } catch { }
}
function Save-State { ($state | ConvertTo-Json) | Set-Content -LiteralPath $stateFile -Encoding ASCII }

# ---------------------------------------------------------------- 3. API
Step '3/5 Starting the API (local profile)'

$apiProc = $null
if (Test-ApiUp) {
    Write-Host "API is already up at $apiUrl - leaving it alone."
}
else {
    $l = Get-Listener 8080
    if ($l) {
        Fail "Port 8080 is in use by $($l.Name) (PID $($l.Pid)) and it is not a healthy FinPath API. Free the port (a compose api container? try: docker compose stop api) and run this again."
    }
    $apiCmd = "`$Host.UI.RawUI.WindowTitle = 'FinPath API'; Set-Location -LiteralPath '$apiDir'; .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'"
    $apiProc = Start-Process -FilePath powershell.exe -PassThru `
        -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', "`"$apiCmd`"")
    $state.api = $apiProc.Id
    Save-State
    Write-Host "API window opened (PID $($apiProc.Id)). First start compiles, so this can take a minute or two."
    Write-Host -NoNewline "Waiting for $healthUrl to report UP "
    Wait-Until { Test-ApiUp } 300 $apiProc 'The API'
    Write-Host 'API is UP.'
}

# ---------------------------------------------------------------- 4. web
Step '4/5 Starting ng serve'

$webProc = $null
if (Test-WebUp) {
    Write-Host "The web app is already up at $webUrl - leaving it alone."
}
else {
    $l = Get-Listener 4200
    if ($l) {
        Fail "Port 4200 is in use by $($l.Name) (PID $($l.Pid)) and it is not serving the web app. Free the port and run this again."
    }
    $installNote = ''
    if (-not (Test-Path -LiteralPath (Join-Path $webDir 'node_modules'))) {
        $installNote = ' (running npm install first, which takes a few minutes)'
    }
    $webCmd = "`$Host.UI.RawUI.WindowTitle = 'FinPath web (ng serve)'; Set-Location -LiteralPath '$webDir'; " +
        "if (-not (Test-Path node_modules)) { npm.cmd install; if (`$LASTEXITCODE -ne 0) { Write-Host 'npm install failed' -ForegroundColor Red; return } }; " +
        "npm.cmd start"
    $webProc = Start-Process -FilePath powershell.exe -PassThru `
        -ArgumentList @('-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', "`"$webCmd`"")
    $state.web = $webProc.Id
    Save-State
    Write-Host "ng serve window opened (PID $($webProc.Id))$installNote."
    Write-Host -NoNewline "Waiting for $webUrl "
    Wait-Until { Test-WebUp } 600 $webProc 'ng serve'
    Write-Host 'Web app is serving.'
}

# ---------------------------------------------------------------- 5. browser
Step '5/5 Opening the browser'
Start-Process $webUrl

Write-Host ''
Write-Host 'FinPath is up:' -ForegroundColor Green
Write-Host "  Web app   $webUrl"
Write-Host "  API       $apiUrl   (health: $healthUrl)"
Write-Host "  Postgres  localhost:$(if ($envValues.ContainsKey('DB_PORT')) { $envValues['DB_PORT'] } else { '5433' })"
Write-Host ''
Write-Host 'Sign-in needs the Google redirect URI http://localhost:8080/login/oauth2/code/google registered on your OAuth client.'
Write-Host 'Stop everything with .\stop-local.ps1'
