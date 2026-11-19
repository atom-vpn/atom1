[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRepo,
    [Parameter(Mandatory = $true)]
    [string]$DestinationRepo,
    [switch]$CommitAndPush
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([string]$Repo, [string[]]$GitArgs)
    $output = & git -C $Repo @GitArgs
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArgs -join ' ') failed in $Repo"
    }
    return ($output | Out-String).Trim()
}

$SourceRepo = (Resolve-Path $SourceRepo).Path
$DestinationRepo = (Resolve-Path $DestinationRepo).Path

$branch = Invoke-Git $DestinationRepo @("branch", "--show-current")
if ($branch -ne "pow-standalone") {
    throw "Destination must be checked out on pow-standalone; current branch is '$branch'."
}

$status = Invoke-Git $DestinationRepo @("status", "--porcelain")
if ($status) {
    throw "Destination working tree is not clean. Commit/stash changes before importing."
}

$artifacts = @(
    @{ Source = "app/libs/psiphontunnel-2.0.39.aar"; Destination = "vendor/aars/psiphontunnel-2.0.39.aar"; Blob = "ab140ff46966e1097441b1342b74ec79e83efb24"; Size = 44395730 },
    @{ Source = "app/libs/libv2ray-native-tun.aar"; Destination = "vendor/aars/libv2ray-native-tun.aar"; Blob = "4c75dc51233408d47bc083a83a3f47cb0e1c644f"; Size = 58464519 },
    @{ Source = "app/src/main/jniLibs/arm64-v8a/libaether.so"; Destination = "vendor/jniLibs/arm64-v8a/libaether.so"; Blob = "10b89f17d9c4a234fff191cf456a5e8f54568f4f"; Size = 9518480 },
    @{ Source = "app/src/main/jniLibs/armeabi-v7a/libaether.so"; Destination = "vendor/jniLibs/armeabi-v7a/libaether.so"; Blob = "4a3b255e1d80eadb9318ce921a6f96c343ffe74f"; Size = 6217124 },
    @{ Source = "app/src/main/jniLibs/x86_64/libaether.so"; Destination = "vendor/jniLibs/x86_64/libaether.so"; Blob = "b75f7ab054826174537adde60086506a9c2d548b"; Size = 10763128 },
    @{ Source = "app/src/main/assets/server_entries.txt"; Destination = "vendor/assets/server_entries.txt"; Blob = "bb99c2504a1e5324b79cf43c9665434c4658972f"; Size = 1151228 }
)

foreach ($artifact in $artifacts) {
    $sourceFile = Join-Path $SourceRepo ($artifact.Source -replace "/", [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Missing source artifact: $sourceFile"
    }

    $sourceBlob = Invoke-Git $SourceRepo @("rev-parse", "main:$($artifact.Source)")
    if ($sourceBlob -ne $artifact.Blob) {
        throw "Source revision mismatch for $($artifact.Source): expected $($artifact.Blob), got $sourceBlob"
    }

    $actualSize = (Get-Item -LiteralPath $sourceFile).Length
    if ($actualSize -ne $artifact.Size) {
        throw "Size mismatch for $($artifact.Source): expected $($artifact.Size), got $actualSize"
    }

    $destinationFile = Join-Path $DestinationRepo ($artifact.Destination -replace "/", [IO.Path]::DirectorySeparatorChar)
    $destinationDirectory = Split-Path -Parent $destinationFile
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceFile -Destination $destinationFile -Force

    $destinationBlob = Invoke-Git $DestinationRepo @("hash-object", $destinationFile)
    if ($destinationBlob -ne $artifact.Blob) {
        throw "Copied Git blob mismatch for $($artifact.Destination): expected $($artifact.Blob), got $destinationBlob"
    }

    Write-Host "VERIFIED $($artifact.Destination) [$actualSize bytes] $destinationBlob" -ForegroundColor Green
}

if ($CommitAndPush) {
    Invoke-Git $DestinationRepo @("add", "--", "vendor/aars", "vendor/jniLibs/arm64-v8a/libaether.so", "vendor/jniLibs/armeabi-v7a/libaether.so", "vendor/jniLibs/x86_64/libaether.so", "vendor/assets/server_entries.txt") | Out-Null
    Invoke-Git $DestinationRepo @("commit", "-m", "Import verified UAC PoW runtime binaries") | Out-Null
    Invoke-Git $DestinationRepo @("push", "origin", "pow-standalone") | Out-Null
    Write-Host "Committed and pushed verified runtime binaries to origin/pow-standalone." -ForegroundColor Green
}
else {
    Write-Host "All artifacts copied and verified locally. Review the copied files, then rerun with -CommitAndPush to commit and push." -ForegroundColor Yellow
}
