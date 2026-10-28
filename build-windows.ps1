# BuildYourTV on Windows, in one command: fetches the build tools the first time (a JDK 17 and the Android SDK's
# command-line tools, platform 34 and build-tools 34.0.0, into a folder next to this repository), builds the APK and,
# with -TvHost, installs it on the TV and grants what Continue watching, the screensaver picker, ad blocking and the
# screensaver delay need. Needs Git for Windows (its bash runs launcher-ui/build.sh) and Python 3.
#
#   .\build-windows.ps1                       # build only: launcher-ui\CleanYourTV-UI.apk
#   .\build-windows.ps1 -TvHost 192.168.1.50  # build, install on the TV (USB debugging on), grant the permissions
#
# The APK is signed with launcher-ui\cleanyourtv-ui.keystore, created by the first build. Keep that file: an update
# signed with another key is refused by the TV, and the app would have to be uninstalled (losing its settings).
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Toolchain = (Join-Path (Split-Path -Parent $PSScriptRoot) 'toolchain')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is many times slower with the progress bar on
$repo = $PSScriptRoot

# Git's own bash (not WSL's bash.exe in System32): next to git.exe, or in the usual install folders.
$gitExe = (Get-Command git -ErrorAction SilentlyContinue).Source
$fromGit = if ($gitExe) { Join-Path (Split-Path -Parent (Split-Path -Parent $gitExe)) 'bin\bash.exe' }
$bash = @($fromGit, "$env:ProgramFiles\Git\bin\bash.exe", "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
        "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $bash) { throw 'Git for Windows is needed (its bash runs the build): https://git-scm.com/download/win' }
# Runs a bash command and returns its exit code. Its stderr (javac's notes, sdkmanager's progress) is printed, not
# treated as a failure: Windows PowerShell turns a native command's stderr into errors under -ErrorAction Stop.
function Invoke-Bash([string]$command) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $bash -lc $command 2>&1 | ForEach-Object { Write-Host "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    return $code
}
function Unix([string]$path) { & $bash -lc "cygpath -u '$($path -replace "'", "'\''")'" }

# --- The build tools, once ------------------------------------------------------------------------------------
New-Item -ItemType Directory -Force $Toolchain | Out-Null
$jdk = Get-ChildItem (Join-Path $Toolchain 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) {
    Write-Host 'Downloading a JDK 17 (Eclipse Temurin)...'
    $zip = Join-Path $Toolchain 'jdk17.zip'
    Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $zip
    Expand-Archive $zip (Join-Path $Toolchain 'jdk') -Force
    Remove-Item $zip
    $jdk = Get-ChildItem (Join-Path $Toolchain 'jdk') -Directory | Select-Object -First 1
}
$env:JAVA_HOME = $jdk.FullName
$sdk = Join-Path $Toolchain 'sdk'
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Write-Host 'Downloading the Android SDK command-line tools...'
    $zip = Join-Path $Toolchain 'cmdline-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
    $tmp = Join-Path $Toolchain 'cmdline-tools-tmp'
    Expand-Archive $zip $tmp -Force
    New-Item -ItemType Directory -Force (Join-Path $sdk 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest')
    Remove-Item $zip, $tmp -Recurse
}
$env:ANDROID_HOME = $sdk
if (-not (Test-Path (Join-Path $sdk 'platforms\android-34')) -or -not (Test-Path (Join-Path $sdk 'build-tools\34.0.0'))) {
    Write-Host 'Installing Android platform 34 and build-tools 34.0.0 (this accepts the Android SDK licence)...'
    $sm = Unix $sdkmanager; $root = Unix $sdk
    $code = Invoke-Bash "export JAVA_HOME='$(Unix $env:JAVA_HOME)'; yes | '$sm' --sdk_root='$root' --licenses >/dev/null; '$sm' --sdk_root='$root' 'platforms;android-34' 'build-tools;34.0.0' >/dev/null"
    if ($code -ne 0) { throw 'sdkmanager failed' }
}

# --- The ADB client the install uses (tools\tv.py), once --------------------------------------------------------
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) {
    Write-Host 'Setting up the ADB client (tools\venv)...'
    python -m venv (Join-Path $repo 'tools\venv')
    & $py -m pip install -q -r (Join-Path $repo 'tools\requirements.txt')
}

# --- Build -------------------------------------------------------------------------------------------------------
Write-Host 'Building...'
$r = Unix $repo
$javaHome = Unix $env:JAVA_HOME
$androidHome = Unix $sdk
$code = Invoke-Bash "cd '$r' && export JAVA_HOME='$javaHome' ANDROID_HOME='$androidHome' && export PATH='$javaHome/bin':`$PATH && RELEASE=1 bash launcher-ui/build.sh"
if ($code -ne 0) { throw 'the build failed' }
$apk = Join-Path $repo 'launcher-ui\CleanYourTV-UI.apk'
Write-Host "Built $apk"
if (-not $TvHost) { return }

# --- Install on the TV, as Home, with the remote-button service ------------------------------------------------------
Write-Host "Installing on $TvHost..."
$apkPath = (Resolve-Path $apk).Path -replace '\\', '/'
$code = Invoke-Bash "cd '$r' && TV_HOST='$TvHost' SCREENSAVER=0 KEY_SERVICE=1 APK='$apkPath' bash launcher-ui/build-and-install.sh"
if ($code -ne 0) { throw 'the install failed' }
$env:TV_HOST = $TvHost
$tv = Join-Path $repo 'tools\tv.py'
# Continue watching and YouTube picks; the screensaver picker and ad blocking; the screensaver delay.
& $py $tv pm grant io.cleanyourtv.ui android.permission.READ_TV_LISTINGS
& $py $tv pm grant io.cleanyourtv.ui android.permission.WRITE_SECURE_SETTINGS
& $py $tv appops set io.cleanyourtv.ui WRITE_SETTINGS allow
Write-Host 'Done. Press Home on the remote.'
