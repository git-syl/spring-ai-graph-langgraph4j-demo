param(
    [ValidateSet('all', 'graph-api', 'persistence', 'durable-start', 'durable-resume', 'durable-retry', 'streaming', 'subgraphs')]
    [string]$Example = 'all',
    [switch]$Offline,
    [string]$Thread = '',
    [string]$Topic = '冰淇淋',
    [ValidateSet('', 'true', 'false')]
    [string]$Approved = '',
    [string]$CheckpointDir = '.graph-checkpoints',
    [switch]$FailPublish,
    [string]$Maven = 'mvn.cmd',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipBuild) {
        & $Maven -q -DskipTests compile dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/graph-examples-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
    }
    $dependencyClasspath = (Get-Content -LiteralPath 'target/graph-examples-classpath.txt' -Raw).Trim()
    $runtimeClasspath = (Join-Path $projectRoot 'target/classes') + [IO.Path]::PathSeparator + $dependencyClasspath
    $exampleArguments = @($Example, "--topic=$Topic", "--checkpoint-dir=$CheckpointDir")
    if ($Thread) { $exampleArguments += "--thread=$Thread" }
    if ($Offline) { $exampleArguments += '--offline' }
    if ($Approved) { $exampleArguments += "--approved=$Approved" }
    if ($FailPublish) { $exampleArguments += '--fail-publish' }
    & $javaCommand '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $runtimeClasspath com.example.refunddemo.examples.GraphExamples @exampleArguments
    if ($LASTEXITCODE -ne 0) { throw "Graph example failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}
