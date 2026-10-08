param()
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$bootstrapRoot = Join-Path $repoRoot 'work/bootstrap'
[IO.Directory]::CreateDirectory($bootstrapRoot) | Out-Null
$zipPath = Join-Path $bootstrapRoot 'apache-maven-3.10.0-bin.zip'
$distributionUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.zip'
$expectedSha512 = '22d31676d5b92ed53308c19ecded3245d0efd0cddfec51ec7bab62f4f13ad70ddddceff8e62940e0e8e2421f3cde8106737f4becec0aa034ffeff2a754e88337'
if (-not (Test-Path -LiteralPath $zipPath)) {
    Invoke-WebRequest -Uri $distributionUrl -OutFile $zipPath -TimeoutSec 120
}
$actualSha512 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA512).Hash.ToLowerInvariant()
if ($actualSha512 -ne $expectedSha512) { throw 'MAVEN_CHECKSUM_MISMATCH: refusing to use downloaded archive.' }
$sha256 = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$propertiesPath = Join-Path $repoRoot '.mvn/wrapper/maven-wrapper.properties'
$properties = @"
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=$distributionUrl
distributionSha256Sum=$sha256
"@
[IO.File]::WriteAllText($propertiesPath, $properties + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
# Preseed exactly the Windows upstream wrapper cache after verifying the archive.
# All extraction/move targets are checked inside this repository's work directory.
$workRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work')) + [IO.Path]::DirectorySeparatorChar
$mavenUserHome = Join-Path $repoRoot 'work/maven-user-home'
$urlHash = ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($distributionUrl)) |
    ForEach-Object { $_.ToString('x2') }) -join ''
$cacheParent = Join-Path $mavenUserHome 'wrapper/dists/apache-maven-3.10.0'
$cachePath = [IO.Path]::GetFullPath((Join-Path $cacheParent $urlHash))
$extractedPath = [IO.Path]::GetFullPath((Join-Path $bootstrapRoot 'apache-maven-3.10.0'))
foreach ($targetPath in @($bootstrapRoot,$cachePath,$extractedPath)) {
    if (-not [IO.Path]::GetFullPath($targetPath).StartsWith($workRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'UNSAFE_BOOTSTRAP_PATH: target must stay inside repository work/.'
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $cachePath 'bin/mvn.cmd'))) {
    [IO.Directory]::CreateDirectory($cacheParent) | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $bootstrapRoot -Force
    Move-Item -LiteralPath $extractedPath -Destination $cachePath
}
[ordered]@{maven_version='3.10.0';archive=$zipPath;sha512_verified=$true;sha512=$actualSha512;sha256=$sha256} |
    ConvertTo-Json | Tee-Object -FilePath (Join-Path $bootstrapRoot 'evidence.json')
Write-Output 'Bootstrap checksum ready. Set MAVEN_USER_HOME to work/maven-user-home and invoke mvnw.cmd.'
