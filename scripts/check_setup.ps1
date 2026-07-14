#!/usr/bin/env pwsh
# check_setup.ps1 — Verifie l'environnement pour eDoc OCR (EA1, Mistral seul).
# Objectif : echouer proprement et lisiblement si quelque chose manque, sans stack trace.
#
# Usage (depuis la racine du projet) :
#   ./scripts/check_setup.ps1            (verifie tout, resout les dependances Maven)
#   ./scripts/check_setup.ps1 -SkipMaven (saute la resolution Maven, plus rapide)

param(
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
$script:hardFailures = 0
$script:warnings = 0

function Write-Result {
    param([string]$Status, [string]$Label, [string]$Detail = '')
    $color = switch ($Status) {
        'OK'   { 'Green' }
        'WARN' { 'Yellow' }
        'FAIL' { 'Red' }
        default { 'Gray' }
    }
    Write-Host ("[{0}]" -f $Status).PadRight(7) -ForegroundColor $color -NoNewline
    Write-Host " $Label" -NoNewline
    if ($Detail) { Write-Host "  ($Detail)" -ForegroundColor DarkGray } else { Write-Host '' }
    if ($Status -eq 'FAIL') { $script:hardFailures++ }
    elseif ($Status -eq 'WARN') { $script:warnings++ }
}

function Get-JavaMajor {
    param([string]$VersionText)
    # Couvre :  java version "17.0.12"  /  openjdk version "17.0.1"  /  javac 17.0.12  /  javac 17
    foreach ($pattern in @('(\d+)\.\d+\.\d+', '"(\d+)', 'javac\s+(\d+)', 'version\s+(\d+)', '(\d+)')) {
        if ($VersionText -match $pattern) { return [int]$Matches[1] }
    }
    return 0
}

Write-Host ''
Write-Host '=== check_setup — eDoc OCR (EA1 / Mistral) ===' -ForegroundColor Cyan
# Le script vit dans scripts/ : la racine du projet est le dossier parent.
$root = if ($PSScriptRoot) { Split-Path $PSScriptRoot -Parent } else { (Get-Location).Path }
Write-Host "Racine projet : $root"
Write-Host ''

# 1) JDK 17 (java)
try {
    $javaRaw = (& java -version 2>&1 | Out-String)
    $major = Get-JavaMajor $javaRaw
    $first = ($javaRaw -split "`n")[0].Trim()
    if ($major -eq 17) {
        Write-Result 'OK' 'JDK (java) actif en version 17' $first
    } elseif ($major -gt 0) {
        Write-Result 'FAIL' "java est en version $major, or JDK 17 est impose" $first
    } else {
        Write-Result 'FAIL' 'Impossible de determiner la version de java'
    }
} catch {
    Write-Result 'FAIL' 'java introuvable dans le PATH' 'Installez un JDK 17'
}

# 2) javac 17 (le JDK, pas seulement le JRE)
try {
    $javacRaw = (& javac -version 2>&1 | Out-String).Trim()
    $major = Get-JavaMajor $javacRaw
    if ($major -eq 17) {
        Write-Result 'OK' 'Compilateur (javac) en version 17' $javacRaw
    } elseif ($major -gt 0) {
        Write-Result 'FAIL' "javac est en version $major, or JDK 17 est impose" $javacRaw
    } else {
        Write-Result 'FAIL' 'Impossible de determiner la version de javac'
    }
} catch {
    Write-Result 'FAIL' 'javac introuvable' 'Un JRE ne suffit pas : installez le JDK 17 complet'
}

# 3) JAVA_HOME (informatif)
if ($env:JAVA_HOME) {
    if (Test-Path $env:JAVA_HOME) {
        Write-Result 'OK' 'JAVA_HOME defini' $env:JAVA_HOME
    } else {
        Write-Result 'WARN' 'JAVA_HOME pointe vers un dossier inexistant' $env:JAVA_HOME
    }
} else {
    Write-Result 'WARN' 'JAVA_HOME non defini' 'Recommande pour Maven/IDE, non bloquant si java est dans le PATH'
}

# 4) Maven present
$mvnOk = $false
try {
    $mvnRaw = (& mvn -version 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0) {
        $mvnOk = $true
        Write-Result 'OK' 'Maven disponible' (($mvnRaw -split "`n")[0].Trim())
    } else {
        Write-Result 'FAIL' 'mvn a renvoye une erreur' (($mvnRaw -split "`n")[0].Trim())
    }
} catch {
    Write-Result 'FAIL' 'mvn introuvable dans le PATH' 'Installez Apache Maven'
}

# 5) Dependances Maven resolues
$pom = Join-Path $root 'pom.xml'
if (-not (Test-Path $pom)) {
    Write-Result 'FAIL' 'pom.xml introuvable a la racine'
} elseif ($SkipMaven) {
    Write-Result 'WARN' 'Resolution Maven sautee (-SkipMaven)' 'Lancez `mvn dependency:resolve` au moins une fois'
} elseif ($mvnOk) {
    Write-Host '        ... resolution des dependances (peut telecharger au 1er run) ...' -ForegroundColor DarkGray
    try {
        & mvn -q -f $pom -DskipTests dependency:resolve *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Result 'OK' 'Dependances Maven resolues'
        } else {
            Write-Result 'WARN' 'Resolution des dependances incomplete' 'Verifiez le reseau puis relancez `mvn dependency:resolve`'
        }
    } catch {
        Write-Result 'WARN' 'Echec de la resolution Maven' $_.Exception.Message
    }
}

# 6) Cle API Mistral (jamais affichee)
$apiKeyFound = $false
$apiKeySource = ''
if ($env:MISTRAL_API_KEY -and $env:MISTRAL_API_KEY.Trim().Length -gt 0) {
    $apiKeyFound = $true
    $apiKeySource = "variable d'environnement"
} else {
    $envFile = Join-Path $root '.env'
    if (Test-Path $envFile) {
        $match = Select-String -Path $envFile -Pattern '^\s*MISTRAL_API_KEY\s*=\s*(.+)$' | Select-Object -First 1
        if ($match) {
            $val = $match.Matches[0].Groups[1].Value.Trim().Trim('"').Trim("'")
            if ($val.Length -gt 0) { $apiKeyFound = $true; $apiKeySource = 'fichier .env' }
        }
    }
}
if ($apiKeyFound) {
    Write-Result 'OK' 'Cle API Mistral presente' "source : $apiKeySource (valeur masquee)"
} else {
    Write-Result 'FAIL' 'Cle API Mistral absente' 'Copiez .env.example vers .env et renseignez MISTRAL_API_KEY'
}

# 7) Documents d'exemple
$samplesDir = Join-Path $root 'data/samples'
$expected = @('6.pdf', '16.pdf', '25.pdf')
if (-not (Test-Path $samplesDir)) {
    Write-Result 'FAIL' 'Dossier data/samples/ absent' 'Creez-le et deposez-y les PDF d exemple'
} else {
    $present = @($expected | Where-Object { Test-Path (Join-Path $samplesDir $_) })
    $missing = @($expected | Where-Object { -not (Test-Path (Join-Path $samplesDir $_)) })
    $anyPdf = Get-ChildItem -Path $samplesDir -Filter *.pdf -File -ErrorAction SilentlyContinue
    if ($present.Count -eq $expected.Count) {
        Write-Result 'OK' 'Documents d exemple presents' ($expected -join ', ')
    } elseif ($anyPdf) {
        Write-Result 'WARN' 'Documents d exemple partiels' ("presents : $($present -join ', ') ; manquants : $($missing -join ', ')")
    } else {
        Write-Result 'FAIL' 'Aucun PDF dans data/samples/' 'Deposez 6.pdf, 16.pdf, 25.pdf'
    }
}

# Bilan
Write-Host ''
Write-Host '=== Bilan ===' -ForegroundColor Cyan
if ($script:hardFailures -eq 0 -and $script:warnings -eq 0) {
    Write-Host 'Tout est pret. Lancez le test decisif :  ./run_smoke.ps1' -ForegroundColor Green
    exit 0
} elseif ($script:hardFailures -eq 0) {
    Write-Host "Environnement utilisable, mais $($script:warnings) avertissement(s) a regarder." -ForegroundColor Yellow
    exit 0
} else {
    Write-Host "$($script:hardFailures) blocage(s) et $($script:warnings) avertissement(s). Corrigez les [FAIL] ci-dessus." -ForegroundColor Red
    exit 1
}
