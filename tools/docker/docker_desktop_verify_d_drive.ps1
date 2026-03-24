param()

$ErrorActionPreference = 'Stop'

$DockerRoot = 'D:\ProTools\docker'
$InstallRoot = 'C:\Program Files\Docker\Docker'
$LogDir = Join-Path $DockerRoot 'logs'
$VerifyLog = Join-Path $LogDir 'verify.log'
$RepoRoot = 'D:\Git\agent\noval'
$DockerDesktopExe = Join-Path $InstallRoot 'Docker Desktop.exe'
$DockerCli = Join-Path $InstallRoot 'resources\bin\docker.exe'

function Write-Log {
    param([string]$Message)
    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $line = "[$timestamp] $Message"
    Write-Host $line
    Add-Content -Path $VerifyLog -Value $line -Encoding UTF8
}

function Wait-ForDocker {
    param([int]$TimeoutSeconds = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            & $DockerCli version | Out-Null
            return $true
        } catch {
            Start-Sleep -Seconds 5
        }
    }
    return $false
}

if (-not (Test-Path $DockerDesktopExe)) {
    throw "Docker Desktop executable not found at $DockerDesktopExe"
}

if (-not (Test-Path $DockerCli)) {
    throw "Docker CLI not found at $DockerCli"
}

Write-Log 'Starting Docker Desktop verification.'

$dockerDesktopRunning = Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue
if (-not $dockerDesktopRunning) {
    Write-Log 'Launching Docker Desktop.'
    Start-Process -FilePath $DockerDesktopExe | Out-Null
}

if (-not (Wait-ForDocker -TimeoutSeconds 300)) {
    throw 'Docker engine did not become ready within timeout.'
}

Write-Log 'Docker engine is ready.'
Write-Log 'Running hello-world container.'
& $DockerCli run --rm hello-world | Tee-Object -FilePath $VerifyLog -Append | Out-Null

Write-Log 'Checking docker compose version.'
& $DockerCli compose version | Tee-Object -FilePath $VerifyLog -Append | Out-Null

Write-Log 'Rendering compose configuration.'
Push-Location $RepoRoot
try {
    & $DockerCli compose -f docker-compose.yml config | Tee-Object -FilePath $VerifyLog -Append | Out-Null

    Write-Log 'Starting local integration stack.'
    & $DockerCli compose -f docker-compose.yml up -d mysql redis crawler backend | Tee-Object -FilePath $VerifyLog -Append | Out-Null
} finally {
    Pop-Location
}

Start-Sleep -Seconds 15

Write-Log 'Docker verification completed.'
