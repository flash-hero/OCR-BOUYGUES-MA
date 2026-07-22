# PFA Bouygues — Moteur OCR eDoc

Remplissage automatique du formulaire eDoc « Nouveau document » à partir du document déposé.
Quand un utilisateur dépose un plan technique, le système lit son **cartouche** (le bloc
d'identification du plan) et pré-remplit les champs du formulaire à sa place. L'utilisateur vérifie
et corrige l'exception, au lieu de tout saisir.

**Moteur de lecture : Mistral OCR.** **Stack imposée par l'encadrant : Java 17, Spring Boot 3.x.**
_(Un premier prototype de cadrage avait été esquissé en Python/FastAPI ; la contrainte JDK 17 imposée
par l'encadrant fixe désormais l'implémentation en Java.)_

> **État du dépôt :** la première livraison — l'incrément **ÉA1** (Mistral seul, détection &
> extraction génériques) — est implémentée et fonctionne. Voir la section
> [État actuel — ÉA1](#état-actuel--éa1-mistral-seul) pour installer, configurer et lancer.
> La suite (classification, validation, API REST, Tesseract) suit le plan en fin de document.

## Documentation

| Fichier | Contenu |
|---|---|
| [`docs/understand.md`](docs/understand.md) | Explication complète de l'architecture, en langage simple, avec exemples |
| [`docs/howtorun.md`](docs/howtorun.md) | Guide pratique pas à pas pour installer et lancer le moteur |
| [`docs/instruction.md`](docs/instruction.md) | Brief original du projet + état d'avancement à jour |
| [`docs/AGENT_CONTEXT.md`](docs/AGENT_CONTEXT.md) | Résumé technique dense pour reprendre le projet dans une nouvelle session IA |
| [`docs/plan_travail_ocr_edoc.md`](docs/plan_travail_ocr_edoc.md) | Plan de travail détaillé (étapes É1 à É8) |
| [`docs/Cahier des charges 1er Phase OCR.pdf`](<docs/Cahier des charges 1er Phase OCR.pdf>) | Cahier des charges original de l'encadrant |

---

## Principe : un lecteur, plusieurs vérificateurs

- **Un seul lecteur — Mistral OCR.** On ne lui demande pas « donne-moi tout le texte » ; on lui fournit un *schéma* (la liste exacte des champs, avec pour chacun une description et ses synonymes de libellés) et il le renvoie **rempli** en JSON structuré (`document_annotation_format`).
- **Plusieurs vérificateurs, non-IA.** Avant d'écrire une valeur dans le formulaire, chaque réponse passe un poste de contrôle : listes officielles eDoc (valeurs autorisées des menus déroulants), règles de format du numéro, et texte natif du PDF quand il existe.
- **L'IA propose, les vérificateurs disposent.** Les champs du formulaire sont des menus à valeurs imposées : une valeur inventée ne correspond à aucune entrée de la liste officielle, donc elle est repérée et jamais écrite comme fiable. **C'est le garde-fou anti-hallucination central.**

---

## Champs cibles

| Type | Champs |
|---|---|
| Obligatoires | `PHASE`, `EMETTEUR`, `LOT`, `TYPE`, `ZONE`, `NIVEAU`, `NUMERO`, `Titre1` |
| Optionnels | `Indice`, `Titre2`, `Titre3` *(Titre4 à confirmer, présent sur le formulaire)* |
| Hors schéma | `PROJET`, `Commune`, `Secteur`… capturés en métadonnées, jamais forcés dans un champ |

Champs codés (tous sauf les titres) : validés par rapprochement flou contre les tables de référence eDoc.

---

## Le parcours d'un document

1. **Réception** — le fichier arrive par l'API ; identification du format, isolation de la page 1 (seule porteuse du cartouche utile).
2. **Préparation** — redressement de l'orientation si le document est pivoté.
3. **Lecture** — un appel Mistral OCR : page + schéma + lexique des libellés → formulaire rempli (JSON) + confiance par mot.
4. **Contrôle** — rapprochement avec les listes officielles, mise au format du numéro, nettoyage des titres ; attribution d'un statut par champ.
5. **Restitution** — l'API renvoie le résultat champ par champ (valeur, statut, confiance) ; le formulaire eDoc s'en sert pour se pré-remplir.

---

## Trois statuts par champ

| Statut | Signification | Formulaire |
|---|---|---|
| `AUTO_VALIDATED` | Valeur lue **et** confirmée par la liste officielle | Rempli automatiquement |
| `TO_REVIEW` | Correspondance douteuse ou lecture peu sûre | Pré-rempli et mis en évidence |
| `MISSING` | Information absente ou illisible | Laissé vide — jamais rempli au hasard |

Indicateur métier : **taux d'automatisation** = % de champs `AUTO_VALIDATED` corrects. Chiffre à minimiser : le **taux d'erreur silencieuse** (`AUTO_VALIDATED` faux).

---

## Problèmes traités (constatés sur documents réels)

| Problème | Réponse |
|---|---|
| Position du cartouche variable (4 coins possibles sur A0/A1) | Le schéma dit *quoi* chercher, pas *où* ; l'OCR lit la page entière |
| Texte du cartouche minuscule sur un plan géant | Stratégie de repli : crop haute résolution autour du cartouche (voir ÉA1, lecture en deux passes) |
| Libellés différents selon les clients (N° Doc / N° GED / N° Chrono) | Lexique de synonymes par champ, en configuration |
| Documents scannés, parfois pivotés | Redressement systématique avant lecture (`/Rotate` + correction locale si besoin) |
| Hallucination sur champ codé | Aucune auto-validation sans match en table de référence |
| Nom de fichier trompeur | Jamais utilisé comme source de vérité ; seul le contenu fait foi |
| Formats multiples (PDF, Word, Excel, IFC) | PDF → OCR ; Word/Excel → lecture directe ; IFC → pas de cartouche |

---

## Deux stratégies d'extraction

- **Stratégie A (défaut)** — page 1 entière envoyée au lecteur. La plus simple, validée en premier.
- **Stratégie B (repli plans denses)** — si A échoue sur les grands formats, découpage haute résolution
  autour du cartouche. Le choix se tranche par la mesure, pas par opinion. **Cette stratégie B est
  déjà implémentée en ÉA1** sous la forme d'une lecture en deux passes (localisation puis extraction
  sur le découpage), avec contrôle qualité et repli sur les autres coins — voir plus bas.

---

## Leviers en réserve (si la précision l'exige)

Mistral OCR reste le seul lecteur ; on peut ajouter des *vérificateurs* :
- **Texte natif du PDF** comme contre-lecture gratuite (accord → confiance renforcée).
- **Double lecture** (deux formulations, comparaison des réponses).
- **Enrichissement du lexique** par client — modification de configuration, sans toucher au code.

---
---

# État actuel — ÉA1 (Mistral seul)

Cette première livraison couvre **uniquement l'ÉA1** : un socle Spring Boot + le premier appel à
**Mistral OCR** avec un **schéma d'annotation ouvert**, et le « test décisif » qui répond à une
question binaire : **Mistral peut-il détecter un cartouche générique et en extraire correctement les
paires libellé/valeur ?** Pas de classification, pas de validation, pas d'API REST, pas de base de
données, **pas de Tesseract** (phases ultérieures).

## Stack (ÉA1)

- Java **17** (imposé), Spring Boot **3.4.1**
- Appel Mistral OCR en REST direct via `RestClient` (pas de SDK Java)
- Apache PDFBox 3 (comptage de pages, base64, rendu d'une région de page)
- Tests : JUnit 5 + Mockito + AssertJ (hors ligne)

Modèle Mistral **épinglé** : `mistral-ocr-4-0` (jamais `-latest`, pour la reproductibilité).

## Prérequis

- JDK 17 dans le `PATH` (`java`/`javac`)
- Maven 3.9+
- Une clé API Mistral

## Configuration

1. Copiez le modèle d'environnement et renseignez votre clé :
   ```powershell
   Copy-Item .env.example .env
   # éditez .env :  MISTRAL_API_KEY=sk-...
   ```
   Le `.env` est chargé automatiquement au démarrage (voir `DotenvEnvironmentPostProcessor`) et
   **n'est jamais commité**. Une vraie variable d'environnement `MISTRAL_API_KEY` a la priorité sur le `.env`.

2. Déposez vos documents d'exemple dans **`data/samples/`** (voir le README de ce dossier).

   > **Pourquoi `data/samples/` et pas `src/main/resources/samples/` ?** Ce sont des plans internes
   > potentiellement confidentiels : on ne veut ni les empaqueter dans le JAR ni les charger dans le
   > classpath. `data/samples/*.pdf` est déjà ignoré par git ; seuls l'arborescence et le README sont versionnés.

### Cibler Azure AI Foundry (Mistral Document AI) au lieu de La Plateforme

Le client est agnostique de l'hébergeur : l'endpoint est surchargeable via `.env` (aucun changement de code).
Pour un déploiement **Azure AI Foundry**, ajoutez dans `.env` :

```dotenv
MISTRAL_OCR_BASE_URL=https://<resource>.services.ai.azure.com
MISTRAL_OCR_PATH=/providers/mistral/azure/ocr?api-version=2024-05-01-preview
MISTRAL_OCR_MODEL=<nom-du-déploiement>     # sur Azure, le nom du déploiement
```

Trois particularités Azure gérées par le code :
- **`?api-version=...` obligatoire** sur la route Foundry (sinon 400/404) → porté par `MISTRAL_OCR_PATH`.
- **`Content-Length` exigé** par la passerelle APIM : le corps est envoyé en `byte[]` (longueur connue),
  jamais en `Transfer-Encoding: chunked` (voir `MistralOcrClient` / `OcrConfig`).
- **Auth** : l'en-tête `Authorization: Bearer` **et** `api-key` sont envoyés — le serveur utilise celui qu'il reconnaît.

## 1. Vérifier l'environnement

```powershell
./scripts/check_setup.ps1
```

Contrôle, en échouant **proprement** (jamais de stack trace) : JDK 17 actif, javac 17, `JAVA_HOME`,
Maven présent, dépendances Maven résolues, clé API lisible, documents d'exemple présents. Code de
sortie non nul si un point bloquant manque. Option `-SkipMaven` pour sauter la résolution Maven.

## 2. Lancer le test décisif

```powershell
./scripts/run_smoke.ps1
```

Équivaut à `mvn -DskipTests spring-boot:run "-Dspring-boot.run.profiles=smoke"`. Pour chaque PDF de
`data/samples/`, le programme envoie le document à Mistral OCR avec le schéma d'annotation ouvert,
puis affiche `cartoucheFound`, la liste des paires **libellé → valeur**, et le **JSON brut** de
l'annotation. Le verdict sur la qualité de l'extraction est **humain** (lecture des résultats).

## Tests unitaires (hors ligne)

```powershell
mvn test
```

Ne touchent pas le réseau : ils vérifient le schéma ouvert, le parsing du `.env`, le contrat de
requête vers l'endpoint OCR (modèle épinglé, PDF en base64 data-URI, format d'annotation) via
`MockRestServiceServer`, le découpage d'une région et le contrôle qualité du cartouche.

## Le schéma d'annotation ouvert

On ne nomme **aucun** champ métier à l'avance. Le schéma JSON demandé à Mistral est :

```java
record CartoucheField(String label, String value) {}
record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {}
```

La classification (ranger chaque paire lue sur un champ cible d'un projet eDoc donné) et la
validation viendront dans des sessions ultérieures.

## Grands formats (plans A0+) : lecture en deux passes

Mistral redimensionne toute image à une taille fixe. Sur un plan de 2 m, le cartouche (petit, dans
un coin) devient illisible après ce redimensionnement, et la lecture pleine page renvoie
`cartoucheFound=false` — alors qu'un A4, même scanné, passe très bien. Les documents dont le grand
côté dépasse ~430 mm (au-delà de l'A3) sont donc traités en **deux passes** (voir
`TwoPassCartoucheExtractor`) — c'est la réalisation concrète de la *Stratégie B* décrite plus haut :

1. **Passe 1 — localisation grossière.** On demande à Mistral seulement la *zone* approximative du
   cartouche (grille 3×3, `CartoucheLocationSchema`), pas son contenu. Le prompt vise explicitement
   la **boîte-formulaire de codes courts**, pas le titre du plan.
2. **Passe 2 — extraction plein résolution.** On rend avec PDFBox un découpage généreux (40 %) autour
   de la zone, à haute résolution (`PdfSupport.renderRegionPng`), et on relance le schéma d'extraction
   ouvert sur cette **seule image**.
3. **Contrôle qualité + repli.** Le résultat n'est accepté que s'il *ressemble* à un cartouche
   (`CartouchePlausibility` : `cartoucheFound`, ≥ 3 paires, ≥ 3 paires « courtes »). Sinon — la passe 1
   s'est trompée, typiquement en visant le titre — on **replie** sur les autres coins un à un
   (bas-droite d'abord), en s'arrêtant au premier qui passe le contrôle. Aucun coin n'est figé par
   défaut : un coin n'est retenu que s'il produit un vrai cartouche.

Diagnostic : la propriété `edoc.force-corner` (ex. `-Dedoc.force-corner=bottom-right`) force la zone
de découpage et court-circuite la passe 1 + le repli — utile pour isoler « le découpage marche-t-il ? »
de « la localisation est-elle correcte ? ». Vide en production.

## Fiabilité et latence de l'extraction (consensus, score de coin, vague unique)

**Méthodologie.** Chaque décision de cette section vient d'une **mesure**, jamais d'une supposition :
réglages rendus configurables, matrice de configurations testées **une variable à la fois** sur un
panel fixe de 4 documents difficiles, cache vidé pour forcer un vrai appel, comparaison du temps **et**
du contenu réel des paires extraites (pas seulement leur nombre), et un run isolé sur un document seul
pour distinguer « trop d'appels à la suite » (throttling de lot) du coût réel d'un appel. Une première
tentative (découpage en deux vagues réseau, exploration légère puis confirmation) a d'abord été
adoptée sans cette mesure isolée ; elle s'est révélée, une fois mesurée, deux fois plus lente et moins
fiable — reprise ci-dessous sous sa forme corrigée. Détail complet, y compris les leviers testés et
rejetés (résolution réduite, zone de coin réduite), dans `docs/AGENT_CONTEXT.md` §2bis/§2ter.

Constat vérifié sur la référence API Mistral : l'endpoint `/v1/ocr` n'expose **ni `temperature`, ni
`seed`, ni `random_seed`** — deux requêtes strictement identiques peuvent renvoyer des résultats
différents (mesuré : `12.pdf` a basculé `cartoucheFound` true→false d'un run à l'autre ; `15.pdf`
77→19 paires). Impossible de fixer la reproductibilité au niveau de la requête.

**Consensus (`OcrConsensus.java`).** Chaque appel OCR est échantillonné **`mistral.ocr.samples` fois
en parallèle** (5 par défaut). Le résultat retenu : vote majoritaire sur `cartoucheFound`, puis médiane
basse du nombre de paires parmi le camp majoritaire — jamais de fusion ni de filtrage de paires, on
choisit un échantillon réel, verbatim. Le résultat du vote est ce qui est figé dans le cache : un
document déjà traité ne recoûte rien. Un échantillon dont le JSON est tronqué/malformé est traité
comme « pas d'extraction » plutôt que de faire échouer tout le document. `mistral.ocr.max-retries`
(2 par défaut) réessaie avec back-off exponentiel sur 429/503/timeout.

**Score de coin (`CartoucheScore.java`).** Sur `20.pdf`, le panneau des intervenants (adresses,
téléphones, rôles) passait le contrôle `CartouchePlausibility` — il a bien des lignes courtes remplies
— mais n'est pas le cartouche. `CartoucheScore` note chaque extraction : bonus pour les libellés
d'identification (Phase, Indice, Échelle, Lot, N° document...) et les valeurs courtes de type code,
malus pour les signaux d'adresse/téléphone/e-mail/rôle d'intervenant. Parmi les coins qui passent le
contrôle qualité, `TwoPassCartoucheExtractor` retient désormais le **meilleur score**, pas le premier
qui passe — `20.pdf` renvoie maintenant le vrai bloc d'identification.

**Une seule vague réseau, Passe 1 désactivée par défaut.** Mesuré (docs 11/12/16/20, caches vides) :
le coût dominant est le **temps d'OCR par appel**, proportionnel au volume de texte de l'image envoyée
— pas le nombre d'appels en soi. La Passe 1 (localisation pleine page) OCRise tout le plan sur un
document dense : c'était l'appel le plus lent (~40 % du temps total), pour un rôle qui ne sert qu'à
départager le score entre coins à égalité. Elle a donc été **désactivée par défaut**
(`mistral.ocr.locate-enabled=false`, réactivable sans changement de code) après avoir vérifié qu'elle
choisissait le même coin que le score seul sur les documents testés. Les quatre coins candidats sont
maintenant rendus (CPU) puis analysés (réseau) **tous en même temps**, au lieu d'un balayage séquentiel.

Deux leviers de latence testés et **rejetés sur mesure** : réduire `crop-long-px` (3400→2400) n'a
donné aucun gain de temps et a dégradé une lecture (`16.pdf` 13→29 paires, lecture confuse) ;
réduire `corner-fraction` (0.40→0.28) a coupé le cartouche d'un document (`12.pdf`, tous les coins
vides → `NEEDS_TILING`). Les deux réglages restent à leur valeur d'origine.

Profil de latence livré (mesuré, run isolé) : pages standard ~5-15 s, grands plans ~13-25 s, les trois
plans A0 les plus denses du corpus ~26-28 s — plancher d'un appel unique à pleine exactitude sur ce
volume de texte ; descendre en dessous dégrade la lecture plutôt que de l'accélérer (mesuré deux fois).

Réglages exposés (`mistral.ocr.*`, tous surchargeables sans toucher au code) :

| Propriété | Défaut | Rôle |
|---|---|---|
| `samples` | 5 | Échantillons par appel (consensus) |
| `max-retries` | 2 | Nouvelles tentatives sur 429/503/timeout, back-off exponentiel |
| `crop-long-px` | 3400 | Résolution du découpage de coin envoyé à Mistral |
| `locate-long-px` | 1400 | Résolution du rendu pleine page pour la Passe 1 |
| `corner-fraction` | 0.40 | Fraction de page prise depuis chaque coin |
| `locate-enabled` | false | Active la Passe 1 (départage uniquement ; désactivée par défaut, voir ci-dessus) |

## Structure du code (ÉA1)

```
src/main/java/com/bycn/edoc
├─ EdocOcrApplication.java            # main Spring Boot
├─ config/DotenvEnvironmentPostProcessor.java  # charge .env sans dépendance externe
├─ ocr/
│  ├─ MistralOcrProperties.java       # config mistral.ocr.* (modèle épinglé, samples, latence)
│  ├─ MistralOcrClient.java           # POST /v1/ocr : analyze / locate, échantillonnage parallèle
│  ├─ OcrConsensus.java               # vote majoritaire sur N échantillons (non-déterminisme)
│  ├─ CartoucheAnnotationSchema.java  # schéma OUVERT d'extraction + prompt
│  ├─ CartoucheLocationSchema.java    # schéma de localisation (passe 1, zone)
│  ├─ TwoPassCartoucheExtractor.java  # orchestration grands formats : vague unique + sélection
│  ├─ CartouchePlausibility.java      # « ça ressemble à un cartouche ? » (contrôle de forme)
│  ├─ CartoucheScore.java             # départage entre coins plausibles (contrôle de contenu)
│  ├─ CropRegion.java                 # zone → rectangle de découpage
│  ├─ CartoucheField / CartoucheExtraction / CartoucheLocation / OcrResult / CartoucheAnalysis  # records
│  ├─ PdfSupport.java                 # base64, dimensions, rendu d'une région (PDFBox)
│  └─ OcrConfig.java                  # câblage des beans
└─ smoke/SmokeTestRunner.java         # le « test décisif » (profil smoke)
```

---
---

# Plan de travail (cible)

## Structure du dépôt (cible)

Découpage conceptuel en modules (indépendant du langage ; réalisé en Java/Spring Boot) :

```
edoc-ocr/
├── ingestion/     # réception, page 1, orientation
├── extraction/    # client Mistral OCR, schéma d'annotation, stratégies A/B, cache
├── validation/    # matching tables de référence, normalisation, statuts
├── api/           # API REST (Spring Boot), DTO, jobs
├── evaluation/    # harnais de mesure, métriques
└── config/
    ├── schema_fields.(yaml)     # champs + synonymes de libellés
    ├── thresholds.(yaml)        # seuils de match, pondérations de confiance
    └── reference_tables/        # tables de référence eDoc (CSV)
```

**Conventions :** code, commentaires et identifiants en anglais ; documentation en français.

## Étapes de travail

| Étape | Contenu | Sortie |
|---|---|---|
| É1 | Accès Mistral OCR, premier appel ; 2 tests décisifs : doc pivoté à 90° lu ? cartouche A0 lisible en pleine page ? Version du modèle figée | Réponse aux 2 tests |
| É2 | Réception, page 1, orientation | Corpus traité sans erreur |
| É3 | Schéma d'annotation + lexique, appel OCR, cache | JSON rempli par document |
| É4 | Contrôle : matching tables, format numéro, statuts | Sortie complète avec statuts |
| É5 | Harnais de mesure + QA vérité terrain, run baseline | Précision par champ et par famille |
| É6 | Amélioration guidée par les chiffres (stratégie B si besoin), 2e run | Précision en hausse mesurée |
| É7 | API REST (dépôt → résultat champ par champ) | POST/GET fonctionnels |
| É8 | Rapport + démonstration | Livraison |

> **Avancement :** l'incrément **ÉA1** livré ici couvre l'essentiel de É1 et É3 (premier appel OCR,
> schéma d'annotation ouvert, modèle figé, test décisif) et anticipe la **stratégie B** de É6
> (lecture en deux passes des grands formats).

## Points à confirmer avec l'encadrant

- **Titre4** — présent sur le formulaire, absent du schéma discuté : à extraire ?
- **Champ Projet** — pré-rempli par le contexte projet : ne vient pas du cartouche (à confirmer).
- **Indice** — défaut « A » : la valeur lue doit-elle le remplacer ?
- **Fichier source** — lecture sur la copie PDF quand natif + PDF déposés (à confirmer).
- **Format du numéro** — convention unique de zéros de tête (« 085 » vs « 000439 »).
- **Libellés en hypothèse** — Spécialité/Discipline → LOT, Localisation → ZONE, DOC → TYPE : à valider avant activation.
