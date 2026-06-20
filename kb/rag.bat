@echo off
rem Convenience wrapper: run the local knowledge-base retrieval tool.
rem   kb\rag "your question" [-k N] [-f] [-d dir]...
rem   kb\rag -f "your question"      rem print whole chunks
rem   kb\rag list
setlocal
rem Prefer a valid JAVA_HOME, otherwise fall back to `java` on PATH.
if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java.exe" "%~dp0Rag.java" %*
) else (
  java "%~dp0Rag.java" %*
)
endlocal
