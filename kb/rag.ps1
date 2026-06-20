#!/usr/bin/env pwsh
# Convenience wrapper: run the local knowledge-base retrieval tool.
#   kb\rag.ps1 "your question" [-k N] [-f] [-d dir]...
#   kb\rag.ps1 -f "your question"      # print whole chunks
#   kb\rag.ps1 list
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$rag = Join-Path $root 'kb\Rag.java'
# Prefer a valid JAVA_HOME, otherwise fall back to `java` on PATH.
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') $rag @args
} else {
    & java $rag @args
}
