$ErrorActionPreference = "Stop"
$dir = Split-Path $PSScriptRoot -Parent
$target = Join-Path $dir 'gradle\wrapper\gradle-wrapper.jar'
$expected = '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
$url = 'https://services.gradle.org/distributions/gradle-8.9-wrapper.jar'
if (-not (Test-Path $target)) {
    $tmp = "$target.tmp"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp
        if ((Get-Item $tmp).Length -gt 2MB) { throw 'Unexpectedly large wrapper' }
        $hash = (Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLowerInvariant()
        if ($hash -ne $expected) { throw "Checksum mismatch: $hash" }
        Move-Item -Force $tmp $target
    } finally {
        if (Test-Path $tmp) { Remove-Item -Force $tmp }
    }
}
$hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
if ($hash -ne $expected) { throw "Untrusted Gradle wrapper jar (SHA-256 $hash)" }
Write-Host 'Verified official Gradle wrapper JAR.'
