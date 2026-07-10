#!/usr/bin/env pwsh
# run_smoke.ps1 — Lance le "test decisif" Mistral OCR (profil smoke, sans serveur web).
# Lit tous les *.pdf de data/samples/ et affiche les paires libelle/valeur + le JSON brut.

$ErrorActionPreference = 'Stop'
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

# Force UTF-8 pour que les accents francais s'affichent correctement.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = '-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

Push-Location $root
try {
    Write-Host 'Lancement du test decisif (profil smoke)...' -ForegroundColor Cyan
    & mvn -q -DskipTests spring-boot:run "-Dspring-boot.run.profiles=smoke" $jvmArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
