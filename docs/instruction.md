

## Contexte du projet

Je travaille sur un PFA (stage de fin d'études, 2ème année, Bouygues Construction IT Maroc) : un moteur OCR pour l'application interne **eDoc** (gestion documentaire). Quand un utilisateur dépose un plan technique ou un document dans eDoc, le système doit lire son **cartouche** (bloc d'identification : numéro de document, auteur, échelle, phase, etc.) et pré-remplir automatiquement les champs du formulaire de dépôt.

**Contrainte imposée par mon encadrant : Java, JDK 17.**

## Principe architectural (à respecter strictement)

Chaque projet eDoc configure sa propre liste de champs à extraire (ex. un projet demande Phase/Emetteur/Lot/Niveau/Type, un autre demande en plus Bâtiment). Il n'existe donc **aucun schéma fixe universel**. L'architecture sépare deux étapes :

1. **Détection + extraction générique** : localiser le cartouche sur le document et en extraire **tous** les libellés et valeurs qu'il contient, tels qu'imprimés — sans présumer à l'avance quels champs existent.
2. **Classification** : ranger chaque paire (libellé, valeur) brute sur le bon champ cible. La liste des champs requis pour un document donné, ainsi que le code du projet eDoc concerné, sont fournis par l'appel API — ce n'est jamais déduit du document lui-même.

Un champ classé est ensuite **validé** contre la table de référence de son projet.

**Règle de conception non négociable (D11)** : aucune table de référence n'est un vocabulaire fermé. Une valeur absente de la table ne doit **jamais** être rejetée automatiquement — elle déclenche toujours un statut `TO_REVIEW` pour vérification humaine, jamais un rejet silencieux. N'importe quel champ (Phase, Emetteur, Lot...) peut légitimement recevoir une valeur inédite.

**Règle de conception non négociable (matching)** : la comparaison entre le nom de champ demandé par l'API (ex. `NUMERO`) et le libellé réellement imprimé sur le document (ex. `NUM`, ou `LEVEL` pour `NIVEAU` — le corpus contient des libellés anglais ponctuels) doit **toujours** passer par une correspondance floue (fuzzy matching), **jamais** par une égalité stricte de chaînes. Une comparaison stricte ferait passer un champ réellement présent en faux `MISSING`.

Deux usages de la correspondance floue existent et ne doivent pas être confondus dans le code :
- **Classification** : compare un *libellé lu* à une liste de synonymes de champ
- **Validation** : compare une *valeur lue* à une table de référence de valeurs officielles

Deux moteurs de lecture seront comparés au final, mais séquentiellement, pas en parallèle (détail phases ci-dessous) : **Mistral OCR** d'abord pour tout le pipeline, puis **Tesseract** ajouté ensuite comme deuxième voie pour les pages de garde (documents simples, A4).

---

## Objectif de la toute première session (ÉA1 — accompli, historique)

Le projet avance en 3 phases : **Mistral seul de bout en bout d'abord**, Tesseract ensuite comme deuxième voie, la comparaison des deux en dernier.

La toute première session (ÉA1) devait uniquement :

1. Scaffolder un projet Maven / Spring Boot 3.x, JDK 17.
2. Écrire un **script de vérification d'environnement** (`check_setup`) qui contrôle : JDK 17 actif, dépendances Maven résolues, clé API Mistral lisible, documents d'exemple présents. Doit échouer proprement et lisiblement si quelque chose manque, pas planter avec une stack trace.
3. Écrire **le test décisif** : envoyer 2-3 documents représentatifs à Mistral OCR avec un **schéma d'annotation ouvert** (pas de champs nommés à l'avance) et afficher le JSON brut retourné, pour vérifier par la lecture humaine si le cartouche est correctement détecté et ses libellés/valeurs correctement extraits.

Cette portée initiale est **accomplie et largement dépassée** — voir la section suivante pour l'état réel du projet aujourd'hui. Cette section est conservée telle quelle comme trace historique du point de départ.

---

## État d'avancement réel (mis à jour après l'extraction complète)

**Réponse à la question binaire posée par ÉA1 : oui.** Mistral détecte et extrait correctement un
cartouche générique — y compris sur des grands plans (A0+) où le cartouche est minuscule après
redimensionnement, et sur des documents de plus de 30 pages (limite technique de l'API contournée).

### Ce qui est fait — Extraction (première moitié de la Phase 1 « Mistral seul »)

Le moteur d'extraction est **terminé et validé** sur le corpus de test complet (27 documents) :
**27/27 traités, 0 erreur, 0 document nécessitant un découpage en tuiles, 0 à vérifier humainement.**

Décisions d'architecture prises pendant cette phase, et pourquoi :

- **Lecture en deux passes pour les grands formats** (seuil : grand côté de page > 430 mm). Passe 1 :
  localisation grossière de la zone du cartouche (grille de coins/bords) sur un rendu image de la
  page entière. Passe 2 : découpage généreux (40 % de la page) autour de la zone retenue, redessiné
  en haute résolution, puis extraction ouverte sur cette seule image. Raison : Mistral redimensionne
  toute image à une taille fixe interne ; sur un plan de 2 m, le cartouche devient illisible après ce
  redimensionnement si on envoie la page entière.
- **Aucune position de cartouche n'est fixée dans le code.** Confirmé empiriquement faux ("toujours en
  bas à droite") sur le corpus — plusieurs coins observés selon les documents. La localisation vient
  toujours d'un appel à Mistral (Passe 1), jamais d'une hypothèse géométrique codée en dur.
- **Contrôle qualité après la Passe 2**, avec repli ordonné sur les autres coins si le résultat ne
  ressemble pas à un vrai cartouche (au moins 3 paires, dont au moins 3 avec une valeur réellement
  remplie — un contrôle plus faible avait laissé passer une légende de symboles à tort sur un
  document du corpus). Le repli s'applique aussi quand la Passe 1 répond « je ne sais pas » : au lieu
  d'abandonner immédiatement, le moteur essaie quand même les coins prioritaires un par un — cette
  correction a permis de récupérer le dernier document du corpus qui restait bloqué.
- **Tous les documents sont envoyés à Mistral comme une image PNG de la page 1** (rendue par nos
  soins avec PDFBox), **jamais comme le PDF brut**. Deux raisons : ça contourne la limite dure de
  30 pages de l'API Mistral (certains documents du corpus en ont jusqu'à 153, avec le cartouche
  toujours sur la page 1), et ça donne un contrôle total sur la résolution envoyée, plutôt que de
  laisser Mistral redimensionner un PDF multi-pages à sa manière opaque. Une tentative de router les
  documents standards sûrs vers l'envoi du PDF natif (`document_url`) a été testée pour tenter de
  récupérer un bloc perdu sur un document précis ; elle a été abandonnée après mesure : l'annotation
  de Mistral s'est révélée **non-déterministe** sur ce document (61 puis 9 puis 9 paires sur trois
  appels strictement identiques), rendant ce chemin peu fiable. Le moteur garde donc un seul chemin de
  code (image) pour tous les documents.
- **Cache de réponses adressé par contenu** (`.ocr-cache/`, clé = empreinte SHA-256 du corps de
  requête exact envoyé à Mistral). Relancer le test sur un document déjà traité à l'identique ne
  rappelle pas l'API. La clé change automatiquement si le prompt ou la résolution d'image changent —
  c'est le comportement voulu, pas un bug.
- **Cœur métier entièrement orienté `byte[]`, jamais `Path`/`File`.** Le chef d'orchestre
  (`TwoPassCartoucheExtractor`) et le client Mistral (`MistralOcrClient`) ne connaissent que des
  tableaux d'octets, jamais un chemin de fichier sur disque. La seule porte disque autorisée est la
  lecture du corpus de test dans `SmokeTestRunner`. **Raison directement liée à l'usage final** : le
  moteur en production recevra chaque document via un appel API (upload par un utilisateur qui dépose
  un document dans eDoc) et renverra le résultat via une réponse API — jamais depuis un dossier sur
  le disque. Le corpus de 27 PDF de test (`data/samples/`) ne sert **qu'au développement et à la
  validation** ; il ne doit jamais influencer une solution qui ne marcherait que pour ces documents
  précis. Grâce à cette séparation stricte, brancher une vraie API REST plus tard consistera à
  ajouter une fine couche de lecture d'upload devant le même cœur, sans toucher à la logique
  d'extraction elle-même.
- **Une piste testée puis rejetée par mesure** : donner à Mistral, en indice dans le prompt, une
  liste de champs typiquement attendus (sans changer le schéma de sortie, toujours `{label, value}`
  générique). Résultat mesuré sur plusieurs runs, cache désactivé : aucune invention de champ
  inexistant, mais suppression reproductible de champs réels absents de la liste d'indices (contraire
  au principe « tout recopier sans présumer »). Abandonné, revenu à la version sans indice de champs.

### Limite connue à garder en tête pour la suite

L'annotation Mistral (le modèle qui lit et retourne le JSON) s'est révélée **non-déterministe** sur
au moins un document dense du corpus (des runs identiques ont donné des résultats sensiblement
différents). Ce n'est pas un bug du code : c'est une propriété du service. À garder en tête pour le
futur harnais de mesure de précision (voir Étape É5 plus bas) : un seul run par document pourrait ne
pas être représentatif sur les cas difficiles.

### Ce qui reste à faire (par ordre logique)

1. ~~**Classification** — ranger chaque paire `(libellé, valeur)` extraite sur le bon champ demandé
   par l'appel API pour le projet eDoc concerné, via correspondance floue (jamais d'égalité stricte —
   voir règle de conception plus haut).~~ **Fait** (commit `18bd7d3`) : fuzzy matching déterministe
   contre `schema_fields.yaml`, assignation gloutonne globale, 56 tests verts.
2. **Validation** — vérifier chaque champ classé contre la table de référence de son projet, avec la
   règle D11 (aucun rejet automatique silencieux, toujours `TO_REVIEW` pour une valeur inconnue).
   **Prochaine étape, pas encore commencée.**
3. **API REST** — exposer le pipeline complet (upload → extraction → classification → validation →
   réponse JSON par champ). Le moteur actuel n'a **pas** d'API fonctionnelle ; `SmokeTestRunner` est un
   outil de test en ligne de commande, pas le produit final.
4. **Tesseract** comme deuxième moteur de lecture pour les pages de garde simples, puis comparaison
   avec Mistral — phases ultérieures, non commencées, non anticipées dans le code actuel.

---

## Stack imposée

| Rôle | Bibliothèque | Coordonnées Maven (à vérifier/ajuster selon dernières versions) |
|---|---|---|
| Framework | Spring Boot 3.x | `org.springframework.boot:spring-boot-starter-web` |
| Appel Mistral OCR | REST direct (pas de SDK officiel Java) | `RestClient` de Spring (inclus dans `spring-web`) |
| PDF | Apache PDFBox | `org.apache.pdfbox:pdfbox` |
| JSON | Jackson | inclus avec `spring-boot-starter-web` |
| Config | SnakeYAML | inclus avec Spring Boot |
| Tests | JUnit 5 + Mockito | `spring-boot-starter-test` |

**Ajouté depuis** : `me.xdrop:fuzzywuzzy:1.4.0` (P3, classification — voir commit `18bd7d3`).
**Ne pas ajouter maintenant** (arriveront en phase B, dédiée à Tesseract) : Tess4J, OpenCV Java —
inutiles tant qu'il n'y a qu'un seul moteur de lecture.

Modèle Mistral OCR **épinglé** : `mistral-ocr-4-0` (jamais `mistral-ocr-latest`, pour que les résultats restent reproductibles d'un run à l'autre).

---

## Schéma d'extraction générique (point de départ, à ajuster si besoin)

```java
record CartoucheField(String label, String value) {}
record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {}
```

Prompt d'annotation à utiliser pour Mistral (à adapter) : demander de localiser le bloc d'identification (cartouche) du document — généralement encadré, contenant les métadonnées comme numéro, auteur, titre, phase, échelle, indice — et de recopier chaque paire libellé/valeur telle qu'imprimée, sans interpréter, sans traduire, sans inventer une paire si une valeur est absente.

---

## Configuration attendue (état initial — historique)

- Clé API Mistral : dans un fichier `.env` à la racine (`MISTRAL_API_KEY=...`), jamais commité — prévoir un `.env.example` et un `.gitignore` adapté.
- Documents d'exemple : j'ai déjà 3 documents représentatifs du corpus (`6.pdf` — page de garde A4 texte natif, `16.pdf` — plan dense A0 texte natif, `25.pdf` — page de garde A4 **scannée**, sans couche texte) récupérés d'un précédent smoke test en Python. Je vais les déposer dans le dossier que tu crées pour ça (probablement `data/samples/` ou `src/main/resources/samples/` — dis-moi lequel tu préfères et pourquoi).

> **Mise à jour :** `data/samples/` a été retenu (jamais empaqueté dans le JAR, jamais versionné —
> voir README pour la justification). Le corpus de test compte désormais **27 documents**, déposés
> au fil des sessions pour couvrir plus de cas réels (formats variés, nombre de pages variable,
> qualité de scan variable). **Ce corpus ne sert qu'au test** : voir la note « Provenance des
> documents » ci-dessous.

---

## Critère de succès de la toute première session (historique — atteint)

Le code compile, `check_setup` s'exécute et rapporte clairement l'état de l'environnement, et le test décisif Mistral tourne (avec une vraie clé API) sur les 3 documents en affichant les paires libellé/valeur extraites — que le résultat soit bon ou mauvais. Le verdict humain sur la qualité de l'extraction vient après, ce n'est pas à toi de le juger automatiquement.

Si quelque chose dans ce brief n'est pas clair ou te semble incohérent, demande avant de coder — ne suppose pas.

---

## Provenance des documents : test vs production (à ne jamais perdre de vue)

Les documents dans `data/samples/` (aujourd'hui 27) sont **uniquement pour tester et valider le
moteur pendant qu'on le construit**. Ils ne font partie d'aucune version livrée.

**Le fonctionnement réel, une fois le moteur branché à eDoc :** le moteur recevra chaque document via
un **appel API**, à chaque fois qu'un utilisateur dépose un document dans eDoc, et renverra le
résultat de l'extraction (puis, plus tard, de la classification et de la validation) via une
**réponse API** — jamais en lisant un fichier depuis un dossier sur le disque du serveur.

C'est pour cette raison que le cœur du moteur est écrit pour recevoir des `byte[]` (octets bruts) et
non des chemins de fichier (`Path`/`File`) — voir la règle correspondante dans `CLAUDE.md`. Aucune
partie de la logique d'extraction ne doit jamais dépendre d'un document de test précis, d'un nom de
fichier, ou d'une hypothèse propre au corpus actuel : la solution doit être **générique**, valable
pour n'importe quel document qu'un utilisateur déposera un jour, pas seulement ceux qu'on a sous la
main aujourd'hui pour tester.

Cette note est une clarification de contexte, pas une nouvelle tâche : l'API REST elle-même n'est
**pas encore construite** (voir « Ce qui reste à faire » plus haut) — on avance étape par étape, et
cette section sert seulement à s'assurer qu'aucune décision prise en cours de route ne ferme la porte
à cet usage final.
