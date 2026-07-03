# Plan de travail — Moteur OCR eDoc
**PFA Oussama — Bouygues Construction IT Maroc**
Version 1.0 — 03/07/2026 · Échéance : 20/07/2026 · Encadrant : M. Boumenzeh

---

## 1. Objectif et livrables

| Livrable | Description | Critère d'acceptation |
|---|---|---|
| Prototype fonctionnel | Pipeline complet : document → JSON au schéma Bordereau → API REST | Un fichier soumis via l'API ressort avec les 8 champs cibles + statuts de confiance |
| Rapport d'architecture justifiée | Document (FR) comparant cascade vs outils seuls, chiffres à l'appui | Chaque décision d'architecture est adossée à une mesure sur le dataset |
| Démo | Scénario reproductible devant l'encadrant | Upload d'un doc de chaque famille → résultat en direct |

Schéma de sortie cible (confirmé par le fichier Bordereau eDoc) :
`PHASE, EMETTEUR, LOT, TYPE, ZONE, NIVEAU, NUMERO, Titre1` (obligatoires) + `Indice` (import indicé) + `Titre2/Titre3` (optionnels). Les champs hors schéma présents sur certains cartouches (PROJET, Commune, Secteur…) sont capturés en métadonnées, pas forcés dans le schéma.

---

## 2. Décisions d'architecture actées

Chaque ligne est une décision fermée, avec sa justification — c'est la matière première du rapport final.

| # | Décision | Justification / preuve |
|---|---|---|
| D1 | Architecture **Option B : cascade avec fail-over** (natif → Tesseract → Mistral vision → Azure DI → revue humaine) | Validée par l'encadrant. Robustesse face à la variabilité du corpus. |
| D2 | Cloud autorisé (Azure, Mistral API) | Confirmé par l'encadrant. |
| D3 | **Page 1 uniquement**, un cartouche par document | Confirmé : les cartouches des autres pages sont des doublons. Borne le coût par document à une page. |
| D4 | Deux familles avec deux stratégies de localisation distinctes | Page de garde : cartouche en bande basse (5/5 confirmés « bas-milieu »). Plan dense : **aucune règle de position fixe possible** — preuves : 7.pdf=bas-gauche, 8.pdf=bas-droite, 11.pdf=bas-droite, 12.pdf=haut-gauche. |
| D5 | Vérification natif/scanné **au niveau de la zone cartouche**, jamais au niveau page | Piège vérifié : un PDF peut être natif globalement avec un cartouche inséré en image (tampon BPE, scan). |
| D6 | Correction d'orientation **systématique et en amont** de la localisation, pour les deux familles | Cas réel observé : plan pivoté à 90°, cartouche « déplacé » de bas-droite vers haut-droite. Chaîne : attribut PDF `/Rotate` → OSD Tesseract → force brute 4 rotations sur zones candidates si OSD peu confiant. |
| D7 | Nom de fichier = signal d'appoint, **jamais vérité terrain** | Preuve : doc Antonypôle, nom de fichier ≠ Code GED imprimé au cartouche. |
| D8 | Tables de référence = **signal de confiance souple**, pas filtre strict | Les tables contiennent elles-mêmes du bruit (anomalie CO_NB : entrée « I → INF » doublonnant « INF → Infrastructure »). Non-match ⇒ baisse de confiance + revue, pas rejet. |
| D9 | Escalade de la cascade **décidée par champ, exécutée par appel groupé par outil** | Si seul NUMERO échoue au niveau Tesseract, seuls les champs non résolus escaladent — mais un seul appel Mistral/Azure sur le crop cartouche couvre tous les champs en attente (les API facturent à la page/l'appel, pas au champ). |
| D10 | Seuils de confiance **propres à chaque outil**, jamais partagés | Les échelles de confiance Tesseract / Azure / LLM ne sont pas comparables. Calibrage sur dataset annoté, stocké en config, pas en dur. |
| D11 | Sortie LLM sur champ codé **jamais auto-validée sans match table** | Risque d'hallucination plausible. Le vrai signal de confiance d'une valeur Mistral = son match (fuzzy) contre la table de référence, pas sa « certitude » auto-déclarée. |
| D12 | Intégration eDoc (Angular/Java) et contrat API final = **hors scope 20/07** | Reporté par l'encadrant (« étape par étape »). L'API expose un JSON stable ; l'adaptation au contrat eDoc sera un mapping ultérieur. |
| D13 | Ordre de cascade configurable, ordre par défaut = D1 | L'évaluation (M8) vérifiera empiriquement si Mistral-avant-Azure est optimal ; l'ordre vit en config, le rapport tranche sur chiffres. |

---

## 3. Architecture du pipeline — spécification par module

Chaque module = un package du repo. Entrées/sorties explicites pour que l'implémentation (Claude Code) soit mécanique.

### M0 — Ingestion & routage de format
- **Entrée** : fichier (PDF / DOCX / XLSX). **Sortie** : objet `Document{path, format, route}`.
- DOCX → extraction texte directe (`python-docx`) → M5. XLSX → `openpyxl` → M5. PDF → M1.
- Rejet propre des formats inconnus (erreur API 422, pas de crash).

### M1 — Isolation page 1 + orientation
- Charger **uniquement la page 1** (PyMuPDF), quel que soit le nombre total de pages.
- Orientation : (1) lire l'attribut `/Rotate` de la page et normaliser ; (2) si scan ou attribut absent : OSD Tesseract sur la page rendue basse résolution ; (3) si confiance OSD < seuil : ne pas forcer — la rotation sera résolue en M2 par force brute sur les zones candidates.
- **Sortie** : image page 1 normalisée + drapeau `orientation_confidence`.

### M2 — Classification de famille + localisation du cartouche
- Heuristique de famille : taille/orientation de page (A4 portrait → probable page de garde ; ≥A2 → probable plan dense). **Signal, pas règle** — la localisation qui suit doit réussir même si l'heuristique se trompe.
- **Page de garde** : traiter la page entière (A4 = peu coûteux) avec ciblage prioritaire de la bande basse.
- **Plan dense** :
  1. Recherche d'ancres dans le **texte natif** (`pdfplumber.extract_words()` avec coordonnées) — indépendante de la position du cartouche sur la page.
  2. Sinon : crop des **4 coins**, passage Tesseract rapide basse résolution sur chaque coin (aux 4 rotations si `orientation_confidence` faible), le coin totalisant le plus d'ancres gagne.
  3. Re-crop haute résolution (rendu ~300 DPI de la zone) du coin gagnant.
- **Règle anti-faux-positif** : une zone n'est retenue que si ≥ 3 libellés-ancres distincts y sont détectés à proximité les uns des autres (un mot isolé du dessin ne suffit jamais).
- **Sortie** : `CartoucheZone{bbox, family, position, rotation_applied}`.

### M3 — Vérification natif/scanné (niveau zone)
- Sur la bbox du cartouche uniquement : présence de texte natif exploitable ?
- Oui → extraction native (niveau 0 de la cascade, gratuite et instantanée).
- Non, ou zone couverte par un objet image → rasterisation de la zone → cascade OCR.
- **Filet de sécurité par champ** : après extraction native, tout champ attendu ressortant vide ou non matché retombe individuellement dans la cascade (cas hybride : cadre natif + valeur tamponnée en image).

### M4 — Cascade d'extraction (cœur du système)
Niveaux, dans l'ordre par défaut (configurable, cf. D13) :

| Niveau | Outil | Coût | Rôle |
|---|---|---|---|
| 0 | Texte natif PDF | 0 | Premier palier gratuit quand disponible |
| 1 | Tesseract (local) | 0 | OCR de base sur crop prétraité (niveaux de gris, seuillage adaptatif, upscale si besoin) |
| 2 | Mistral vision (API) | € | Re-vérification **sur l'image du crop** (jamais correction texte-seule), sortie JSON structurée |
| 3 | Azure Document Intelligence (API) | € | Modèle layout/document généraliste, fort sur tableaux et paires clé-valeur |
| 4 | Drapeau revue humaine | — | Statut `TO_REVIEW` dans la sortie API |

- Escalade par champ, exécution groupée par outil (D9).
- **Cache obligatoire des réponses brutes** de chaque outil par (document, niveau) : les itérations d'évaluation ne re-consomment ni quota ni budget.
- Format de sortie normalisé commun aux 4 niveaux :
```
ExtractionRecord {
  field, value_raw, value_normalized,
  source_tool, tool_confidence,        # échelle propre à l'outil
  table_match_score,                   # fuzzy vs table de référence (null si champ libre)
  final_confidence, status             # AUTO_VALIDATED | TO_REVIEW | MISSING
}
```

### M5 — Structuration
- **Dictionnaire d'ancres/synonymes** (config YAML, deux sections : `confirmés` / `hypothèses à valider`) :
  - NUMERO ← N° DOC, N°DOC, N° GED, N° Chrono, N° document, NUM
  - Indice ← Ind, IND, IND.
  - NIVEAU ← Niveau, Niveaux, NIV · ZONE ← Zone, ZON · PHASE ← Phase, PHA · EMETTEUR ← Emetteur, Émetteur, EMET · TYPE ← Type, TYP · LOT ← Lot
  - Hypothèses à valider avant mapping : Spécialité→LOT ?, Discipline→LOT ?, Localisation→ZONE ?, DOC→TYPE ? (cartouches Abidjan / Grand Paris). Libellé non mappable ⇒ métadonnée + drapeau, jamais de mapping forcé.
- **Titre1** : pas de table de référence (champ libre). Ancres : Désignation, Objet, Titre + heuristique « plus grand bloc texte du cartouche hors tableau codé ». Champ le plus difficile — assumé dans l'évaluation.
- **Matching flou** (RapidFuzz/Levenshtein) aux deux étages : reconnaissance de libellé ET validation de valeur.
- **Normalisation** avant comparaison : casse, espaces, zéros de tête de NUMERO (« 085 » vs « 85 » — convention canonique unique appliquée à la fois aux prédictions et à la vérité terrain ; convention exacte à confirmer, cf. §10).

### M6 — Confiance & décision
- `final_confidence` par champ = combinaison (pondérée, en config) de : confiance outil normalisée + score de match table + accord inter-niveaux si plusieurs ont tourné.
- Statuts : `AUTO_VALIDATED` (≥ seuil haut) / `TO_REVIEW` (zone grise) / `MISSING` (champ absent du document).
- Seuils calibrés en M8, stockés dans `config/thresholds.yaml`.

### M7 — API REST (FastAPI)
- `POST /extractions` (multipart) → `202 { job_id }` — asynchrone dès le départ (volume élevé attendu).
- `GET /extractions/{job_id}` → `{ status, result }` où `result` = schéma Bordereau + par champ : valeur, confiance, statut, outil source + métadonnées document (famille, position cartouche, rotation appliquée, temps, niveaux utilisés).
- Traitement en arrière-plan : `BackgroundTasks` FastAPI suffit pour le prototype. Celery/Redis = chemin d'évolution documenté, **pas implémenté** (sur-ingénierie vs 20/07).
- Secrets (clés Azure/Mistral) : variables d'environnement uniquement, jamais dans le code ni le repo.

### M8 — Harnais d'évaluation
- Script : pipeline sur tout le dataset → comparaison à la vérité terrain (Excel) → tableaux de métriques (cf. §6).
- Produit directement les tableaux du rapport d'architecture. C'est un module de première classe, pas un script jetable.

---

## 4. Structure du repo (référence pour Claude Code)

```
edoc-ocr/
├── src/
│   ├── ingestion/        # M0, M1 : routage format, page 1, orientation
│   ├── localization/     # M2 : famille, ancres, 4 coins
│   ├── extraction/       # M3, M4 : natif, tesseract, mistral, azure, orchestrateur cascade, cache
│   ├── structuration/    # M5 : mapping libellés, validation valeurs, normalisation
│   ├── scoring/          # M6 : confiance finale, statuts
│   ├── api/              # M7 : FastAPI, schémas Pydantic, jobs
│   └── evaluation/       # M8 : harnais, métriques
├── config/
│   ├── anchors.yaml      # dictionnaire libellés (confirmés / hypothèses)
│   ├── thresholds.yaml   # seuils par outil + pondérations confiance
│   ├── cascade.yaml      # ordre des niveaux
│   └── reference_tables/ # Phase, Niveau, Zone, Type, CO_NB, Emetteur*, Lot* (CSV)
├── data/
│   ├── raw/              # documents du dataset
│   ├── annotations.xlsx  # vérité terrain
│   └── cache/            # réponses brutes API par (doc, outil)
└── tests/
```
Conventions : code, commentaires, identifiants en **anglais** ; documentation et rapport en **français**.

---

## 5. Planning jour par jour (03/07 → 20/07)

12 jours ouvrés. Week-ends (4-5, 11-12, 18-19/07) = buffer optionnel, pas planifiés.

| Jour | Date | Contenu | Jalon / critère de sortie |
|---|---|---|---|
| J1 | ven 03/07 | Setup repo + venv + dépendances. **Demander clé Azure DI (tier gratuit F0) et clé Mistral aujourd'hui** (week-end devant). Exporter les tables Emetteur et Lot depuis eDoc. Convertir les 7 tables en CSV config. Relancer l'encadrant pour les documents supplémentaires. | Environnement opérationnel ; appels de test Azure/Mistral passent (ou demande de clés tracée). |
| J2 | lun 06/07 | M0 + M1 : routage format, chargeur page 1, correction d'orientation (attribut + OSD). | Les 8 docs distincts passent M0-M1 sans erreur ; doc pivoté correctement redressé. |
| J3 | mar 07/07 | M2 : heuristique famille + localisation (ancres natives + 4 coins). | Position détectée = position annotée dans l'Excel sur les 8 docs. |
| J4 | mer 08/07 | M3 + niveaux 0-1 : check natif/scanné niveau zone, extracteur natif, connecteur Tesseract (avec prétraitement image), sortie `ExtractionRecord`. | Champs extraits en natif et via Tesseract sur ≥ 1 doc de chaque famille. |
| J5 | jeu 09/07 | Connecteurs Mistral vision + Azure DI, même format de sortie. Cache des réponses brutes. | Les 4 niveaux produisent des `ExtractionRecord` comparables. |
| J6 | ven 10/07 | M5 : dictionnaire d'ancres, fuzzy matching libellés + valeurs, normalisation. | **Jalon semaine 1 : chaîne complète (hors cascade) sur 1 doc de chaque famille → JSON Bordereau.** |
| J7 | lun 13/07 | M4 orchestrateur cascade (escalade par champ, appels groupés, seuils initiaux) + M6 confiance/statuts. | Cascade de bout en bout sur les 8 docs. |
| J8 | mar 14/07 | M8 harnais + **QA vérité terrain** (annotations validées contre les tables : typos, normalisation NUMERO) + 1er run complet → métriques de base par outil et cascade. Calibrage des seuils. Squelette du rapport créé (les tableaux s'y déverseront). | Tableau baseline : précision par champ, par famille, par outil. |
| J9 | mer 15/07 | M7 API FastAPI (asynchrone, schémas). Intégration des docs supplémentaires de l'encadrant s'ils sont arrivés. | `POST` + `GET` fonctionnels en local sur un doc réel. |
| J10 | jeu 16/07 | Journée d'itération : correction des 3 principaux modes d'échec révélés par l'éval (rotations limites, champs hybrides natif/tampon, trous du dictionnaire d'ancres). 2e run d'éval. | Précision en hausse mesurée vs J8 ; échecs restants documentés. |
| J11 | ven 17/07 | **Gel du code.** Run d'évaluation final → tableaux définitifs. Rédaction du rapport (FR). Option si avance : Dockerfile. | Rapport ≥ 80 % rédigé, métriques finales figées. |
| J12 | lun 20/07 | Finalisation rapport + préparation démo (scénario : 1 page de garde + 1 plan dense pivoté, en direct via l'API). Livraison. | Démo répétée une fois de bout en bout avant présentation. |

Chemin critique : **les clés API (J1)**. Sans elles, J5 glisse — mitigation en §7.

---

## 6. Méthodologie d'évaluation

- **Dataset** : 8 documents distincts actuels (doublons exclus : 13-18.pdf ; sans cartouche exclus : 6.docx, 9, 10.pdf) + documents supplémentaires demandés à l'encadrant (cible ≥ 20). Les proportions page de garde / plan dense doivent refléter le corpus réel.
- **QA de la vérité terrain (J8)** : chaque annotation de champ codé est confrontée aux tables de référence ; les écarts (ex. NIVEAU=TTT absent de la table, NUMERO 85 vs 085) sont tranchés avant tout calibrage — on ne calibre pas sur une référence douteuse.
- **Métriques** :
  - Précision **par champ** : correspondance exacte après normalisation pour les champs codés ; similarité normalisée ≥ seuil pour Titre1 (champ libre).
  - Ventilée **par famille** et **par configuration** (chaque outil seul vs cascade) → c'est le tableau qui justifie l'architecture.
  - **Taux d'automatisation** : % de champs `AUTO_VALIDATED` corrects (l'indicateur métier : ce que l'utilisateur eDoc n'a plus à saisir).
  - **Coût et latence** par document (nb d'appels API par niveau).
- **Calibration des seuils** : sur l'ensemble du dataset si < 20 docs, avec mention explicite de la limite (pas de split train/test crédible à cette taille) ; si ≥ 20 docs, réserver ~30 % en test. Cette honnêteté méthodologique figure telle quelle dans le rapport.

---

## 7. Risques et parades

| Risque | Impact | Parade |
|---|---|---|
| Clés Azure/Mistral en retard | J5 glisse | Demande J1 matin. La cascade se dégrade proprement : chemin natif + Tesseract + statuts TO_REVIEW reste démontrable de bout en bout sans aucune clé. |
| Documents supplémentaires arrivent tard | Calibrage moins robuste | Développement complet possible sur les 8 actuels ; relance J1 puis J5 ; limite documentée dans le rapport si dataset final < 20. |
| Hallucination Mistral sur champs codés | Erreurs silencieuses | D11 : jamais d'auto-validation d'une valeur LLM non matchée en table. |
| Quotas du tier gratuit Azure épuisés par les runs d'éval | Blocage J8/J10 | Cache des réponses brutes (M4) : chaque document n'est envoyé qu'une fois par outil, les re-runs lisent le cache. |
| Rotation non détectée (OSD peu fiable sur pages pauvres en texte) | Cartouche raté | Chaîne à 3 étages de D6 ; la force brute 4 rotations sur crops de coins est le filet final. |
| Texte natif corrompu sur certains exports CAO du corpus élargi | Extraction native fausse | Vérifié sain sur les docs actuels ; le filet par champ de M3 renvoie automatiquement vers l'OCR tout champ natif non matché. |
| Dérive de périmètre (eDoc UI, Java/Angular, déploiement) | Deadline explosée | §8 opposable : hors scope acté avec l'encadrant. |

---

## 8. Hors scope explicite (jusqu'au 20/07)

Intégration UI eDoc (Angular) et couche Java · contrat API final eDoc (D12) · déploiement production et industrialisation (Docker = option J11) · fine-tuning ou entraînement de modèles · dédoublonnage automatique du corpus · gestion multi-cartouches par document (contredit par les faits confirmés) · pages autres que la page 1.

---

## 9. Actions immédiates (aujourd'hui, avant le week-end)

1. Demander les clés **Azure Document Intelligence** et **Mistral API** (motif : tests, tier gratuit accepté).
2. Exporter depuis eDoc les tables **Emetteur** et **Lot** (mêmes bouton Exporter que la table Type).
3. Relancer l'encadrant sur les **documents supplémentaires** avec vérité terrain.
4. Initialiser le repo selon §4 (Claude Code).

---

## 10. Questions ouvertes (non bloquantes, à trancher au fil de l'eau)

1. Convention de normalisation NUMERO (zéros de tête : « 085 » vs « 85 » vs « 000439 » côté eDoc) — à confirmer avec l'encadrant ou par le comportement de l'import Bordereau.
2. Modèle Mistral vision exact à utiliser — vérifier la documentation Mistral à jour au moment de l'implémentation (offre susceptible d'avoir évolué).
3. Mappings de libellés en hypothèse (Spécialité/Discipline→LOT, Localisation→ZONE, DOC→TYPE) — valider sur les documents supplémentaires ou avec l'encadrant.
4. Volumétrie réelle en production — impacte le dimensionnement post-PFA, pas le prototype.
