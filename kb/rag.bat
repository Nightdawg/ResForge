@echo off
rem Convenience wrapper: run the local knowledge-base retrieval tool.
rem   kb\rag query "your question" [-k N] [-d dir]...
rem   kb\rag list
setlocal
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\graalvm-jdk-21.0.9+7.1"
"%JAVA_HOME%\bin\java.exe" "%~dp0Rag.java" %*
endlocal
