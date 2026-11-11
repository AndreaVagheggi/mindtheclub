# Confronta i file del motore copiati dalla App con la App di oggi.
#
# motore\manifest.txt ha una riga per file:  stato|percorso|sha256
#   stato      identico    il file sul PC deve essere uguale a quello della App
#              modificato  il file sul PC e' stato adattato (ogni differenza e' marcata
#                          con un commento "DESKTOP:")
#   percorso   relativo a com\bolimot\mindtheclub, uguale nelle due cartelle
#   sha256     impronta del file DELLA APP nel momento in cui e' stato copiato
#
# Dice due cose:
#   1. quali file sono cambiati nella App dopo la copia (una correzione da portare sul PC);
#   2. quali file "identici" sul PC non lo sono piu' (una modifica fatta sul PC senza segnarla).
#
# Non modifica nulla. Windows PowerShell 5.1.

$app = "C:\mtc\app\src\main\java\com\bolimot\mindtheclub"
$pc = Join-Path $PSScriptRoot "..\src\main\kotlin\com\bolimot\mindtheclub"
$manifest = Join-Path $PSScriptRoot "manifest.txt"

$cambiatiNellaApp = @()
$divergentiSulPc = @()
$mancanti = @()
$totale = 0

foreach ($riga in Get-Content $manifest) {
    if ([string]::IsNullOrWhiteSpace($riga)) { continue }
    $parti = $riga.Split("|")
    if ($parti.Length -ne 3) { continue }
    $stato = $parti[0]
    $percorso = $parti[1].Replace("/", "\")
    $improntaCopia = $parti[2].ToLower()
    $totale++

    $fileApp = Join-Path $app $percorso
    $filePc = Join-Path $pc $percorso

    if (-not (Test-Path $fileApp)) { $mancanti += "$percorso  (non esiste piu' nella App)"; continue }
    if (-not (Test-Path $filePc)) { $mancanti += "$percorso  (non esiste sul PC)"; continue }

    $improntaApp = (Get-FileHash $fileApp -Algorithm SHA256).Hash.ToLower()
    if ($improntaApp -ne $improntaCopia) { $cambiatiNellaApp += "$percorso  ($stato)" }

    if ($stato -eq "identico") {
        $improntaPc = (Get-FileHash $filePc -Algorithm SHA256).Hash.ToLower()
        if ($improntaPc -ne $improntaCopia) { $divergentiSulPc += $percorso }
    }
}

Write-Host ""
Write-Host "File del motore sotto controllo: $totale"
Write-Host ""

if ($cambiatiNellaApp.Count -eq 0) {
    Write-Host "[OK]  Nessun file e' cambiato nella App dopo la copia."
} else {
    Write-Host "[!!]  Cambiati nella App dopo la copia, da riportare sul PC:"
    $cambiatiNellaApp | ForEach-Object { Write-Host "        $_" }
}

if ($divergentiSulPc.Count -eq 0) {
    Write-Host "[OK]  I file marcati identici lo sono ancora."
} else {
    Write-Host "[!!]  Marcati identici ma diversi sul PC:"
    $divergentiSulPc | ForEach-Object { Write-Host "        $_" }
}

if ($mancanti.Count -gt 0) {
    Write-Host "[!!]  File mancanti:"
    $mancanti | ForEach-Object { Write-Host "        $_" }
}
Write-Host ""
