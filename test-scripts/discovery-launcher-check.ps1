$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$workerScript = Join-Path $root 'scripts/discovery-demo.ps1'
$workerAst = $null
foreach ($path in @((Join-Path $root 'start-demo.ps1'), $workerScript)) {
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if ($errors) { throw ($errors | Out-String) }
    if ($path -eq $workerScript) { $workerAst = $ast }
}
$function = $workerAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Owned-Process'
}, $true)
Invoke-Expression $function.Extent.Text
$stamp = (Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
if (-not (Owned-Process $PID $stamp)) { throw 'String ownership check failed.' }
$date = [DateTime]::Parse($stamp)
if (-not (Owned-Process $PID $date)) { throw 'JSON DateTime ownership check failed.' }
if (Owned-Process $PID $date.AddSeconds(-1)) { throw 'Stale PID/start-time pair was accepted.' }
$buildFolderFunction = $workerAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-JavaBuildFolders'
}, $true)
Invoke-Expression $buildFolderFunction.Extent.Text
$buildFolders = Get-JavaBuildFolders @(
    @{ Kind = 'java'; Folder = 'lender-service' }
    @{ Kind = 'java'; Folder = 'lender-service' }
    @{ Kind = 'node'; Folder = 'lenddesk' }
    @{ Kind = 'java'; Folder = 'api-gateway' }
)
if (($buildFolders -join ',') -ne 'lender-service,api-gateway') {
    throw 'Java build folders must exclude frontends and build each replicated service once.'
}
function Runtime-Snapshot {
    return (@(Get-ChildItem (Join-Path $root 'runtime') -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object FullName | ForEach-Object { "$($_.FullName):$($_.Length):$($_.LastWriteTimeUtc.Ticks)" }) -join "`n")
}
$before = Runtime-Snapshot
$output = & (Join-Path $root 'start-demo.ps1') -Lendora -Discovery -Replicas 2 -Plan 6>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Discovery dry run failed.' }
foreach ($expected in @('accounts-2 : 18086', 'borrower-2 : 18081', 'lender-2 : 18084', 'lifecycle-2 : 18085', 'gateway : 8090', '43 connections')) {
    if (-not $output.Contains($expected)) { throw "Missing plan entry: $expected" }
}
$null = & (Join-Path $root 'start-demo.ps1') -Plan 6>&1
if ($LASTEXITCODE -ne 0) { throw 'Standalone dry run failed.' }
if ($before -ne (Runtime-Snapshot)) { throw 'Dry run changed runtime files.' }
Write-Output 'Launcher syntax, PID ownership, replica ports, budget and read-only dry runs passed.'
