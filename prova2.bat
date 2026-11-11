@echo off
rem Prova 2: il PC prende l'identita' da un backup e riceve i segnali al posto del telefono.
rem ATTENZIONE: scrive sul server. Il telefono del backup si disattiva. Solo identita' di prova.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=2
pause
