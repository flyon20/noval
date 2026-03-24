param(
    [switch]$SkipComposeVerify
)

$ErrorActionPreference = 'Stop'

$DockerRoot = 'D:\ProTools\docker'
$InstallRoot = 'C:\Program Files\Docker\Docker'
$InstallerDir = Join-Path $DockerRoot 'installer'
$ConfigDir = Join-Path $DockerRoot 'config'
$DataRoot = Join-Path $DockerRoot 'data'
$WslDataRoot = Join-Path $DataRoot 'wsl'
$HyperVDataRoot = Join-Path $DataRoot 'hyperv'
$WindowsDataRoot = Join-Path $DataRoot 'windows'
$LogDir = Join-Path $DockerRoot 'logs'
$InstallerPath = Join-Path $InstallerDir 'DockerDesktopInstaller.exe'
$VerifyScript = Join-Path $PSScriptRoot 'docker_desktop_verify_d_drive.ps1'
$InstallLog = Join-Path $LogDir 'install.log'
$DockerDownloadUrl = 'https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe'

function Write-Log {
    param([string]$Message)
    $timestamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $line = "[$timestamp] $Message"
    Write-Host $line
    Add-Content -Path $InstallLog -Value $line -Encoding UTF8
}

function Test-IsAdmin {
    return ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
        IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

if (-not (Test-IsAdmin)) {
    throw 'This installer script must run as Administrator.'
}

foreach ($dir in @($DockerRoot, $InstallerDir, $ConfigDir, $DataRoot, $WslDataRoot, $HyperVDataRoot, $WindowsDataRoot, $LogDir)) {
    Ensure-Directory -Path $dir
}

Write-Log 'Starting Docker Desktop installation bootstrap.'
Write-Log "Docker Desktop program files will use default install path: $InstallRoot"
Write-Log "Docker Desktop heavy data roots will use D drive under: $DataRoot"

$rebootRequired = $false
foreach ($feature in @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')) {
    $featureInfo = (& dism.exe /online /get-featureinfo /featurename:$feature | Out-String)
    if ($featureInfo -match 'State : Enabled') {
        Write-Log "$feature is already enabled."
        continue
    }

    Write-Log "Enabling optional feature: $feature"
    $enableOutput = (& dism.exe /online /enable-feature /featurename:$feature /all /norestart | Out-String)
    Add-Content -Path $InstallLog -Value $enableOutput -Encoding UTF8
    if ($LASTEXITCODE -in @(0, 3010)) {
        $rebootRequired = $true
    } else {
        throw "Failed to enable $feature, exit code: $LASTEXITCODE"
    }
}

try {
    Write-Log 'Running WSL install/update commands.'
    & wsl.exe --install --no-distribution --web-download | Out-Null
    if ($LASTEXITCODE -eq 3010) {
        $rebootRequired = $true
    }
} catch {
    Write-Log "WSL install command returned: $($_.Exception.Message)"
}

try {
    & wsl.exe --update --web-download | Out-Null
    Write-Log 'WSL update completed or was already current.'
} catch {
    Write-Log "WSL update returned: $($_.Exception.Message)"
}

try {
    & wsl.exe --set-default-version 2 | Out-Null
    Write-Log 'WSL default version set to 2.'
} catch {
    Write-Log "Unable to set WSL default version to 2 yet: $($_.Exception.Message)"
}

if (-not (Test-Path $InstallerPath)) {
    Write-Log "Downloading Docker Desktop installer from $DockerDownloadUrl"
    & curl.exe -L $DockerDownloadUrl --output $InstallerPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to download Docker Desktop installer, exit code: $LASTEXITCODE"
    }
    if (-not (Test-Path $InstallerPath) -or (Get-Item $InstallerPath).Length -le 0) {
        throw 'Docker Desktop installer download produced an empty file.'
    }
}

$installArgs = @(
    'install',
    '--accept-license',
    '--backend=wsl-2',
    '--always-run-service',
    '--no-windows-containers',
    "--wsl-default-data-root=$WslDataRoot",
    "--hyper-v-default-data-root=$HyperVDataRoot",
    "--windows-containers-default-data-root=$WindowsDataRoot"
)

Write-Log "Installing Docker Desktop with default program path and D-drive data roots"
$installProc = Start-Process -FilePath $InstallerPath -ArgumentList $installArgs -Wait -PassThru
Write-Log "Docker Desktop installer exit code: $($installProc.ExitCode)"
if ($installProc.ExitCode -notin @(0, 3010)) {
    throw "Docker Desktop installer failed with exit code $($installProc.ExitCode)"
}
if ($installProc.ExitCode -eq 3010) {
    $rebootRequired = $true
}

try {
    & net localgroup docker-users $env:USERNAME /add | Out-Null
    Write-Log "Ensured user $env:USERNAME is in docker-users."
} catch {
    Write-Log "Unable to add current user to docker-users: $($_.Exception.Message)"
}

$verifyTaskName = 'CodexDockerDesktopVerify'
$verifyCommand = "powershell.exe -ExecutionPolicy Bypass -File `"$VerifyScript`""
try {
    & schtasks.exe /Delete /TN $verifyTaskName /F | Out-Null
} catch {
}
& schtasks.exe /Create /SC ONLOGON /RL HIGHEST /TN $verifyTaskName /TR $verifyCommand /F | Out-Null
Write-Log "Created logon verification task: $verifyTaskName"

if ($rebootRequired) {
    Write-Log 'Reboot is required before Docker Desktop can be verified.'
    Write-Log 'After reboot and next logon, the scheduled verification task will run automatically.'
    exit 3010
}

if (-not $SkipComposeVerify) {
    Write-Log 'No reboot required. Running verification script immediately.'
    & powershell.exe -ExecutionPolicy Bypass -File $VerifyScript
}
