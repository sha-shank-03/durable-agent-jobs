param()
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jarPath = Join-Path $repoRoot 'target/durable-agent-jobs.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the executable JAR first.' }
$runRoot = Join-Path $repoRoot ('work/acceptance/cli-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$dbPath = Join-Path $runRoot 'jobs.db'
$specPath = Join-Path $runRoot 'ticket.json'
Copy-Item -LiteralPath (Join-Path $repoRoot 'examples/ticket.json') -Destination $specPath
$unicodeSpec = Get-Content -Raw -LiteralPath $specPath | ConvertFrom-Json
$unicodeSpec.request.title = ' Fictional café export request '
[IO.File]::WriteAllText($specPath, ($unicodeSpec | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$records = [Collections.Generic.List[object]]::new()

function Invoke-JobCli {
    param([string[]]$CliArguments, [int]$ExpectedExit = 0)
    $sequence = $records.Count + 1
    $stdoutPath = Join-Path $runRoot ("command-$sequence.json")
    $stderrPath = Join-Path $runRoot ("command-$sequence.stderr.txt")
    & java '-Xmx256m' '-jar' $jarPath @CliArguments > $stdoutPath 2> $stderrPath
    $observedExit = $LASTEXITCODE
    $records.Add([ordered]@{arguments=$CliArguments;exit=$observedExit;expected_exit=$ExpectedExit;stdout=$stdoutPath;stderr=$stderrPath})
    if ($observedExit -ne $ExpectedExit) { throw "Unexpected CLI exit at command $sequence; inspect stderr file." }
    if ($ExpectedExit -eq 0) { return Get-Content -Raw -LiteralPath $stdoutPath | ConvertFrom-Json }
    return Get-Content -Raw -LiteralPath $stderrPath | ConvertFrom-Json
}

$first = Invoke-JobCli -CliArguments @('--db',$dbPath,'submit','--key','verification-001','--spec',$specPath)
$duplicate = Invoke-JobCli -CliArguments @('--db',$dbPath,'submit','--key','verification-001','--spec',$specPath)
if (-not $duplicate.duplicate -or $duplicate.job_id -ne $first.job_id) { throw 'Same-ID dedup assertion failed.' }
$worker = Invoke-JobCli -CliArguments @('--db',$dbPath,'worker','--drain')
if ($worker.processed -ne 1 -or $worker.succeeded -ne 1 -or $worker.effect_count -ne 1) { throw 'Initial worker assertion failed.' }
$inspection = Invoke-JobCli -CliArguments @('--db',$dbPath,'inspect','--key','verification-001','--json')
if ($inspection.state -ne 'SUCCEEDED' -or $inspection.checkpoint_sequence -ne 2) { throw 'Inspection assertion failed.' }
if ($inspection.result.title -ne 'Fictional café export request') { throw 'UTF-8 CLI title did not round-trip.' }
$changedSpec = Get-Content -Raw -LiteralPath $specPath | ConvertFrom-Json
$changedSpec.request.title = 'Changed fictional request'
$changedSpec | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'changed.json') -Encoding utf8
$conflict = Invoke-JobCli -CliArguments @('--db',$dbPath,'submit','--key','verification-001','--spec',(Join-Path $runRoot 'changed.json')) -ExpectedExit 3
if ($conflict.error_code -ne 'REQUEST_CONFLICT') { throw 'Changed-spec conflict assertion failed.' }
$repeat = Invoke-JobCli -CliArguments @('--db',$dbPath,'worker','--drain')
if ($repeat.processed -ne 0 -or $repeat.invocation_count -ne 1 -or $repeat.effect_count -ne 1) { throw 'Repeated effect assertion failed.' }
$reopened = Invoke-JobCli -CliArguments @('--db',$dbPath,'inspect','--key','verification-001','--json')
if (($inspection | ConvertTo-Json -Depth 20 -Compress) -ne ($reopened | ConvertTo-Json -Depth 20 -Compress)) {
    throw 'Completed checkpoint changed across independent CLI processes.'
}
$records | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runRoot 'commands.json') -Encoding utf8
$summary = [ordered]@{
    execution_mode='deterministic_mock';run_dir=$runRoot;state=$reopened.state;
    duplicate_same_job=$true;changed_spec_rejected=$true;completed_checkpoint_persisted=$true;
    utf8_title_preserved=$true;
    invocation_count=$repeat.invocation_count;effect_count=$repeat.effect_count;
    commands_executed=$records.Count;assertions_failed=0
}
$summary | ConvertTo-Json | Tee-Object -FilePath (Join-Path $runRoot 'summary.json')
