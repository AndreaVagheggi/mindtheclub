@echo off
rem Prova 1: legge un backup di MindTheClub e controlla che l'identita' sia la stessa
rem del telefono. Non scrive nulla, ne' sul PC ne' sul server.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=1
pause
