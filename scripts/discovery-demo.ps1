param(
    [switch]$Plan,
    [ValidateRange(1, 2)][int]$Replicas = 2,
    [ValidateSet('Start', 'Status', 'Stop', 'Restart')][string]$Action = 'Start',
    [string]$Instance,
    [string]$Worker,
    [int]$StartupTimeoutSeconds = 240
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$state = Join-Path $root 'runtime\discovery'
function Json-Read($Path) { if (Test-Path $Path) { Get-Content -Raw $Path | ConvertFrom-Json } }
function Json-Write($Path, $Value) {
    $temp = "$Path.$PID.tmp"
    [IO.File]::WriteAllText($temp, ($Value | ConvertTo-Json -Depth 10))
    Move-Item -Force $temp $Path
}
function Owned-Process([int]$IdValue, $Started) {
    if ($IdValue -le 0 -or -not $Started) { return $null }
    $process = Get-Process -Id $IdValue -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    try {
        # ConvertFrom-Json may return a DateTime rather than a string, depending on PowerShell version.
        $expected = if ($Started -is [DateTime]) { $Started.ToUniversalTime() } else {
            [DateTime]::Parse([string]$Started, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
        }
        if ($process.StartTime.ToUniversalTime().Ticks -eq $expected.Ticks) { return $process }
    } catch { return $null }
    return $null
}
function Port-Open([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try { return $client.ConnectAsync('127.0.0.1', $Port).Wait(500) -and $client.Connected }
    catch { return $false } finally { $client.Dispose() }
}
function Wait-Check($Name, [scriptblock]$Check) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Check) { return }
        Start-Sleep -Seconds 2
    } while ($timer.Elapsed.TotalSeconds -lt $StartupTimeoutSeconds)
    throw "$Name was not ready within $StartupTimeoutSeconds seconds. Inspect runtime/discovery logs."
}
function Initialize-LendingTopics {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        try {
            return Invoke-RestMethod -Method Post 'http://localhost:3001/api/topics/lending-preset' -ContentType 'application/json' -Body '{"partitions":6}' -TimeoutSec 30
        } catch {
            $failure = $_
            $detail = try { $failure.ErrorDetails.Message | ConvertFrom-Json -ErrorAction Stop } catch { $null }
            if ($detail.code -notin @('LEADER_NOT_AVAILABLE', 'NOT_LEADER_FOR_PARTITION')) { throw }
            if ($timer.Elapsed.TotalSeconds -ge $StartupTimeoutSeconds) {
                throw "Kafka lending partition leaders were not ready within $StartupTimeoutSeconds seconds. Verify that Relay is connected to the intended broker on port 9092 and check the broker logs. Kafka reported: $($detail.error)"
            }
            Write-Host 'Waiting for Kafka lending partition leaders...'
            Start-Sleep -Seconds 2
        }
    } while ($true)
}
function Initialize-Key {
    if ($env:LENDORA_PROVISIONING_KEY) { return }
    $path = Join-Path $root 'runtime\lendora-provisioning.key'
    if (-not (Test-Path $path)) {
        $bytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        [IO.File]::WriteAllText($path, [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
    }
    $env:LENDORA_PROVISIONING_KEY = [IO.File]::ReadAllText($path).Trim()
    if ($env:LENDORA_PROVISIONING_KEY.Length -lt 32) { throw 'Invalid local provisioning key.' }
}
# Supervisors own only their recorded child tree. The process start time prevents PID reuse mistakes.
if ($Worker) {
    $definition = Json-Read (Join-Path $state "$Worker.config.json")
    if (-not $definition) { throw "Missing definition for $Worker" }
    Initialize-Key
    $env:LENDORA_ROUTING_ENABLED = 'true'
    foreach ($property in $definition.Environment.PSObject.Properties) { Set-Item "env:$($property.Name)" $property.Value }
    $stop = Join-Path $state "$Worker.stop"
    $statusPath = Join-Path $state "$Worker.status.json"
    $failures = @()
    $consecutive = 0
    while (-not (Test-Path $stop)) {
        foreach ($stream in @('stdout', 'stderr')) {
            $log = Join-Path $state "$Worker.$stream.log"
            if (Test-Path $log) { Move-Item -Force $log (Join-Path $state "$Worker.$stream.previous.log") }
        }
        $child = Start-Process -FilePath $definition.Executable -ArgumentList $definition.Arguments -WorkingDirectory (Join-Path $root $definition.Folder) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $state "$Worker.stdout.log") -RedirectStandardError (Join-Path $state "$Worker.stderr.log")
        $childStartTime = $child.StartTime.ToUniversalTime()
        $childStart = $childStartTime.ToString('o')
        Json-Write $statusPath @{ Name = $Worker; Port = $definition.Port; State = 'Running'; Pid = $child.Id; Started = $childStart; Supervisor = $PID; SupervisorStarted = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o'); Restarts = $consecutive }
        while (-not $child.HasExited -and -not (Test-Path $stop)) { Start-Sleep -Seconds 1; $child.Refresh() }
        if (Test-Path $stop) {
            if (Owned-Process $child.Id $childStart) { & taskkill.exe /PID $child.Id /T /F | Out-Null }
            Json-Write $statusPath @{ Name = $Worker; Port = $definition.Port; State = 'Stopped' }
            exit 0
        }
        $now = [DateTime]::UtcNow
        $failures = @($failures | Where-Object { $_ -gt $now.AddMinutes(-10) }) + @($now)
        if (($now - $childStartTime).TotalMinutes -ge 10) { $consecutive = 0 }
        $consecutive++
        if ($failures.Count -ge 5) {
            Json-Write $statusPath @{ Name = $Worker; Port = $definition.Port; State = 'CrashLimit'; ExitCode = $child.ExitCode }
            exit 1
        }
        $delay = @(5, 15, 30, 60)[[Math]::Min($consecutive - 1, 3)]
        Json-Write $statusPath @{ Name = $Worker; Port = $definition.Port; State = 'Restarting'; Delay = $delay; Supervisor = $PID; SupervisorStarted = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o') }
        # Wait in short intervals so Stop also interrupts backoff.
        for ($i = 0; $i -lt $delay -and -not (Test-Path $stop); $i++) { Start-Sleep -Seconds 1 }
    }
    Json-Write $statusPath @{ Name = $Worker; Port = $definition.Port; State = 'Stopped' }
    exit 0
}
function Start-Owned($Definition) {
    $name = $Definition.Name
    $previous = Json-Read (Join-Path $state "$name.status.json")
    if ($previous.Supervisor -and (Owned-Process $previous.Supervisor $previous.SupervisorStarted)) { throw "$name is already supervised. Use -Action Status or Restart." }
    if (Port-Open $Definition.Port) { throw "Port $($Definition.Port) is in use; no unrelated process will be stopped." }
    Json-Write (Join-Path $state "$name.config.json") $Definition
    Remove-Item (Join-Path $state "$name.stop") -ErrorAction SilentlyContinue
    $supervisor = Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Worker $name" -PassThru -WindowStyle Hidden
    Wait-Check $name {
        $status = Json-Read (Join-Path $state "$name.status.json")
        if ($status.State -eq 'CrashLimit' -or $supervisor.HasExited) { throw "$name failed to start. Inspect runtime/discovery/$name.stderr.log" }
        try {
            $health = Invoke-RestMethod "http://localhost:$($Definition.Port)$($Definition.ReadyPath)" -TimeoutSec 3
            if ($Definition.Kind -eq 'java') { return $health.status -eq 'UP' }
            return $true
        } catch { return $false }
    }
    if ($name -eq 'gateway') {
        foreach ($prefix in @('accounts', 'borrower', 'lender')) {
            $probePath = if ($prefix -eq 'accounts') { 'auth/me' } else { 'organizations' }
            Wait-Check "Gateway $prefix route" {
                try {
                    $response = Invoke-WebRequest -UseBasicParsing "http://localhost:8090/$prefix/api/v1/$probePath" -TimeoutSec 3
                    return -not [string]::IsNullOrWhiteSpace($response.Headers['X-Service-Instance'])
                } catch {
                    try {
                        return $null -ne $_.Exception.Response -and -not [string]::IsNullOrWhiteSpace(($_.Exception.Response.Headers.GetValues('X-Service-Instance') -join ','))
                    } catch { return $false }
                }
            }
        }
    }
}
function Stop-Owned($Name) {
    $status = Json-Read (Join-Path $state "$Name.status.json")
    if (-not $status) { return }
    [IO.File]::WriteAllText((Join-Path $state "$Name.stop"), 'stop')
    if ($status.Supervisor -and (Owned-Process $status.Supervisor $status.SupervisorStarted)) {
        Wait-Check "Stop $Name" { -not (Owned-Process $status.Supervisor $status.SupervisorStarted) }
    } elseif ($status.Pid -and (Owned-Process $status.Pid $status.Started)) {
        & taskkill.exe /PID $status.Pid /T /F | Out-Null
    }
}
if ($Action -ne 'Start') {
    $names = @(Get-ChildItem $state -Filter '*.config.json' -ErrorAction SilentlyContinue | ForEach-Object { Json-Read $_.FullName } | Sort-Object Order | ForEach-Object { $_.Name })
    if ($Action -eq 'Restart' -and -not $Instance) {
        $manifest = Json-Read (Join-Path $state 'manifest.json')
        if ($manifest) { $names = @($manifest.Instances | Where-Object { $_ -in $names }) }
    }
    if ($Instance) {
        if ($Instance -notin $names) { throw "Unknown instance: $Instance" }
        $names = @($Instance)
    }
    if ($Plan) { Write-Host "$Action : $($names -join ', ')"; exit 0 }
    if ($Action -eq 'Status') {
        foreach ($name in $names) {
            $status = Json-Read (Join-Path $state "$name.status.json")
            if ($status.State -eq 'Running' -and -not (Owned-Process $status.Pid $status.Started)) { $status.State = 'Exited' }
            $status | Select-Object Name, Port, State, Pid, Restarts
        }
    } else {
        foreach ($name in $names) { Stop-Owned $name }
        if ($Action -eq 'Restart') { foreach ($name in $names) { Start-Owned (Json-Read (Join-Path $state "$name.config.json")) } }
    }
    exit 0
}
if ($Instance) { throw 'Use -Instance with Status, Stop or Restart.' }
$definitions = @()
function Java-Definition($Name, $Folder, $Port, $Profiles = 'discovery', $Extra = '') {
    return @{ Name = $Name; Folder = $Folder; Port = $Port; Kind = 'java'; ReadyPath = '/actuator/health/readiness'; Executable = 'java.exe'; Arguments = "-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -jar target/$Folder-0.0.1-SNAPSHOT.jar --spring.profiles.active=$Profiles --server.port=$Port $Extra"; Environment = @{} }
}
$definitions += Java-Definition 'registry' 'discovery-server' 8761 '' '--management.endpoints.web.exposure.include=health,info,metrics'
$definitions += Java-Definition 'market' 'market-data-service' 8080 'discovery' '--spring.jpa.hibernate.ddl-auto=update'
$definitions += Java-Definition 'accounts-1' 'lendora-service' 8086
$definitions += Java-Definition 'borrower-1' 'borrower-service' 8081 'discovery' '--lendora.auth-enabled=true'
$definitions += Java-Definition 'lender-1' 'lender-service' 8084 'discovery' '--lendora.auth-enabled=true'
$definitions += Java-Definition 'lender-fix' 'fix-adapter-service' 8083 'lender,discovery'
$definitions += Java-Definition 'borrower-fix' 'fix-adapter-service' 8082 'borrower,discovery'
$definitions += Java-Definition 'lifecycle-1' 'lifecycle-service' 8085
if ($Replicas -eq 2) {
    $definitions += Java-Definition 'accounts-2' 'lendora-service' 18086
    $definitions += Java-Definition 'borrower-2' 'borrower-service' 18081 'discovery' '--lendora.auth-enabled=true'
    $definitions += Java-Definition 'lender-2' 'lender-service' 18084 'discovery' '--lendora.auth-enabled=true'
    $definitions += Java-Definition 'lifecycle-2' 'lifecycle-service' 18085
}
$definitions += Java-Definition 'gateway' 'api-gateway' 8090 ''
$frontendEnvironment = @{ VITE_API_BASE_URL = 'http://localhost:8090/borrower'; VITE_LENDER_API_URL = 'http://localhost:8090/lender'; VITE_LENDORA_API_URL = 'http://localhost:8090/accounts/api/v1'; VITE_AUTH_API_URL = 'http://localhost:8090/accounts/api/v1'; VITE_MARKET_DATA_API = 'http://localhost:8090/market'; VITE_LENDORA_AUTH_REQUIRED = 'true' }
foreach ($app in @(@('borrowdesk', 5173), @('lenddesk', 5175), @('lendora', 5176))) {
    $definitions += @{ Name = $app[0]; Folder = $app[0]; Port = $app[1]; Kind = 'node'; ReadyPath = '/'; Executable = 'cmd.exe'; Arguments = "/d /s /c `"npm.cmd run dev -- --port $($app[1]) --strictPort`""; Environment = $frontendEnvironment }
}
Write-Host "Discovery demo: $Replicas replica(s) per core service, one gateway, one market service and one FIX adapter per side."
Write-Host "Database maximum: $(11 + 16 * $Replicas) connections, plus PostgreSQL maintenance reserve. JVM heaps bounded at 384 MB/process; 16 GB machine recommended."
if ($Plan) { $definitions | ForEach-Object { Write-Host "$($_.Name) : $($_.Port) [$($_.Folder)]" }; exit 0 }
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) { throw 'Install Java 17+ and add java.exe to PATH.' }
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Install Node.js and add npm.cmd to PATH.' }
if (-not (Port-Open 5432)) { throw 'Start PostgreSQL on 5432 and create the databases in START_DEMO.md.' }
foreach ($definition in $definitions) { if (Port-Open $definition.Port) { throw "Port $($definition.Port) is already in use. Stop existing demo instances first." } }
$null = New-Item -ItemType Directory -Force $state
Json-Write (Join-Path $state 'manifest.json') @{ Instances = @($definitions | ForEach-Object { $_.Name }) }
Initialize-Key
$env:LENDORA_ROUTING_ENABLED = 'true'
# Build each jar once, regardless of replica count. Stop immediately on a failed build.
foreach ($folder in @($definitions | Where-Object { $_.Kind -eq 'java' } | Select-Object -ExpandProperty Folder -Unique)) {
    Push-Location (Join-Path $root $folder)
    try { & .\mvnw.cmd -DskipTests package; if ($LASTEXITCODE -ne 0) { throw "Build failed: $folder" } } finally { Pop-Location }
}
foreach ($folder in @('borrowdesk', 'lenddesk', 'lendora', 'kafka-ui-portal\kafka-ui-portal')) {
    Push-Location (Join-Path $root $folder)
    try { if (-not (Test-Path 'node_modules\vite\package.json')) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw "npm ci failed: $folder" } } } finally { Pop-Location }
}
if (-not (Port-Open 3001)) {
    $portal = @{ Name = 'kafka-portal'; Folder = 'kafka-ui-portal\kafka-ui-portal'; Port = 3001; Kind = 'node'; ReadyPath = '/api/health'; Executable = 'cmd.exe'; Arguments = '/d /s /c "npm.cmd run build && npm.cmd start"'; Environment = @{} }
    $portal.Order = -1
    $manifest = Json-Read (Join-Path $state 'manifest.json')
    $manifest.Instances = @('kafka-portal') + @($manifest.Instances)
    Json-Write (Join-Path $state 'manifest.json') $manifest
    Start-Owned $portal
}
$health = Invoke-RestMethod 'http://localhost:3001/api/health'
if ($health.bootstrap -notin @('localhost:9092', '127.0.0.1:9092')) { throw 'Kafka UI must use the local broker on 9092.' }
if (-not $health.online) {
    if (-not $health.initialized) { $null = Invoke-RestMethod -Method Post 'http://localhost:3001/api/kafka/initialize' -ContentType 'application/json' -Body '{}' -TimeoutSec 60 }
    $null = Invoke-RestMethod -Method Post 'http://localhost:3001/api/kafka/start' -ContentType 'application/json' -Body '{}' -TimeoutSec 60
}
Wait-Check 'Kafka' { try { return (Invoke-RestMethod 'http://localhost:3001/api/health').online } catch { return $false } }
$topics = Initialize-LendingTopics
if (@($topics.undersized).Count -gt 0) { throw 'Existing topics need a controlled partition migration. See SCALABILITY.md; no topic was deleted or resized.' }
$order = 0
foreach ($definition in $definitions) {
    $definition.Order = $order++
    if ($definition.Name -eq 'gateway') {
        Wait-Check 'Eureka registrations' {
            try {
                $apps = Invoke-RestMethod 'http://localhost:8761/eureka/apps' -Headers @{ Accept = 'application/json' }
                foreach ($service in @('BORROWER-SERVICE', 'LENDER-SERVICE', 'LENDORA-SERVICE', 'LIFECYCLE-SERVICE')) {
                    $app = @($apps.applications.application | Where-Object { $_.name -eq $service })
                    if (@($app.instance | Where-Object { $_.status -eq 'UP' }).Count -lt $Replicas) { return $false }
                }
                return $true
            } catch { return $false }
        }
    }
    Start-Owned $definition
}
Write-Host 'Ready: Lendora http://localhost:5176 | BorrowDesk :5173 | LendDesk :5175 | Gateway :8090 | Eureka :8761'
Write-Host 'Status/Stop/Restart: start-demo.cmd -Discovery -Action Status|Stop|Restart [-Instance borrower-2]'
