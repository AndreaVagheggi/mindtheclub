@echo off
rem Prova 3: il PC riceve un messaggio di testo da un telefono e lo conferma.
rem ATTENZIONE: scrive sul server e conferma i messaggi senza salvarli. Solo identita' di prova.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=3
pause
