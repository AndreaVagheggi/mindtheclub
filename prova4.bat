@echo off
rem Prova 4: il PC conserva identita' e dati. La prima volta carica un backup, le volte
rem successive riapre quello che ha salvato senza chiedere nulla. Non usa la rete.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=4
pause
