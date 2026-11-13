@echo off
setlocal
set "NEXODB_JAVA=javaw.exe"
if defined JAVA_HOME set "NEXODB_JAVA=%JAVA_HOME%\bin\javaw.exe"
"%NEXODB_JAVA%" -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true -Xms16m -Xmx256m -jar "%~dp0app\nexodb-studio.jar" %*
