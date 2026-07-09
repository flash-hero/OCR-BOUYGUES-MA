# Moteur OCR eDoc — Plan de travail
**Java / JDK 17 · Détection de cartouche + classification générique**
PFA Oussama — Bouygues Construction IT Maroc · Encadrant : M. Boumenzeh
Version 6.0

---

## 0. Objectif et principe

Quand un utilisateur dépose un document technique dans eDoc, le système doit lire son cartouche (bloc d'identification) et pré-remplir automatiquement les champs du formulaire, pour n'importe quel projet eDoc — chaque projet ayant sa propre liste de champs configurés (Codification + Champs personnalisés).

Le principe repose sur deux étapes séparées :

1. **Lire** : localiser le cartouche sur le document et en extraire tous les libellés et valeurs qu'il contient, tels qu'imprimés, sans supposer à l'avance quels champs existent.
2. **Classer** : ranger chaque paire (libellé, valeur) lue sur le bon champ cible — la liste des champs requis pour un document donné est fournie par l'appel API, avec le code du projet eDoc concerné.

Un champ classé est ensuite validé contre la table de référence de son projet (liste des valeurs officielles) avant d'être proposé à l'utilisateur. **Aucune table de référence n'est un vocabulaire fermé** : une valeur absente de la table n'est jamais rejetée automatiquement, elle déclenche une vérification humaine — n'importe quel champ peut légitimement recevoir une valeur inédite.

Deux procédures de lecture sont testées en parallèle pour décider empiriquement du meilleur moteur :
- **Procédure 1 (hybride)** : Tesseract pour les pages de garde, Mistral OCR pour les plans denses.
- **Procédure 2 (mono-outil)** : Mistral OCR pour tout.

La comparaison se joue uniquement sur les pages de garde — sur les plans denses, les deux procédures utilisent Mistral de la même façon.

---

## 1. Architecture — 7 modules

| Module | Rôle |
|---|---|
| **P0** — Ingestion & routage | Réception du document (PDF/DOCX/XLSX), isolation de la page 1 |
| **P1** — Préparation | Correction d'orientation (`/Rotate`), rendu de la page en image |
| **P2** — Détection + extraction générique | Localiser le cartouche, en extraire tous les libellés/valeurs, sans schéma nommé à l'avance |
| **P3** — Classification | Ranger chaque paire (libellé, valeur) sur un champ requis, par correspondance floue |
| **P4** — Validation | Correspondance floue contre la table de référence du projet, statuts `AUTO_VALIDATED` / `TO_REVIEW` / `MISSING` |
| **P5** — API REST | Reçoit document + champs requis + code projet → résultat par champ |
| **P6** — Harnais d'évaluation | Mesure comparative Tesseract vs Mistral, précision par champ et par famille de document |

Deux usages distincts de la correspondance floue, à ne jamais confondre dans le code :
- **P3** compare un *libellé* lu (ex. "N° Doc", "LEVEL") à une liste de synonymes de champ
- **P4** compare une *valeur* lue (ex. "EX3", "Bouygues") à une table de référence

---

## 2. Stack technique (Java, JDK 17)

| Brique | Outil | Rôle |
|---|---|---|
| Framework applicatif / API REST | **Spring Boot 3.x** | Compatible JDK 17, standard en entreprise pour ce type de service |
| Build | **Maven** | Gestion des dépendances et du build |
| Appel Mistral OCR | **REST direct** (`RestClient` / `WebClient` de Spring) | Mistral ne publie pas de SDK Java officiel — appel HTTP/JSON écrit à la main, isolé dans une seule classe |
| Lecture/rendu PDF | **Apache PDFBox** | Lecture `/Rotate`, rendu page 1 en image |
| Lecture DOCX/XLSX | **Apache POI** | Documents déjà textuels — lecture directe, pas d'OCR |
| OCR (moteur Tesseract) | **Tess4J** | Wrapper Java du moteur Tesseract natif ; binaire système + packs `fra` et `eng` requis |
| Détection de structure (cartouche, voie Tesseract) | **OpenCV Java** (`org.openpnp:opencv`) | Détection de contours/grille pour localiser le cartouche sans compréhension sémantique |
| Correspondance floue (P3, P4) | **`me.xdrop:fuzzywuzzy`** | Port Java de l'algorithme utilisé par RapidFuzz |
| Schéma d'extraction, sérialisation | **Java records + Jackson** | Schéma ouvert pour Mistral, DTO d'API |
| Configuration (YAML, CSV) | **SnakeYAML** (inclus Spring Boot) + **Apache Commons CSV** | Lecture des lexiques et tables de référence |
| Traitement asynchrone | **`@Async` Spring** | Traitement en tâche de fond après réception d'un document |
| Tests | **JUnit 5 + Mockito** | Tests unitaires du pipeline |

Modèle Mistral OCR épinglé : `mistral-ocr-4-0` (jamais `mistral-ocr-latest`, pour des métriques reproductibles).

---

## 3. Détail par module

### P0 / P1 — Ingestion et préparation
`@RestController` Spring reçoit un `MultipartFile` et un DTO JSON (`requiredFields: List<String>`, `projectCode: String`). Détection de format par extension. PDFBox lit l'attribut `/Rotate` et rend la page 1 en `BufferedImage` (300–400 DPI) pour la voie Tesseract ; Mistral reçoit le PDF directement, sans rendu préalable.

### P2 — Détection + extraction générique

**Mistral OCR** : appel avec un schéma d'annotation ouvert — aucun nom de champ métier dans le schéma, seulement une structure générique :
```java
record CartoucheField(String label, String value) {}
record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {}
```
Le prompt d'annotation demande de localiser le bloc d'identification et de recopier chaque libellé avec sa valeur telle qu'imprimée, sans interpréter ni traduire.

**Tesseract (via Tess4J)** : n'a pas de compréhension sémantique, donc détection structurelle nécessaire — recherche de grille/boîte à bordures par contours (`Imgproc.findContours`) ou détection de lignes (`Imgproc.HoughLinesP`), le cartouche se distinguant visuellement du reste de la page par sa structure en tableau. OCR de la zone détectée en `fra+eng` (le corpus contient des libellés anglais ponctuels : "LEVEL", "As indicated"). Appariement libellé/valeur par géométrie des bounding boxes — au moins deux mises en page coexistent dans le corpus (libellé à gauche de sa valeur sur une ligne ; libellé au-dessus de sa valeur sur deux lignes), l'heuristique doit gérer les deux.

### P3 — Classification
`FuzzySearch.ratio(...)` (fuzzywuzzy) compare chaque libellé brut aux synonymes connus pour chaque champ demandé dans l'appel API. Bibliothèque de synonymes **partagée entre tous les projets** (`schema_fields.yaml`, un seul fichier, multilingue) — un même nom de champ a le même sens partout, seul le sous-ensemble de champs actifs varie par appel. Jamais de comparaison stricte (`==`) entre nom de champ demandé et libellé lu : une correspondance manquée à tort transforme un champ présent en faux `MISSING`.

### P4 — Validation
Correspondance floue contre `config/projects/{projectCode}/reference_tables/*.csv`. Statuts (`enum FieldStatus { AUTO_VALIDATED, TO_REVIEW, MISSING }`). Règle absolue : un non-match ne rejette jamais silencieusement une valeur — il déclenche toujours `TO_REVIEW`, sur n'importe quel champ, sans exception.

### P5 — API REST
`POST /extractions` (document + `requiredFields` + `projectCode`) → `202 {jobId}`, traitement en tâche de fond (`@Async`). `GET /extractions/{jobId}` → résultat par champ (valeur, statut, confiance, moteur utilisé).

### P6 — Harnais d'évaluation
Classe Java exécutable (pas un test JUnit — c'est un script de mesure) : exécute P2→P3→P4 avec chaque moteur sur le corpus annoté, produit un rapport comparatif (précision par champ, par famille de document, coût, latence).

---

## 4. Configuration

- `config/schema_fields.yaml` — bibliothèque partagée de synonymes de libellés par nom de champ, multilingue (français + anglais).
- `config/projects/{projectCode}/reference_tables/*.csv` — valeurs acceptées par champ, propres à chaque projet eDoc (colonnes `code`, `libelle`).

---

## 5. Structure du projet

```
edoc-ocr/
├── pom.xml
├── src/main/java/com/bouygues/edocor/
│   ├── EdocOcrApplication.java
│   ├── ingestion/       # P0, P1
│   ├── extraction/      # P2 : MistralClient, TesseractExtractor, détection cartouche
│   ├── classification/  # P3
│   ├── validation/      # P4
│   ├── api/             # P5 : controllers, DTO
│   └── evaluation/      # P6 : harnais exécutable
├── src/main/resources/
│   ├── application.yml
│   ├── schema_fields.yaml
│   └── projects/{projectCode}/reference_tables/*.csv
├── src/test/java/...
└── README.md
```

---

## 6. Étapes de travail

| Étape | Durée | Contenu | Sortie |
|---|---|---|---|
| É1 | 2–3 j | Scaffold Maven/Spring Boot, config Tess4J + packs natifs (fra, eng), config OpenCV Java. Test décisif : Mistral détecte-t-il le cartouche en schéma ouvert, sur une page de garde native, un plan dense, un scan ? | Environnement prêt, réponse au test décisif |
| É2 | 1–2 j | P0/P1 : PDFBox, routage | Tronc d'ingestion prêt |
| É3 | 4–5 j | P2 : appel REST Mistral + schéma ouvert ; détection structurelle OpenCV + Tess4J + appariement | Paires (libellé, valeur) brutes, deux moteurs |
| É4 | 2 j | P3 : classification | Champs classés |
| É5 | 1–2 j | P4 : validation | Statuts calculés |
| É6 | 1–2 j | P6 : harnais comparatif Tesseract/Mistral | Tableau comparatif chiffré, décision du moteur retenu pour les pages de garde |
| É7 | 1–2 j | Itération sur les points faibles mesurés | Précision en hausse |
| É8 | 1–2 j | P5 : API REST | POST/GET fonctionnels |
| É9 | 1–2 j | Rapport + démo | Livrables prêts |

**Total estimé : 15–21 jours ouvrés.**

---

## 7. Ce qui reste à obtenir ou décider

- `data/annotations.xlsx` (vérité terrain) — valeur correcte de chaque champ pour le corpus de test, à saisir manuellement. Bloquant pour É6, pas pour É1–É5.
- Tables `FORMAT_DU_PLAN` et `ECHELLE_DU_PLAN` — proposées à partir de l'échantillon de documents disponible, à valider avec l'équipe eDoc.
- Normalisation de la notation d'échelle avant comparaison (`1/50`, `1:50`, `1 : 50` coexistent selon le gabarit).
- Champ `DATE`, présent dans le cartouche sur les documents inspectés mais absent du schéma actuel — à ajouter ou volontairement laisser hors schéma.
- Convention de zéros de tête pour `NUMERO`, valeur par défaut du champ `Indice`, présence du champ `Titre4`.

---

## 8. Risques

| Risque | Impact | Parade |
|---|---|---|
| Détection de cartouche échoue (zone ratée ou mauvaise zone choisie) | Rien à classer, ou du bruit classé à tort | Test décisif dès É1 ; lecture "page entière" en repli si la détection échoue trop souvent |
| Appariement libellé/valeur ambigu (plusieurs mises en page coexistent) | Valeurs mal associées à leur libellé, silencieusement | Détecter le type de mise en page avant d'apparier ; mesurer ce taux d'erreur spécifiquement en É6 |
| Configuration des bibliothèques natives (Tess4J, OpenCV) capricieuse en Java | Temps perdu en É1 sur des erreurs d'environnement | Script `check_setup` en tout début d'É1, qui isole précisément ce qui échoue |
| Pas de SDK Mistral officiel en Java | Code d'appel à écrire et maintenir à la main | Appel isolé dans une seule classe pour limiter l'impact d'un changement de format côté Mistral |
| Configuration projet incomplète (synonymes manquants) | Libellés valides classés à tort en "hors schéma" | Enrichissement itératif du lexique partagé, jamais un blocage |
| Verbosité Java sur une phase qui reste exploratoire (réglage des heuristiques d'appariement) | Cycles d'itération plus lents qu'un prototypage rapide | Accepté comme coût de l'intégration ; pas de sur-architecture avant le résultat du test décisif É1 |

---

## 9. Rapport

Le rapport final justifie, chiffres à l'appui : le choix Tesseract vs Mistral sur les pages de garde (précision, coût, latence) ; la robustesse du garde-fou de validation (combien d'erreurs le non-match empêche de valider à tort) ; et l'architecture générique (détection + classification) comme réponse au constat que chaque projet eDoc configure ses propres champs — démontrée sur le(s) projet(s) pour lesquels des données réelles sont disponibles, conçue pour en accueillir d'autres sans changement de code.
