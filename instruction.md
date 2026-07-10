

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

## Objectif de CETTE session (portée : ÉA1 uniquement — Mistral seul)

Le projet avance en 3 phases : **Mistral seul de bout en bout d'abord** (cette session s'inscrit dans cette phase), Tesseract ensuite comme deuxième voie, la comparaison des deux en dernier. Ne pas anticiper Tesseract dans cette session — aucune dépendance Tess4J ou OpenCV à ajouter maintenant, ce sera une session dédiée plus tard.

Cette première session doit uniquement :

1. Scaffolder un projet Maven / Spring Boot 3.x, JDK 17.
2. Écrire un **script de vérification d'environnement** (`check_setup`) qui contrôle : JDK 17 actif, dépendances Maven résolues, clé API Mistral lisible, documents d'exemple présents. Doit échouer proprement et lisiblement si quelque chose manque, pas planter avec une stack trace.
3. Écrire **le test décisif** : envoyer 2-3 documents représentatifs à Mistral OCR avec un **schéma d'annotation ouvert** (pas de champs nommés à l'avance) et afficher le JSON brut retourné, pour vérifier par la lecture humaine si le cartouche est correctement détecté et ses libellés/valeurs correctement extraits.

Rien d'autre. Pas de classification, pas de validation, pas d'API REST fonctionnelle, pas de base de données, **pas de Tesseract**. Le but est de répondre à une question binaire : **est-ce que Mistral peut détecter un cartouche générique et l'extraire correctement ?** Tout le reste du projet en dépend.

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

**Ne pas ajouter maintenant** (arriveront en phase B, dédiée à Tesseract) : Tess4J, OpenCV Java, fuzzywuzzy — inutiles tant qu'il n'y a qu'un seul moteur et pas de classification/validation dans cette session.

Modèle Mistral OCR **épinglé** : `mistral-ocr-4-0` (jamais `mistral-ocr-latest`, pour que les résultats restent reproductibles d'un run à l'autre).

---

## Schéma d'extraction générique (point de départ, à ajuster si besoin)

```java
record CartoucheField(String label, String value) {}
record CartoucheExtraction(boolean cartoucheFound, List<CartoucheField> fields) {}
```

Prompt d'annotation à utiliser pour Mistral (à adapter) : demander de localiser le bloc d'identification (cartouche) du document — généralement encadré, contenant les métadonnées comme numéro, auteur, titre, phase, échelle, indice — et de recopier chaque paire libellé/valeur telle qu'imprimée, sans interpréter, sans traduire, sans inventer une paire si une valeur est absente.

---

## Configuration attendue

- Clé API Mistral : dans un fichier `.env` à la racine (`MISTRAL_API_KEY=...`), jamais commité — prévoir un `.env.example` et un `.gitignore` adapté.
- Documents d'exemple : j'ai déjà 3 documents représentatifs du corpus (`6.pdf` — page de garde A4 texte natif, `16.pdf` — plan dense A0 texte natif, `25.pdf` — page de garde A4 **scannée**, sans couche texte) récupérés d'un précédent smoke test en Python. Je vais les déposer dans le dossier que tu crées pour ça (probablement `data/samples/` ou `src/main/resources/samples/` — dis-moi lequel tu préfères et pourquoi).

---

## Critère de succès de cette session

Le code compile, `check_setup` s'exécute et rapporte clairement l'état de l'environnement, et le test décisif Mistral tourne (avec une vraie clé API) sur les 3 documents en affichant les paires libellé/valeur extraites — que le résultat soit bon ou mauvais. Le verdict humain sur la qualité de l'extraction vient après, ce n'est pas à toi de le juger automatiquement.

Si quelque chose dans ce brief n'est pas clair ou te semble incohérent, demande avant de coder — ne suppose pas.
