param(
    [switch]$Plan,
    [switch]$Lendora,
    [ValidateRange(10, 1800)]
    [int]$StartupTimeoutSeconds = 240,
    [string]$ObservabilityAgent
)

$ErrorActionPreference = 'Stop'

$portal = @{
    Name = 'Kafka UI'
    Folder = 'kafka-ui-portal\kafka-ui-portal'
    Port = 3001
    ReadyPath = '/api/health'
    Command = 'if exist node_modules\vite\package.json (call npm run build && call npm start) else (call npm ci && call npm run build && call npm start)'
}
$services = @(
    @{
        Name = 'Market data'
        Folder = 'market-data-service'
        Port = 8080
        ReadyPath = '/actuator/health'
        Command = 'call mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=update'
    },
    @{
        Name = 'Lender FIX adapter'
        Folder = 'fix-adapter-service'
        Port = 8083
        ReadyPath = '/api/v1/adapter/status'
        Command = 'call run-lender.cmd'
    },
    @{
        Name = 'Borrower FIX adapter'
        Folder = 'fix-adapter-service'
        Port = 8082
        ReadyPath = '/api/v1/adapter/status'
        Command = 'call run-borrower.cmd'
    },
    @{
        Name = 'Borrower service'
        Folder = 'borrower-service'
        Port = 8081
        ReadyPath = '/actuator/health'
        Command = 'call mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--lendora.auth-enabled=false'
    },
    @{
        Name = 'Lender service'
        Folder = 'lender-service'
        Port = 8084
        ReadyPath = '/actuator/health'
        Command = 'call mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--lendora.auth-enabled=false'
    },
    @{
        Name = 'Lifecycle service'
        Folder = 'lifecycle-service'
        Port = 8085
        ReadyPath = '/actuator/health'
        Command = 'call mvnw.cmd spring-boot:run'
    }
)
$frontend = @{
    Name = 'Lending test frontend'
    ExpectedTitle = 'Lending lab'
    Folder = 'security-lending-test-demo'
    Port = 5176
    ReadyPath = '/'
    Command = 'if exist node_modules\vite\package.json (call npm run dev) else (call npm ci && call npm run dev)'
}

$frontends = @($frontend)
if ($Lendora) {
    foreach ($service in $services) {
        if ($service.Port -in @(8081, 8084)) {
            $service.Command = 'call mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--lendora.auth-enabled=true'
        }
    }
    $services += @{
        Name = 'Lendora account service'
        Folder = 'lendora-service'
        Port = 8086
        ReadyPath = '/api/v1/auth/me'
        Command = 'call mvnw.cmd spring-boot:run'
    }
    $frontends = @(
        @{ Name = 'BorrowDesk'; Folder = 'borrowdesk'; Port = 5173; ReadyPath = '/'; ExpectedTitle = 'BorrowDesk' },
        @{ Name = 'LendDesk'; Folder = 'lenddesk'; Port = 5175; ReadyPath = '/'; ExpectedTitle = 'lenddesk' },
        @{ Name = 'Lendora'; Folder = 'lendora'; Port = 5176; ReadyPath = '/'; ExpectedTitle = 'Lendora' }
    )
    foreach ($app in $frontends) {
        $app.Command = "if exist node_modules\vite\package.json (call npm run dev -- --port $($app.Port) --strictPort) else (call npm ci && call npm run dev -- --port $($app.Port) --strictPort)"
    }
}

function Initialize-LendoraKey {
    if (-not [string]::IsNullOrWhiteSpace($env:LENDORA_PROVISIONING_KEY)) { return }
    $directory = Join-Path $PSScriptRoot 'runtime'
    $path = Join-Path $directory 'lendora-provisioning.key'
    if (-not (Test-Path -LiteralPath $path)) {
        $null = New-Item -ItemType Directory -Path $directory -Force
        $bytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        $value = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        [IO.File]::WriteAllText($path, $value)
    }
    $env:LENDORA_PROVISIONING_KEY = [IO.File]::ReadAllText($path).Trim()
    if ($env:LENDORA_PROVISIONING_KEY.Length -lt 32) {
        throw 'The saved provisioning key is invalid. Set LENDORA_PROVISIONING_KEY or replace runtime/lendora-provisioning.key.'
    }
    Write-Host 'Using the saved local provisioning key for all three backend services.'
}

function Test-LoginRequired([int]$Port, [string]$Path) {
    try {
        $null = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 4
        return $false
    } catch {
        return $null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401
    }
}

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-LocalJson([int]$Port, [string]$Path) {
    try {
        Invoke-RestMethod -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 4
    } catch {
        $null
    }
}

function Wait-Ready([string]$Name, [scriptblock]$Check) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $result = & $Check
        if ($result) { return $result }
        Start-Sleep -Seconds 2
    } while ($timer.Elapsed.TotalSeconds -lt $StartupTimeoutSeconds)
    throw "$Name did not become ready within $StartupTimeoutSeconds seconds. Check its Command Prompt for the startup error."
}

function Start-Component($Component) {
    $directory = Join-Path $PSScriptRoot $Component.Folder
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw "Missing service folder: $directory"
    }
    if (Test-Port $Component.Port) {
        Write-Host "Checking existing $($Component.Name) on port $($Component.Port)..."
        if (-not $Lendora -and $Component.Port -in @(8081, 8084) -and (Test-LoginRequired $Component.Port '/api/v1/organizations')) {
            throw "The existing $($Component.Name) requires Lendora login. Stop the borrower and lender backend windows, then rerun start-demo.cmd without -Lendora for the test console. The launcher will not silently reuse authenticated backends in anonymous test mode."
        }
        if ($Lendora -and $Component.Port -in @(8081, 8084)) {
            if (-not (Test-LoginRequired $Component.Port '/api/v1/organizations')) {
                throw "Stop the existing $($Component.Name) and rerun in Lendora mode so authentication and the shared key are loaded."
            }
        }
        if ($Component.ExpectedTitle) {
            $existing = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Component.Port)/" -TimeoutSec 4
            if ($existing.Content -notmatch "<title>[^<]*$([regex]::Escape($Component.ExpectedTitle))") {
                throw "Port $($Component.Port) is occupied by another frontend. Stop it before starting $($Component.Name)."
            }
        }
    } else {
        Write-Host "Starting $($Component.Name) on port $($Component.Port)..."
        $launch = "/d /k title $($Component.Name) & $($Component.Command)"
        if ($ObservabilityAgent -and $Component.Port -in (8080..8086)) {
            $previousMavenArgs = $env:MAVEN_ARGS
            $previousServiceName = $env:OTEL_SERVICE_NAME
            try {
                $env:MAVEN_ARGS = ('{0} "-Dspring-boot.run.agents={1}"' -f
                    $previousMavenArgs, $ObservabilityAgent).Trim()
                $env:OTEL_SERVICE_NAME = switch ($Component.Port) {
                    8082 { 'borrower-fix-adapter' }
                    8083 { 'lender-fix-adapter' }
                    default { $Component.Folder }
                }
                Start-Process -FilePath $env:ComSpec -WorkingDirectory $directory -WindowStyle Normal -ArgumentList $launch
            } finally {
                $env:MAVEN_ARGS = $previousMavenArgs
                $env:OTEL_SERVICE_NAME = $previousServiceName
            }
        } else {
            Start-Process -FilePath $env:ComSpec -WorkingDirectory $directory -WindowStyle Normal -ArgumentList $launch
        }
    }
    $null = Wait-Ready $Component.Name {
        if ($Component.ReadyPath -eq '/api/v1/auth/me') { return Test-LoginRequired $Component.Port $Component.ReadyPath }
        if ($Component.ReadyPath -eq '/') {
            try {
                $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Component.Port)/" -TimeoutSec 4
                return $page.StatusCode -eq 200
            } catch { return $false }
        }
        $status = Get-LocalJson $Component.Port $Component.ReadyPath
        if ($Component.ReadyPath -eq '/actuator/health') { return $status.status -eq 'UP' }
        if ($Component.ReadyPath -eq '/api/v1/adapter/status') { return $status.success -eq $true }
        return $null -ne $status.online
    }
}

function Invoke-PortalPost([string]$Path) {
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:3001$Path" `
        -ContentType 'application/json' -Body '{}' -TimeoutSec 60
}

try {
    if ($Plan) {
        @($portal) + $services + $frontends | ForEach-Object {
            $directory = Join-Path $PSScriptRoot $_.Folder
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
                throw "Missing service folder: $directory"
            }
            Write-Host "$($_.Port)  $($_.Name)  [$directory]"
        }
        exit 0
    }

    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
        throw 'npm was not found on PATH. Install Node.js before starting the demo.'
    }
    if (-not (Test-Port 5432)) {
        throw 'Start PostgreSQL on port 5432 and create the databases listed in START_DEMO.md.'
    }

    if ($Lendora) {
        foreach ($port in @(8081, 8082, 8083, 8084, 8086)) {
            if (Test-Port $port) {
                throw "Port $port already has a backend running. Stop the borrower, lender, Lendora and both FIX adapter windows first, then rerun to load the shared provisioning key and routing mode consistently."
            }
        }
        Initialize-LendoraKey
        $env:LENDORA_ROUTING_ENABLED = 'true'
        Write-Host 'Lendora mode requires the lendora_db PostgreSQL database in addition to the existing demo databases.'
    }

    if ($ObservabilityAgent) {
        $ObservabilityAgent = (Resolve-Path -LiteralPath $ObservabilityAgent).Path
        foreach ($service in $services) {
            if (Test-Port $service.Port) {
                throw "Stop $($service.Name) on port $($service.Port) before starting with observability."
            }
        }
        $env:SPRING_PROFILES_INCLUDE = (@($env:SPRING_PROFILES_INCLUDE, 'observability') | Where-Object { $_ }) -join ','
        if (-not $env:OTEL_EXPORTER_OTLP_ENDPOINT) { $env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://127.0.0.1:4318' }
        $env:OTEL_EXPORTER_OTLP_PROTOCOL = 'http/protobuf'
        $env:OTEL_TRACES_EXPORTER = 'otlp'
        $env:OTEL_METRICS_EXPORTER = 'none'
        $env:OTEL_LOGS_EXPORTER = 'none'
        $env:OTEL_PROPAGATORS = 'tracecontext'
    }

    Start-Component $portal
    $health = Get-LocalJson 3001 '/api/health'
    if ($health.bootstrap -notin @('localhost:9092', '127.0.0.1:9092')) {
        throw "The portal uses Kafka at '$($health.bootstrap)'. This demo expects localhost:9092."
    }
    if (-not $health.online -and $health.status -notin @('initializing', 'starting')) {
        if (-not $health.initialized) { $null = Invoke-PortalPost '/api/kafka/initialize' }
        $null = Invoke-PortalPost '/api/kafka/start'
    }
    $null = Wait-Ready 'Kafka broker' { (Get-LocalJson 3001 '/api/health').online }
    $topics = Invoke-PortalPost '/api/topics/lending-preset'
    Write-Host $topics.message

    foreach ($service in $services) { Start-Component $service }
    $null = Wait-Ready 'Borrower-to-lender FIX session' {
        $adapter = Get-LocalJson 8082 '/api/v1/adapter/status'
        @($adapter.data.sessions | Where-Object { $_.loggedOn }).Count -gt 0
    }
    foreach ($app in $frontends) { Start-Component $app }

    Write-Host ''
    if ($Lendora) {
        Write-Host 'Lendora:    http://localhost:5176'
        Write-Host 'BorrowDesk: http://localhost:5173'
        Write-Host 'LendDesk:   http://localhost:5175'
    } else {
        Write-Host 'Test frontend: http://127.0.0.1:5176'
    }
    Write-Host 'Kafka UI:      http://127.0.0.1:3001'
    Write-Host 'Each application runs in its own Command Prompt.'
} catch {
    Write-Host "Startup failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
