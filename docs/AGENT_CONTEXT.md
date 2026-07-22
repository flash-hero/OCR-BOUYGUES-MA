# Contexte agent — à lire en début de nouvelle session

Ce fichier condense tout l'historique de développement pour qu'une **nouvelle session Claude Code**
(sans l'historique de conversation) puisse reprendre le travail sans qu'on ait à tout réexpliquer.
`CLAUDE.md` (chargé automatiquement) donne les règles courtes à respecter ; ce fichier donne le
**pourquoi** détaillé derrière chaque règle et la chronologie complète des décisions.

Pour la version pédagogique (destinée à un humain, style simple) : voir `understand.md`.
Pour lancer le projet : voir `howtorun.md`.
Pour le brief original + son évolution : voir `instruction.md`.

---

## 1. Résumé en une phrase

Moteur OCR Java/Spring Boot qui lit le cartouche (bloc d'identification) d'un document technique via
Mistral OCR, pour préremplir automatiquement un formulaire de dépôt dans l'application interne eDoc
(Bouygues Construction). PFA (stage de fin d'études) de l'utilisateur, contrainte imposée : Java 17.

## 2. État exact à la fin de cette session

- **Branche** : `main`. Sessions précédentes : P4 validation (`5b3c969`), fix `.env` (`440f3a7`).
  Cette session (2026-07-21) : **fiabilité + latence de l'extraction** (voir §2bis).
- **Extraction (P2), classification (P3), validation (P4)** : terminées ; P2 retravaillée cette
  session (voir §2bis), P3/P4 inchangées.
- **102 tests unitaires**, tous verts, aucun n'appelle le réseau (`mvn test`).
- **Prochaine étape non commencée** : P5 — API REST (exposer le pipeline complet
  upload → extraction → classification → validation → réponse JSON par champ).

## 2bis. Session 2026-07-21 — consensus, score de coin, latence (l'extraction en une vague)

Constats mesurés (corpus complet + docs lourds 11/12/16/20, caches vides) :
- `/v1/ocr` n'a **ni `temperature` ni `seed`** (vérifié sur la référence API) : deux requêtes
  identiques peuvent différer (12.pdf a basculé found true→false ; 15.pdf 77→19 paires).
- La latence est dominée par le **temps d'OCR par appel**, qui croît avec le **volume de texte**
  de l'image (pas ses pixels — Mistral redimensionne en interne). Un coin dense d'A0 ≈ 20-28 s/appel.
- La passe 1 (localisation pleine page) OCRise TOUT le plan : c'était l'appel le plus lent (~40 %
  du temps), et la sélection par score choisit le même coin sans elle.

Ce qui a été construit (tout dans `ocr/`) :
- **`OcrConsensus`** : N échantillons du même appel en parallèle, vote majoritaire sur
  `cartoucheFound` + médiane basse du nombre de paires ; jamais de fusion/filtrage de paires.
  `mistral.ocr.samples` = **5** (mesuré : stabilise 12.pdf pour ~+5 s de queue).
- **`CartoucheScore`** : départage entre coins plausibles — favorise les codes courts
  d'identification, pénalise adresses/téléphones/rôles d'intervenants (corrige 20.pdf qui
  renvoyait le panneau des intervenants).
- **Une seule vague réseau** par grand plan : rendus des 4 coins en parallèle (CPU), puis analyse
  des 4 coins en même temps (consensus par coin, figé dans le cache). Passe 1 **désactivée par
  défaut** (`mistral.ocr.locate-enabled=false`, réactivable sans code — elle ne fait que départager).
- **Tolérance aux échantillons défaillants** : une annotation JSON tronquée = « pas d'extraction »
  pour le vote, jamais un échec du document (crash 13.pdf corrigé). Retry back-off sur 429/503
  (`mistral.ocr.max-retries=2`).
- **Repli amélioré** : aucun coin validé → coin le plus riche renvoyé NON validé (`[A VERIFIER]`) ;
  `NEEDS_TILING` seulement si TOUS les coins sont vides.
- Leviers **rejetés sur mesure** : résolution 2400 px (aucun gain de temps, 16.pdf 13→29 paires) ;
  fraction 0.28 (cartouche de 12.pdf coupé → tuiles). `crop-long-px=3400`, `corner-fraction=0.40`.

Profil de latence livré : pages standard ~5-15 s ; grands plans ~13-25 s ; les 3 monstres A0
(11/12/16) ~26-28 s — plancher d'un appel unique à pleine exactitude, descendre en dessous dégrade
les lectures (mesuré). Objectif <20 s tenu partout sauf ces trois-là, choix assumé « exactitude
d'abord ».

## 2ter. Méthodologie appliquée cette session — mesurer, jamais supposer

Le travail ci-dessus (§2bis) s'est fait en **deux temps**, avec un changement net de méthode entre les
deux — à connaître pour ne pas répéter la première approche dans une future session.

**Premier temps (conception raisonnée, non mesurée).** Consensus + score de coin + parallélisation ont
d'abord été construits par analyse (le raisonnement tenait, les tests unitaires passaient), puis un
découpage en deux vagues réseau (« exploration légère » puis « confirmation ») a été ajouté en
supposant qu'échantillonner plus légèrement les 4 coins réduirait la rafale d'appels simultanés et
donc la latence. **Ce découpage n'a jamais été mesuré isolément avant d'être intégré** — seulement
validé par les tests unitaires (qui ne mesurent pas de temps réel). Un run complet sur le corpus a
ensuite révélé, après coup, qu'il **doublait** la latence isolée (2 vagues séquentielles au lieu
d'une) et **dégradait** la sélection de coin (échantillonnage à 1 sur l'exploration = vote plus
fragile, régressions sur `12.pdf`/`19.pdf`). Coûteux à découvrir a posteriori.

**Second temps (mandat explicite de l'utilisateur, effort maximal) : mesurer avant d'adopter.** Suite
à l'instruction « tu décides, ne me demande rien, dépense les appels API nécessaires, livre » :
1. Le découpage en deux vagues a été **annulé** (retour à une vague unique, coins + localisation
   lancés ensemble), une fois son coût réel mesuré.
2. Les leviers de latence (résolution du découpage, fraction de coin, activation de la passe 1) ont
   été rendus **configurables** (`mistral.ocr.*`) au lieu d'être des constantes — condition préalable
   pour pouvoir les tester sans recompiler à chaque essai.
3. **Matrice de mesure** : un panel fixe de 4 documents difficiles (`11.pdf`, `12.pdf`, `16.pdf`,
   `20.pdf`), cache vidé avant chaque variante pour forcer de vrais appels, **une seule variable
   changée à la fois** — R1 (référence), R2 (passe 1 désactivée), R3 (+ résolution 2400 px), R4
   (+ fraction de coin 0.28), R5 (+ 5 échantillons de consensus).
4. Pour chaque variante, comparaison **du temps ET du contenu réel des paires extraites** (pas
   seulement leur nombre) contre R1 — c'est cette comparaison de contenu qui a révélé que R3
   dégradait la lecture de `16.pdf` (13→29 paires confuses) et que R4 coupait le cartouche de
   `12.pdf`, alors que le temps mesuré seul n'aurait rien montré d'anormal.
5. **Isoler un document seul** (`11.pdf` sans aucun autre traitement en parallèle) pour trancher une
   question précise : le temps mesuré sur le corpus complet est-il dû au *nombre* d'appels qui se
   bousculent (throttling de lot) ou au *coût par appel* lui-même ? Résultat : même isolé, `11.pdf`
   restait lent (~77 s pour seulement 10 appels) — ce n'était donc pas du throttling, mais bien le
   temps d'OCR par appel sur une image dense. Sans ce test isolé, la conclusion serait restée fausse.
6. Décision prise **uniquement sur les chiffres** : R2 (passe 1 off) adopté (même coin choisi, ~40 %
   plus rapide) ; R3 et R4 **rejetés** et documentés comme tels (pas juste abandonnés silencieusement,
   voir §2bis) ; R5 (samples=5) adopté (corrige la loterie de consensus sur `12.pdf` pour un coût de
   queue négligeable, les échantillons étant parallèles).
7. **Run complet du corpus (27 documents, cache vidé) comme dernière porte avant le commit** — pas un
   run exploratoire de plus, la validation finale de tout ce qui a été mesuré et décidé ci-dessus.

**À retenir pour la suite** (voir aussi la nouvelle règle dans `CLAUDE.md`) : toute future
optimisation de latence ou de résolution/zone doit suivre ce protocole — configurable, matrice à une
variable, comparaison de contenu (pas seulement de temps), test isolé avant d'accuser un lot, et
leviers rejetés documentés au même titre que les leviers adoptés.

## 3. Principe architectural (invariant, ne pas dévier)

Deux étapes strictement séparées :
1. **Extraction générique** (FAIT) — localiser le cartouche, extraire TOUTES les paires
   libellé/valeur qu'il contient, sans présumer quels champs existent. Schéma de sortie toujours
   `{cartoucheFound: boolean, fields: [{label, value}]}` — jamais de propriété métier nommée en dur
   (jamais `phase`, `emetteur`... comme clé JSON).
2. **Classification** (À FAIRE) — ranger chaque paire brute sur le champ cible demandé par l'appel
   API. La liste des champs requis et le code du projet eDoc viennent de l'appel API, jamais déduits
   du document.

Règles non négociables (détaillées dans `CLAUDE.md`, rappelées ici avec leur justification) :

| Règle | Pourquoi |
|---|---|
| Aucune position de cartouche fixe dans le code | Observé empiriquement faux : plusieurs coins différents selon les documents dès les tout premiers tests (3 coins sur 4 plans) |
| `byte[]` dans tout le cœur métier, jamais `Path`/`File` | Le moteur en production recevra les documents via upload API, jamais depuis un dossier disque — voir §7 |
| Modèle Mistral épinglé `mistral-ocr-4-0`, jamais `-latest` | Reproductibilité des métriques d'un run à l'autre |
| Correspondance floue (fuzzy matching) partout, jamais `==` strict | Le corpus contient des variantes de libellés (« NUM » pour NUMERO, libellés anglais ponctuels comme « LEVEL » pour NIVEAU) — s'applique à la classification (label lu vs synonymes) et à la validation (valeur lue vs table de référence), deux usages distincts à ne pas confondre |
| Règle D11 : aucune table de référence n'est un vocabulaire fermé | Une valeur absente de la table → toujours `TO_REVIEW`, jamais un rejet silencieux |
| Un contrôle qualité positif ne garantit pas un résultat correct | Historique de bugs où le contrôle a été satisfait à tort (voir §5, légende de 10.pdf) — toujours vérifier le contenu réel, pas seulement le passage du contrôle |

## 4. Architecture technique — carte des fichiers

```
src/main/java/com/bycn/edoc/
├── EdocOcrApplication.java                 point d'entrée Spring Boot
├── config/DotenvEnvironmentPostProcessor   charge .env (priorité < vraies env vars)
└── ocr/
    ├── MistralOcrProperties                config (clé, endpoint, modèle épinglé, cache)
    ├── PdfSupport                          byte[] → mesures page, rendu région PDF en PNG (PDFBox)
    ├── CropRegion                          zone nommée (bottom-right...) → rectangle fractionnaire
    ├── CartoucheLocationSchema             schéma + prompt PASSE 1 (zone seulement, pas de contenu)
    ├── CartoucheAnnotationSchema           schéma OUVERT + prompt extraction ({label,value}[])
    ├── MistralOcrClient                    appel HTTP réel : locateImage() / analyzeImage()
    ├── OcrResponseCache                    cache disque adressé par contenu (SHA-256 du body)
    ├── CartouchePlausibility               contrôle qualité post-extraction
    ├── TwoPassCartoucheExtractor           CHEF D'ORCHESTRE — voir §6
    ├── OcrConfig                           câblage des beans Spring
    └── records: CartoucheField, CartoucheExtraction, CartoucheLocation, CartoucheAnalysis, OcrResult
└── smoke/SmokeTestRunner                   outil CLI de test (profil "smoke"), PAS le produit final
└── classification/
    ├── SchemaFieldsRegistry              charge schema_fields.yaml (SnakeYAML)
    ├── LabelNormalizer                    replie accents/casse/espaces avant comparaison
    ├── FieldClassifier                    assignation gloutonne globale (fuzzy matching)
    ├── ClassificationConfig / properties  seuil + flag hypothesis-synonyms (application.yml)
    └── records: ClassifiedField, ClassificationResult, FieldStatus
└── validation/
    ├── ReferenceTableRegistry            charge les CSV par projet/champ (Commons CSV), cache, liste vide si absent
    ├── FieldValidator                     matching flou valeur vs code, applique le strip des zéros de tête (numérique pur uniquement)
    ├── ValidationConfig / properties      seuil de validation (application.yml, distinct du seuil de classification)
    └── records: ValidatedField, ValidationResult
```

Dépendance ajoutée en P3 : `me.xdrop:fuzzywuzzy:1.4.0` (FuzzySearch.ratio) — absente
jusqu'ici, différée volontairement le temps qu'il n'y ait pas de classification (voir
instruction.md, stack imposée). Le cache OCR (P2) continue d'utiliser
`java.security.MessageDigest` (JDK standard), sans lien avec cette nouvelle dépendance.

Dépendance ajoutée en P4 : `org.apache.commons:commons-csv:1.12.0` — listée dans
`plan_travail_ocr_edoc.md` §2 depuis le début, mais jamais réellement présente dans `pom.xml`
avant ce commit.

## 5. Chronologie complète des décisions (avec ce qui a été essayé et rejeté)

Chaque ligne = un problème réel rencontré sur le corpus, la cause, et la correction retenue.

1. **Cartouche illisible sur grands plans (A0+)** → Mistral redimensionne toute image envoyée à une
   taille fixe interne ; un petit cartouche dans un coin d'un plan de 2 m devient illisible après ce
   redimensionnement. **Fix** : lecture en deux passes — passe 1 localise grossièrement (zone parmi
   9 + unknown), passe 2 redécoupe cette zone à haute résolution et extrait.
2. **Passe 1 pointait vers le titre du plan** au lieu du cartouche → prompt pas assez précis sur ce
   qui distingue un cartouche (bloc dense de codes courts) d'un titre (phrase descriptive). **Fix** :
   prompt réécrit pour insister sur cette distinction.
3. **Légende de symboles acceptée à tort comme cartouche** (`10.pdf`) → le contrôle qualité comptait
   les libellés courts sans vérifier que la valeur associée était réellement remplie ; une légende a
   des libellés (REF/SYMBOLE/DÉNOMINATION) mais des valeurs vides. **Fix** :
   `CartouchePlausibility.isShortCodeField` exige désormais une valeur non vide.
4. **Tableau à deux lignes mal apparié** (en-têtes recopiés avec valeurs vides, valeurs orphelines
   séparées) → **Fix** : prompt d'extraction précisé pour associer chaque en-tête à la valeur
   directement en dessous.
5. **Documents > 30 pages rejetés par Mistral** (HTTP 400 `document_parser_too_many_pages`) → la
   limite s'applique au document entier reçu, pas au sous-ensemble de pages annoté (`pages=[0]`
   seul ne suffisait pas). **Fix retenu** : ne plus jamais envoyer le PDF brut (`document_url`) — on
   rend nous-mêmes une image PNG de la page 1 (même mécanisme que la passe 2 crop) et on envoie
   `image_url` pour TOUS les documents, un seul chemin de code. Effet de bord positif : récupère
   aussi 2 documents qui échouaient en localisation avec l'ancien envoi PDF brut.
6. **Cœur du moteur couplé à `Path`/`File`** (refactor architectural, avant le point 5) → identifié
   avant d'implémenter le fix #5 (vérification explicite demandée) : `extract(Path)`,
   `MistralOcrClient.analyze(Path)`, `PdfSupport.*(Path)` lisaient le disque directement, à plusieurs
   niveaux. **Fix** : tout le cœur retravaillé pour prendre des `byte[]` ; la seule porte disque
   restante est `PdfSupport.read(Path)`, utilisée uniquement par `SmokeTestRunner`.
7. **Retester coûtait cher** (chaque run rappelle Mistral pour tout le corpus) → **Fix** :
   `OcrResponseCache`, clé = SHA-256 du corps de requête exact (contient déjà modèle + prompt +
   schéma + octets image, donc une requête identique = même clé). Best-effort (erreur d'E/S = simple
   cache miss, ne fait jamais échouer l'OCR). Réponses en erreur jamais mises en cache.
8. **Qualité douteuse sur 2 documents après le passage à l'image (15.pdf, 3.pdf), investigués avant
   de merger** :
   - `15.pdf` (24→77 paires) : **investigué et confirmé bénin** — les 77 paires sont toutes du
     contenu réel de cartouche (identité + intervenants + tableau de révisions), pas de légende
     mélangée. Aucun plafond de paires ajouté (aurait tronqué des cartouches légitimement riches).
   - `3.pdf` (11→5 paires) : **investigué et confirmé bénin** — c'est un PDF numérique (pas un scan,
     couche texte détectée), notre rendu (290 DPI) dépasse la résolution native de ses images
     embarquées. Le cartouche est illisible dans les deux cas (les « 11 » étaient surtout du texte
     dupliqué sans sens) ; aucune perte de contenu réel.
9. **`26.pdf` : un bloc « intervenants » (annuaire d'entreprises) perdu** par rapport à un ancien run
   en `document_url`. Deux pistes essayées et **rejetées après mesure** :
   - Augmenter la résolution du rendu pleine page (400→500 DPI) : **inefficace**, le bloc ne revient
     pas (Mistral redimensionne `image_url` en interne, plus de pixels envoyés n'aide pas).
   - Router les documents standards sûrs vers `document_url` (PDF natif) : **récupère parfois** le
     bloc, mais mesure sur 3 appels identiques cache désactivé = **61 / 9 / 9 paires** — non
     déterministe, pire que l'image (34, stable) dans 2 cas sur 3. **Abandonné.**
   - **Conclusion retenue** : le bloc perdu est un **annuaire d'entreprises** qui ne correspond à
     **aucun champ du schéma cible** (PHASE/EMETTEUR/LOT/TYPE/ZONE/NIVEAU/NUMERO/INDICE...) —
     contenu décoratif hors périmètre, pas une régression à corriger. Le code identifiant + le
     tableau de révisions (contenu réellement utile) sont, eux, préservés par le chemin image.
10. **Dernier document du corpus bloqué** (`21.pdf`, A0 très dense) : la passe 1 échouait souvent à
    localiser (« unknown »), mais quand elle réussissait par hasard, l'extraction sur le coin
    fonctionnait bien. Le problème n'était donc pas un cartouche illisible, mais une localisation
    pleine page trop difficile pour ce document précis. **Fix** : quand la passe 1 répond
    « unknown », le moteur ne renonce plus — il balaie les coins prioritaires avec le même mécanisme
    passe 2 + contrôle qualité (déjà utilisé pour le repli normal), sans logique de fusion. Validé
    5/5 runs cache désactivé → toujours `TWO_PASS_CROP`, jamais `NEEDS_TILING`.
11. **Piste testée : indice de champs attendus dans le prompt** (PHASE, ÉMETTEUR, LOT... cités comme
    repère, schéma de sortie inchangé). Testée d'abord sur la passe 1 (localisation) : effet neutre
    sur `21.pdf` (la passe 1 répond toujours « unknown », c'est le balayage des coins qui règle le
    cas). Testée ensuite sur la passe 2 (extraction), avec vérification anti-invention stricte sur
    `6.pdf`/`16.pdf`/`26.pdf` : **aucune invention** de champ absent confirmée, **mais** suppression
    reproductible de champs réels absents de la liste d'indices (`6.pdf` perd `PROJET` et `AUTEUR`,
    tous deux réels, dans 5/5 runs). **Abandonné** : contraire au principe d'extraction ouverte.
    Aucune autre piste d'indice de champs à explorer — décision actée avec l'utilisateur.
12. **Fuzzy matching cassé par la casse et les accents** (P3, avant tout commit) → mesure
    empirique de `FuzzySearch.ratio` avant d'écrire le classifieur (pas d'hypothèse) :
    "NUM" vs "Num" = 33, "LEVEL" vs "Level" = 20, "EMETTEUR" vs "Émetteur" = 0 — largement
    sous le seuil de 80. Or ce sont exactement les deux variantes que CLAUDE.md documente
    comme devant fonctionner (NUM→NUMERO, LEVEL→NIVEAU, corpus réel). **Fix** :
    `LabelNormalizer` (accents repliés, minuscules, espaces compactés, deux-points finaux
    retirés), appliqué symétriquement aux deux côtés de la comparaison — n'affecte donc pas
    le classement relatif des scores, seulement leur valeur absolue. `rawLabel` et
    `matchedSynonym` restent stockés non normalisés dans `ClassifiedField` (traçabilité).
    Après normalisation, les trois cas ci-dessus passent à 100.
    **Mise à jour P4** : la visibilité de `LabelNormalizer` est passée de package-private à
    public, pour être réutilisée par `validation/` sans dupliquer la logique de normalisation
    (une seule source de vérité — la validation compare des codes courts avec la même
    sensibilité à la casse et aux accents, donc la même parade s'applique). Comportement
    inchangé, aucune ligne de logique touchée : les 56 tests P2/P3 restent verts après ce
    changement.

13. **Zéros de tête ambigus entre deux codes Lot différents** (P4, avant tout commit) →
    mesure empirique avant d'écrire la logique de comparaison (comme en P3, pas
    d'hypothèse) : `ratio("003", "03") = 80` (sous le seuil de 85, comme anticipé), mais
    aussi `ratio("003", "00") = 80` — un score strictement identique entre le bon code
    (03, Terrassements) et un mauvais (00, Généralités). Un simple ajustement de seuil
    n'aurait pas résolu cette égalité — elle aurait pu se trancher arbitrairement par
    l'ordre des lignes du CSV, validant silencieusement le mauvais lot. **Fix** : strip
    des zéros de tête avant comparaison, appliqué uniquement aux valeurs/codes purement
    numériques (`003`→`3`, `03`→`3`, `00`→`0` : le bon code obtient 100, le mauvais 0).
    Codes alphanumériques (`N-1`, `A-B`, `O11`) non affectés. Vérifié ensuite qu'aucun
    autre couple de codes, sur les 5 tables réelles, n'atteint le seuil de 85 entre eux —
    le seuil reste donc sûr tel quel, pas besoin d'une logique de désambiguïsation
    supplémentaire.

## 6. `TwoPassCartoucheExtractor.extract(byte[])` — logique actuelle exacte

```
mesurer grand côté page 1 (mm) via PdfSupport.pageLongSideMm
si grand côté ≤ 430 mm (LARGE_PAGE_THRESHOLD_MM) :
    rendre page 1 entière en PNG → analyzeImage() → SINGLE_PAGE
sinon (grand plan) :
    si forcedCorner (diagnostic edoc.force-corner) : un seul essai sur ce coin, pas de repli
    sinon :
        passe 1 : locateImage(rendu pleine page) → CartoucheLocation{cartoucheFound, corner}
        candidats = [corner de la passe 1] + CORNER_PRIORITY si localisé
                  = CORNER_PRIORITY (bottom-right, bottom-left, top-right, top-left) si "unknown"
        pour chaque candidat (dans l'ordre) :
            passe 2 : crop 40% du coin, haute résolution, analyzeImage()
            si CartouchePlausibility.looksLikeCartouche() → retourner TWO_PASS_CROP (succès)
            sinon garder le meilleur essai (le plus de champs) en réserve
        si aucun candidat n'a passé le contrôle :
            si la passe 1 avait localisé : retourner TWO_PASS_CROP avec qualityPassed=false (meilleur essai, à vérifier)
            si la passe 1 avait dit "unknown" ET tous les candidats du balayage ont échoué : retourner NEEDS_TILING
```

`CORNER_PRIORITY` = `[bottom-right, bottom-left, top-right, top-left]` (bas-droite d'abord, position
la plus fréquente observée). Bords/bandes (top-center, left, right, center...) ne sont **pas** dans
la liste de repli — seulement les 4 coins purs.

## 7. Note de portée — pourquoi `byte[]` partout (contexte produit final)

Le corpus de 27 PDF dans `data/samples/` sert **uniquement au développement/test**. Le moteur final
recevra chaque document via un appel API (upload à chaque dépôt utilisateur dans eDoc) et renverra le
résultat via une réponse API — jamais depuis un fichier sur disque serveur. C'est la raison directe
du choix `byte[]` dans tout le cœur (voir §3, §4) : la logique d'extraction ne doit jamais dépendre
de la provenance des octets, pour rester valable sur n'importe quel document futur, pas seulement sur
le corpus de test actuel. **Aucune API REST n'existe encore** — c'est la 3e étape des travaux restants
(voir §2 et `instruction.md`), après classification et validation.

## 8. Limite connue (à ne pas re-découvrir dans une future session)

L'annotation Mistral (le modèle qui lit et retourne le JSON) est **non-déterministe** sur au moins un
document dense du corpus : trois appels strictement identiques (mêmes octets, cache désactivé) sur
`26.pdf` ont donné 61, 9 puis 9 paires. Ce n'est pas un bug du code — propriété du service. Pertinent
pour le futur harnais de mesure de précision (comparaison à la vérité terrain `annotations.xlsx`, en
cours de préparation par l'utilisateur, pas encore disponible) : un seul run par document pourrait ne
pas être représentatif sur les cas difficiles ; envisager plusieurs runs ou cache désactivé pour cette
mesure spécifique.

## 9. Workflow git à respecter (déjà en place, à poursuivre)

- Commit à chaque changement logique, message en français, type conventionnel (`feat:`, `fix:`,
  `refactor:`...), pas de trailer `Co-Authored-By` (désactivé par préférence utilisateur).
- Identité commit : `user.name=flash-hero`,
  `user.email=195345363+flash-hero@users.noreply.github.com` — **jamais** l'adresse gmail réelle
  (GitHub bloque sinon la publication, incident déjà rencontré et résolu).
- Avant chaque commit : scanner le contenu indexé pour des secrets
  (`git grep --cached -E "JQQJ99|6rqYdp394"` — motifs de la clé Azure réelle), vérifier
  `git diff --cached --name-only`.
- **Ne jamais commiter** : `.env`, `data/samples/*.pdf` (plans internes potentiellement
  confidentiels), `target/`, `graphify-out/`, `.claude/`, `CLAUDE.md`, `.ocr-cache/`.
- Repo : `https://github.com/flash-hero/PFA-BOUYGUES-`. Après un changement qui touche ce qui est
  physiquement envoyé à Mistral (prompt, résolution, routage) : **toujours retester sur les 27
  documents complets**, jamais seulement sur les documents visés par la correction — le cache se
  recalcule alors automatiquement pour les entrées concernées (attendu, pas un bug).

## 10. Pour aller plus loin (mémoire long terme, hors de ce dépôt)

Des notes plus détaillées existent dans la mémoire persistante de l'assistant (hors de ce dépôt) :
historique complet des expériences de localisation, du GitHub setup, du corpus 27 documents, et de la
non-déterminisme Mistral. Ce fichier `AGENT_CONTEXT.md` en est le résumé actionnable ; en cas de doute
sur une décision passée, se fier d'abord au **code et aux tests actuels** (source de vérité), ce
fichier étant une photographie au moment de sa rédaction.

## 11. Prochaine étape suggérée (non commencée)

Démarrer la **classification** : étant donné une `CartoucheExtraction` (liste de `{label, value}`) et
une liste de champs cibles fournie par l'appel (ex. `["PHASE", "EMETTEUR", "LOT", ...]` + leurs
synonymes de configuration), associer chaque champ cible à la meilleure paire lue via correspondance
floue sur le libellé — jamais d'égalité stricte (voir §3). Prévoir dès le départ le statut à 3 valeurs
(`AUTO_VALIDATED` / `TO_REVIEW` / `MISSING`, voir README section « Trois statuts ») même si la
validation contre les tables de référence est une étape ultérieure distincte. Consulter
`instruction.md` (règles de conception) et `README.md` (vision produit, champs cibles, statuts) avant
de coder.
