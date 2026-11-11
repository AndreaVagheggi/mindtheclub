@echo off
rem Prova 7: il ritorno al telefono. Il PC scrive un backup nel formato del telefono e lascia
rem l'identita'. Non usa la rete. Dopo, il PC non parte piu' finche' non si ricarica un backup.
cd /d "%~dp0"

rem Gradle ha bisogno di Java per partire: se il PC non ne ha uno dichiarato, usa quello
rem di Android Studio, lo stesso con cui si compila la App.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat --console=plain run -Pprova=7
pause
