@echo off
rem Prova 6: una piccola chat a testo. Il PC spedisce messaggi di testo con il motore della App
rem e ne riceve. Serve un'identita' gia' caricata con prova4.bat. Scrive sul server.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=6
pause
