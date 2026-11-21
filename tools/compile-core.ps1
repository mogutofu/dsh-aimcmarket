# 编译"不依赖 Minecraft 的那部分"代码（util/config/data/ai/engine），给本机快速语法/类型检查用。
# 完整构建仍然走 build-fixed.ps1（Gradle + ForgeGradle）。
param(
    [string]$Out = 'D:\server\temp\aimc-core-out'
)

$ErrorActionPreference = 'Continue'
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Jdk   = 'D:\server\java'
$Gson  = 'D:\server\.gradle-home2\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar'
$Root  = Join-Path $PSScriptRoot '..\src\main\java'
$Root  = [IO.Path]::GetFullPath($Root)

if (-not (Test-Path $Gson)) { throw "gson jar 不在：$Gson" }
if (-not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) { throw "JDK 不在：$Jdk" }

Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Out | Out-Null

# 排除依赖 Forge/Minecraft 的命令层、UI 层和模组主类
$files = Get-ChildItem (Join-Path $Root 'com\mogutofu\aimcmarket') -Recurse -Filter *.java |
    Where-Object {
        $_.FullName -notmatch '\\command\\' -and
        $_.FullName -notmatch '\\ui\\' -and
        $_.Name -ne 'AimcmarketMod.java' -and $_.Name -ne 'MarketConfig.java'
    } | ForEach-Object { $_.FullName }

Write-Host "[compile-core] $($files.Count) 个文件 → $Out"
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -Xlint:none -cp $Gson -d $Out @files
$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host '[compile-core] OK'
} else {
    Write-Host "[compile-core] 失败，exit=$code"
}
exit $code
