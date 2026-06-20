#!/usr/bin/env pwsh
# Convenience wrapper: run the local knowledge-base retrieval tool.
#   kb\rag.ps1 "your question" [-k N] [-f] [-d dir]...
#   kb\rag.ps1 -f "your question"      # print whole chunks
#   kb\rag.ps1 list
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = 'C:\Program Files\Java\graalvm-jdk-21.0.9+7.1'
}
& (Join-Path $env:JAVA_HOME 'bin\java.exe') (Join-Path $root 'kb\Rag.java') @args
