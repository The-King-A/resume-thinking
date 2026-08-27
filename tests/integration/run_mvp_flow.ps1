[CmdletBinding()]
param(
    [switch]$RequireLive,
    [switch]$NoStart,
    [int]$ReadyTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$javaRoot = Join-Path $repoRoot 'back\java'
$pythonRoot = Join-Path $repoRoot 'back\python'
$assertionScript = Join-Path $PSScriptRoot 'assert_mvp_flow.py'
$javaBase = if ($env:MVP_API_BASE_URL) { $env:MVP_API_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8080' }
$pythonBase = if ($env:MVP_PYTHON_BASE_URL) { $env:MVP_PYTHON_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8000' }
$strict = $RequireLive -or $env:MVP_REQUIRE_LIVE -eq '1'
$startedProcesses = @()
$exitCode = 0
$pythonExe = $null
$liveAttempted = $false

function Write-Flow {
    param([string]$Message)
    Write-Output "[flow] $Message"
}

function Import-DotEnv {
    $envPath = Join-Path $repoRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) { return $false }
    foreach ($line in Get-Content -LiteralPath $envPath) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
            $name = $Matches[1]
            $value = $Matches[2]
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            # An explicitly exported process value wins over .env. Values are
            # never echoed, persisted, or included in child-process arguments.
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    return $true
}

function Test-ConfiguredValue {
    param([string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    return -not [string]::IsNullOrWhiteSpace($value) -and $value -notmatch '(?i)replace-with|change[-_ ]?me|placeholder'
}

function Test-Health {
    param([string]$Url)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri $Url
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Test-TcpPort {
    param([string]$Host, [int]$Port)
    if ([string]::IsNullOrWhiteSpace($Host) -or $Port -lt 1 -or $Port -gt 65535) { return $false }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($Host, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false }
    finally { $client.Dispose() }
}

function Test-RedisPing {
    param([string]$Host, [int]$Port)
    if (-not (Test-TcpPort $Host $Port)) { return $false }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($Host, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1000)) { return $false }
        $client.EndConnect($pending)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 1000
        $stream.WriteTimeout = 1000
        $ping = [Text.Encoding]::ASCII.GetBytes("*1`r`n`$4`r`nPING`r`n")
        $stream.Write($ping, 0, $ping.Length)
        $buffer = New-Object byte[] 64
        $count = $stream.Read($buffer, 0, $buffer.Length)
        if ($count -lt 5) { return $false }
        return ([Text.Encoding]::ASCII.GetString($buffer, 0, $count)).StartsWith('+PONG')
    } catch { return $false }
    finally { $client.Dispose() }
}

function Get-MySqlEndpoint {
    $raw = [Environment]::GetEnvironmentVariable('MYSQL_URL', 'Process')
    if ($raw -and $raw -match '^jdbc:mysql://(?<host>[^/:]+)(?::(?<port>\d+))?/') {
        return @{ Host = $Matches.host; Port = if ($Matches.port) { [int]$Matches.port } else { 3306 } }
    }
    return $null
}

function Test-DependencyReadiness {
    $mysql = Get-MySqlEndpoint
    if (-not $mysql -or -not (Test-TcpPort $mysql.Host $mysql.Port)) {
        Write-Flow 'mysql tcp=UNAVAILABLE'
        return $false
    }
    Write-Flow 'mysql tcp=PASS'
    $redisHost = [Environment]::GetEnvironmentVariable('REDIS_HOST', 'Process')
    $redisPortRaw = [Environment]::GetEnvironmentVariable('REDIS_PORT', 'Process')
    $redisPort = 0
    [void][int]::TryParse($redisPortRaw, [ref]$redisPort)
    if (-not (Test-RedisPing $redisHost $redisPort)) {
        Write-Flow 'redis ping=UNAVAILABLE'
        return $false
    }
    Write-Flow 'redis ping=PASS'
    return $true
}

function Wait-Health {
    param([string]$Name, [string]$Url, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Health $Url) { Write-Flow "$Name health=PASS"; return $true }
        Start-Sleep -Seconds 1
    }
    Write-Flow "$Name health=UNAVAILABLE"
    return $false
}

function Test-Python311 {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        $version = (& $Path -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null | Select-Object -First 1).Trim()
        return $version -eq '3.11'
    } catch { return $false }
}

function Resolve-Python {
    if ($env:MVP_PYTHON_EXECUTABLE -and (Test-Python311 $env:MVP_PYTHON_EXECUTABLE)) {
        return (Resolve-Path -LiteralPath $env:MVP_PYTHON_EXECUTABLE).Path
    }
    $launcher = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($launcher) {
        try {
            $candidate = (& $launcher.Source -3.11 -c "import sys; print(sys.executable)" 2>$null | Select-Object -First 1).Trim()
            if (Test-Python311 $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        } catch { }
    }
    $known = @(
        (Join-Path $env:LocalAppData 'Programs\Python\Python311\python.exe'),
        'C:\Python311\python.exe'
    )
    foreach ($candidate in $known) {
        if (Test-Python311 $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return $null
}

try {
    $hasDotEnv = Import-DotEnv
    $javaHealthy = Test-Health ("$javaBase/actuator/health")
    $pythonHealthy = Test-Health ("$pythonBase/health")

    if (-not $javaHealthy -or -not $pythonHealthy) {
        if ($NoStart) {
            if ($strict) { throw 'required services are not healthy and -NoStart was supplied' }
            Write-Flow 'SKIP services=UNAVAILABLE (NoStart)'
            $exitCode = 0
            return
        }

        $required = @('MYSQL_URL', 'MYSQL_USERNAME', 'MYSQL_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'JWT_SIGNING_KEY_BASE64', 'APP_ENCRYPTION_KEY_BASE64')
        $missing = @($required | Where-Object { -not (Test-ConfiguredValue $_) })
        $pythonExe = Resolve-Python
        if (-not $hasDotEnv -and $missing.Count -gt 0) {
            if ($strict) { throw 'required .env credentials are not configured' }
            Write-Flow 'SKIP configuration=MISSING_ENV'
            $exitCode = 0
            return
        }
        if ($missing.Count -gt 0) {
            if ($strict) { throw 'required service credentials are placeholders or missing' }
            Write-Flow 'SKIP configuration=MISSING_CREDENTIALS'
            $exitCode = 0
            return
        }
        if (-not $pythonExe) {
            if ($strict) { throw 'Python 3.11 executable was not found' }
            Write-Flow 'SKIP python=EXECUTABLE_MISSING'
            $exitCode = 0
            return
        }

        if ($env:MVP_START_REDIS -ne '0') {
            $docker = Get-Command docker.exe -ErrorAction SilentlyContinue
            if ($docker) {
                try {
                    & $docker.Source compose -f (Join-Path $repoRoot 'docker-compose.redis.yml') up -d *> $null
                    if ($LASTEXITCODE -eq 0) { Write-Flow 'redis compose=REQUESTED' }
                    else { Write-Flow 'redis compose=UNAVAILABLE' }
                } catch {
                    Write-Flow 'redis compose=UNAVAILABLE'
                }
            } else {
                Write-Flow 'redis compose=SKIP'
            }
        }

        if (-not $pythonHealthy) {
            $pythonPort = ([Uri]$pythonBase).Port
            if ($pythonPort -lt 1) { $pythonPort = 8000 }
            $pythonArgs = @('-m', 'uvicorn', 'app.main:app', '--app-dir', $pythonRoot, '--host', '127.0.0.1', '--port', $pythonPort)
            $pythonProcess = Start-Process -FilePath $pythonExe -ArgumentList $pythonArgs -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru
            $startedProcesses += $pythonProcess
        }
        if (-not $javaHealthy) {
            # Local loopback providers are allowed only for this explicit test
            # process; production profiles retain the secure default.
            [Environment]::SetEnvironmentVariable('APP_ALLOW_LOCAL_MODEL_ENDPOINTS', 'true', 'Process')
            [Environment]::SetEnvironmentVariable('PYTHON_ANALYSIS_BASE_URL', $pythonBase, 'Process')
            [Environment]::SetEnvironmentVariable('MATCHING_CALLBACK_URL', "$javaBase/internal/v1/analysis-results", 'Process')
            $mvnw = Join-Path $javaRoot 'mvnw.cmd'
            if (-not (Test-Path -LiteralPath $mvnw -PathType Leaf)) { throw 'Maven Wrapper is missing' }
            $javaProcess = Start-Process -FilePath $mvnw -ArgumentList @('spring-boot:run', '-Dspring-boot.run.profiles=local') -WorkingDirectory $javaRoot -WindowStyle Hidden -PassThru
            $startedProcesses += $javaProcess
        }
        $javaHealthy = Wait-Health 'java' "$javaBase/actuator/health" $ReadyTimeoutSeconds
        $pythonHealthy = Wait-Health 'python' "$pythonBase/health" $ReadyTimeoutSeconds
        if (-not $javaHealthy -or -not $pythonHealthy) {
            if ($strict) { throw 'service health check failed' }
            Write-Flow 'SKIP services=UNAVAILABLE_AFTER_START'
            $exitCode = 0
            return
        }
    } else {
        Write-Flow 'using existing services=PASS'
    }

    if (-not $pythonExe) { $pythonExe = Resolve-Python }
    if (-not $pythonExe) {
        if ($strict) { throw 'Python 3.11 executable was not found' }
        Write-Flow 'SKIP python=EXECUTABLE_MISSING'
        $exitCode = 0
        return
    }
    if (-not (Test-DependencyReadiness)) {
        if ($strict) { throw 'MySQL or Redis readiness check failed' }
        Write-Flow 'SKIP dependencies=UNAVAILABLE'
        $exitCode = 0
        return
    }

    # The Python assertion runner owns the ephemeral loopback provider and
    # keeps all credentials in memory. Its output is already allow-listed.
    $previousLive = $env:MVP_LIVE
    $previousApi = $env:MVP_API_BASE_URL
    $env:MVP_LIVE = '1'
    $env:MVP_API_BASE_URL = $javaBase
    try {
        $liveAttempted = $true
        $output = & $pythonExe $assertionScript '--live' '--api-base' $javaBase '--python-base' $pythonBase 2>&1
        $flowExit = $LASTEXITCODE
        foreach ($line in $output) {
            if ($line -is [string] -and $line -match '^\[flow\] (?:[A-Za-z0-9 _-]+)(?: id=[0-9a-fA-F-]{36})?(?: state=[A-Z_]+)?(?: status=[0-9]+)?(?: code=[A-Z_]+)?$') {
                Write-Output $line
            }
        }
        if ($flowExit -ne 0) {
            Write-Flow "live flow=FAIL status=$flowExit"
            $exitCode = 1
        }
    } finally {
        if ($null -eq $previousLive) { Remove-Item Env:MVP_LIVE -ErrorAction SilentlyContinue } else { $env:MVP_LIVE = $previousLive }
        if ($null -eq $previousApi) { Remove-Item Env:MVP_API_BASE_URL -ErrorAction SilentlyContinue } else { $env:MVP_API_BASE_URL = $previousApi }
    }
} catch {
    if ($liveAttempted) {
        Write-Flow 'FAIL live=ASSERTION'
        $exitCode = 1
    } elseif ($strict) {
        Write-Flow 'FAIL preflight=CONFIGURATION_OR_SERVICE'
        $exitCode = 2
    } else {
        Write-Flow 'SKIP preflight=CONFIGURATION_OR_SERVICE'
        $exitCode = 0
    }
} finally {
    foreach ($process in $startedProcesses) {
        try {
            if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        } catch { }
    }
}

exit $exitCode
