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

- **Branche** : `main`, dernier commit `bedf97e` (« balayer les coins quand la localisation échoue »).
- **Extraction terminée et validée** : 27/27 documents du corpus (`data/samples/`) traités avec
  succès (0 erreur, 0 « needs tiling », 0 « à vérifier humainement »).
- **29 tests unitaires**, tous verts, aucun n'appelle le réseau (`mvn test`).
- **Aucune régression** en attente : tous les changements décrits ci-dessous sont commités et testés
  sur le corpus complet, pas seulement sur les documents ciblés par chaque correction.
- **Prochaine étape non commencée** : classification (répartir chaque paire `label/value` extraite
  sur le champ demandé par l'appel API du projet eDoc concerné).

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
```

Aucune dépendance ajoutée au-delà du socle initial (`spring-boot-starter-web`, PDFBox,
`spring-boot-starter-test`) — le cache utilise `java.security.MessageDigest` (JDK standard), pas de
bibliothèque externe.

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
