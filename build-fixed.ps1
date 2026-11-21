# 本机 Forge 1.20.1 构建脚本（只在 D:\server 这台机器上用，CI 不需要）。
#
# 为什么需要它：DSH 的 Windows 沙箱里，构建 fork 出去的 JVM 的 Files.isWritable()
# 会恒为 false，导致 ForgeGradle 的 access transformer 步骤（access-transformers
# 8.2.14 走 ZipFileSystem）抛 ReadOnlyFileSystemException。办法是把 JDK 的
# jdk.zipfs 打一个「readOnly 恒为 false」的小补丁，用 JAVA_TOOL_OPTIONS
# --patch-module 注入到构建启动的每个 JVM 里。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\build-fixed.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File .\build-fixed.ps1 -Task "build --rerun-tasks"
param(
    [string]$Task = 'build',
    [string]$LogFile = ''
)

$ErrorActionPreference = 'Continue'

$ProjectDir = $PSScriptRoot
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path }

$Jdk        = 'D:\server\java'
$GradleHome = 'D:\server\.gradle-home2'
$TempDir    = 'D:\server\temp'
$ToolDir    = 'D:\server\.mcai-build-tools\zipfs-patch'
$PatchOut   = Join-Path $ToolDir 'classes'
$PatchSrc   = Join-Path $ToolDir 'src\jdk.zipfs'
$ProjectCache = Join-Path $TempDir 'aimcmarket-cache'
$BuildOut     = Join-Path $TempDir 'aimcmarket-build'

$env:JAVA_HOME       = $Jdk
$env:PATH            = (Join-Path $Jdk 'bin') + ';' + $env:PATH
$env:GRADLE_USER_HOME = $GradleHome
$env:TEMP            = $TempDir
$env:TMP             = $TempDir

function Prepare-ZipfsPatch {
    $marker = Join-Path $PatchOut 'jdk\nio\zipfs\ZipFileSystem.class'
    if (Test-Path $marker) { return }
    Write-Host '[fix] compiling patched jdk.zipfs (first run only)...'
    $srcZip = Join-Path $Jdk 'lib\src.zip'
    if (-not (Test-Path $srcZip)) { throw "JDK sources not found: $srcZip" }
    Remove-Item $ToolDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path (Join-Path $ToolDir 'src') -Force | Out-Null
    New-Item -ItemType Directory -Path $PatchOut -Force | Out-Null
    Push-Location (Join-Path $ToolDir 'src')
    try {
        $jarExe = Join-Path $Jdk 'bin\jar.exe'
        $entries = @(& $jarExe tf $srcZip | Where-Object { $_ -like 'jdk.zipfs/*' })
        & $jarExe xf $srcZip @entries
    } finally { Pop-Location }
    $zfs = Join-Path $PatchSrc 'jdk\nio\zipfs\ZipFileSystem.java'
    if (-not (Test-Path $zfs)) { throw "jdk.zipfs sources missing: $zfs" }
    $text = [IO.File]::ReadAllText($zfs)
    $needle = 'this.readOnly = !writeable;'
    if (-not $text.Contains($needle)) { throw "patch point not found in $zfs" }
    $text = $text.Replace($needle, 'this.readOnly = false; /* patched: sandbox isWritable() is unreliable */')
    [IO.File]::WriteAllText($zfs, $text, (New-Object System.Text.UTF8Encoding($false)))
    $sources = @(Get-ChildItem $PatchSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    & (Join-Path $Jdk 'bin\javac.exe') '--patch-module' "jdk.zipfs=$PatchSrc" '-d' $PatchOut @sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
    Write-Host '[fix] patched jdk.zipfs ready'
}

Prepare-ZipfsPatch
$env:JAVA_TOOL_OPTIONS = '--patch-module=jdk.zipfs=' + $PatchOut

$env:AIMC_BUILD_DIR = $BuildOut

if (-not $LogFile) { $LogFile = Join-Path $ProjectDir 'build.log' }

Push-Location $ProjectDir
try {
    $taskArgs = @($Task -split '\s+' | Where-Object { $_ })
    & 'D:\gradle-8.1.1\gradle-8.1.1\bin\gradle.bat' @taskArgs --no-daemon --console=plain "--project-cache-dir=$ProjectCache" 2>&1 | Tee-Object -FilePath $LogFile
    $code = $LASTEXITCODE
} finally { Pop-Location }

if ($code -ne 0) {
    Write-Host "[error] gradle failed with exit code $code - see $LogFile"
    exit $code
}
$jar = Join-Path $BuildOut 'libs\aimcmarket-forge-1_20_1-v0_1_0.jar'
if (Test-Path $jar) {
    $info = Get-Item $jar
    Write-Host "[ok] $jar ($($info.Length) bytes)"
} else {
    Write-Host '[warn] gradle succeeded but the expected jar is missing (check build\log)'
}
exit 0
