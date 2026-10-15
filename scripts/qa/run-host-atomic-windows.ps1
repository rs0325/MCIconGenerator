param(
    [Parameter(Mandatory=$true)][string]$Java,
    [Parameter(Mandatory=$true)][string]$Bundle,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [Parameter(Mandatory=$true)][string]$InventorySha256,
    [ValidateSet('normal','outer-deadline')][string]$Mode = 'normal'
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputRoot) { throw 'OutputRootは新規directoryが必要' }
$inventory = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Bundle 'file-hashes.json')).Hash.ToLowerInvariant()
if ($inventory -ne $InventorySha256) { throw 'inventory SHA256不一致' }
$Java = (Resolve-Path -LiteralPath $Java).Path
$Bundle = (Resolve-Path -LiteralPath $Bundle).Path
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path
$result = @{ passed = $false; mode = $Mode; inventorySha256 = $InventorySha256 }
$process = $null
$stdout = $null
$stderr = $null
try {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Java
    $info.WorkingDirectory = $Bundle
    $probeMode = if ($Mode -eq 'outer-deadline') { 'deadline' } else { 'normal' }
    $info.Arguments = '-Xmx128m -cp "classes;lib/*" HostAtomicRecoveryProbe "' + (Join-Path $OutputRoot 'output') + '" "' + (Join-Path $OutputRoot 'evidence') + '" 75 ' + $probeMode + ' ' + $InventorySha256
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $started = $process.StartTime.ToUniversalTime()
    $result.supervisorPid = $process.Id
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if ($Mode -eq 'outer-deadline') {
        $readyFile = Join-Path $OutputRoot 'evidence\deadline.stdout.log'
        $readyUntil = [DateTime]::UtcNow.AddSeconds(10)
        while (-not ((Test-Path -LiteralPath $readyFile) -and ((Get-Content -LiteralPath $readyFile -Raw) -match 'READY_FOR_ATOMIC_WRITE'))) {
            if ($process.HasExited -or [DateTime]::UtcNow -ge $readyUntil) { throw '外側期限fixtureの子actorがREADYに到達しない' }
            Start-Sleep -Milliseconds 20
        }
        $result.fixtureActorReady = $true
    }
    $milliseconds = if ($Mode -eq 'outer-deadline') { 1000 } else { 100000 }
    if (-not $process.WaitForExit($milliseconds)) { throw 'OUTER_DEADLINE' }
    $result.supervisorExit = $process.ExitCode
    if (-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)) { throw 'OUTPUT_DRAIN_DEADLINE' }
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'supervisor.stdout.log'), $stdout.Result, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'supervisor.stderr.log'), $stderr.Result, [Text.UTF8Encoding]::new($false))
    $report = Get-Content -LiteralPath (Join-Path $OutputRoot 'evidence\report.json') -Raw | ConvertFrom-Json
    if ($process.ExitCode -ne 0 -or -not $report.passed -or @($report.remainingOwnedActors).Count -ne 0) { throw 'atomic検証失敗' }
    $result.passed = $true
} catch {
    $result.failure = $_.Exception.Message
} finally {
    $killedActors = @()
    $remainingActors = @()
    if ($null -ne $process) {
        try {
            # 先に監督Javaを止め、新しいactorが増えない状態で直接の子だけ照合する。
            if (-not $process.HasExited) {
                $result.killedSupervisor = $true
                $process.Kill()
                if (-not $process.WaitForExit(5000)) { throw '所有supervisorの回収期限超過' }
            }
            $until = $process.ExitTime.ToUniversalTime()
            $result.supervisorExit = $process.ExitCode
            $children = @(Get-CimInstance -ClassName Win32_Process -Filter ('ParentProcessId = ' + $process.Id) -OperationTimeoutSec 5)
            foreach ($child in $children) {
                $created = $child.CreationDate.ToUniversalTime()
                if ($created -ge $started -and $created -le $until -and $child.ExecutablePath -eq $Java -and $child.CommandLine -match '(?:^|\s)(?:AtomicCrashProbe|HostAtomicRecoveryProbe)(?:\s|$)') {
                    $actor = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
                    if ($null -ne $actor -and $actor.StartTime.ToUniversalTime() -ge $started -and $actor.StartTime.ToUniversalTime() -le $until) {
                        $killedActors += $actor.Id
                        $actor.Kill()
                        if (-not $actor.WaitForExit(5000)) { $remainingActors += $actor.Id }
                    }
                }
            }
            if ($killedActors.Count -gt 0 -or $remainingActors.Count -gt 0) { $result.passed = $false }
        } catch {
            $result.cleanupFailure = $_.Exception.Message
            $result.passed = $false
        }
        if ($null -ne $stdout -and $null -ne $stderr) {
            try {
                if (-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)) { throw 'OUTPUT_DRAIN_DEADLINE' }
                [IO.File]::WriteAllText((Join-Path $OutputRoot 'supervisor.stdout.log'), $stdout.Result, [Text.UTF8Encoding]::new($false))
                [IO.File]::WriteAllText((Join-Path $OutputRoot 'supervisor.stderr.log'), $stderr.Result, [Text.UTF8Encoding]::new($false))
            } catch { $result.drainFailure = $_.Exception.Message; $result.passed = $false }
        }
        $result.supervisorRemaining = -not $process.HasExited
        if ($result.supervisorRemaining) { $result.passed = $false }
    }
    $result.cleanupKilledActorPids = $killedActors
    $result.remainingOwnedActors = $remainingActors
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'outer-report.json'), ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
}
if (-not $result.passed) { throw 'atomic検証の外側監督がFAILを記録した' }
Write-Output 'atomic PASS'
