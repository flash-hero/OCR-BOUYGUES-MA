# Comprendre le projet — guide simple

Ce document explique **tout ce qui a été construit**, comme si tu le découvrais pour la première
fois. Pas besoin de connaître Java ou Spring Boot avant de le lire : chaque idée est expliquée avec
des mots simples et des exemples concrets.

---

## 1. C'est quoi ce projet, en une phrase ?

Un utilisateur dépose un plan technique (PDF) dans l'application **eDoc**. Notre programme lit
automatiquement le petit tableau d'identification du plan — le **cartouche** — pour remplir tout
seul le formulaire de dépôt, au lieu que l'utilisateur tape tout à la main.

**Exemple concret.** Sur un plan d'architecte, il y a souvent une petite boîte, en bas à droite ou
dans un coin, qui ressemble à ça :

```
┌─────────────────────────────┐
│ PROJET   : 54B               │
│ EMETTEUR : LACH               │
│ LOT      : MIN               │
│ PHASE    : EXE                │
│ INDICE   : A                  │
└─────────────────────────────┘
```

C'est ce cartouche que le programme doit trouver sur la page, puis lire, ligne par ligne, sans se
tromper — que la boîte soit en haut, en bas, à gauche, à droite, petite, grande, sur un A4 ou sur un
plan aussi grand qu'une table.

---

## 2. Le principe : deux métiers séparés

Le projet complet (le "cahier des charges") demande deux choses très différentes, et on a choisi de
**ne jamais les mélanger** :

1. **Extraction (ce qui est fait aujourd'hui)** : trouver le cartouche sur le document et **recopier
   tout ce qu'il contient**, texte brut, sans deviner à quoi ça sert. Le programme ne sait pas que
   "PHASE" est un champ important pour eDoc — il lit juste ce qui est écrit.
2. **Classification (la prochaine étape, pas encore construite)** : prendre chaque ligne lue
   (« PHASE : EXE ») et décider sur quel champ du formulaire eDoc elle doit atterrir. Chaque projet
   eDoc a sa propre liste de champs à remplir — donc cette étape a besoin de savoir, à chaque appel,
   quels champs sont demandés.

**Pourquoi séparer les deux ?** Parce que si on mélangeait "lire" et "comprendre", le programme
serait obligé de connaître à l'avance la liste exacte des champs de *tous* les projets eDoc possibles.
Impossible : un projet demande Phase/Emetteur/Lot, un autre demande en plus Bâtiment, un troisième a
des libellés différents. En séparant, l'extraction reste **la même pour tout le monde**, et seule la
classification change selon le projet.

> **Analogie simple.** Imagine un élève qui recopie un tableau au propre (extraction), et un
> professeur qui, ensuite, décide quelle case correspond à quelle matière (classification). L'élève
> n'a pas besoin de connaître le programme scolaire pour bien recopier.

---

## 3. Ce qui est fait, et ce qui reste à faire

| Étape | Statut |
|---|---|
| **Extraction générique** — trouver et lire le cartouche | ✅ **Terminé et testé sur 27 documents** |
| **Classification** — ranger chaque paire sur le bon champ du formulaire | ⏳ Pas encore commencé |
| **Validation** — vérifier chaque champ classé contre les listes officielles eDoc | ⏳ Pas encore commencé |
| **API REST** — exposer tout ça pour que eDoc puisse appeler le moteur | ⏳ Pas encore commencé |
| **Tesseract (deuxième lecteur)** + comparaison avec Mistral | ⏳ Prévu bien plus tard |

Ce document explique en détail la partie ✅ **Terminé** : comment elle est construite, pourquoi, et
quels problèmes on a rencontrés en la construisant.

---

## 4. Important : les 27 PDF de test ne sont PAS le produit final

Dans le dossier `data/samples/`, il y a 27 fichiers PDF (`1.pdf`, `2.pdf`, … `27.pdf`). **Ce ne sont
que des exemples pour tester le programme pendant qu'on le construit.** Ils ne font partie d'aucune
version finale.

**Le vrai fonctionnement, une fois terminé :**

- Un utilisateur dépose un document dans eDoc → eDoc **envoie ce document à notre moteur par un appel
  API** (pas un fichier posé dans un dossier).
- Notre moteur lit le document et **renvoie le résultat par un appel API** (pas un fichier écrit sur
  le disque).
- Ça doit marcher pour **n'importe quel document que n'importe quel utilisateur dépose un jour** — pas
  seulement les 27 PDF qu'on a sous la main aujourd'hui.

**Pourquoi c'est mentionné explicitement ?** Parce que c'est une règle de conception qu'on a respectée
dès le début du code : le cœur du programme (la partie qui lit le cartouche) **ne connaît jamais un
chemin de fichier sur le disque**. Il ne reçoit que des **octets** (le contenu brut du fichier, sous
forme de nombres). Aujourd'hui, ces octets viennent d'un fichier qu'on lit sur le disque pour tester.
Demain, ils viendront d'un fichier envoyé par un utilisateur via une requête web. **Le code du moteur
sera exactement le même dans les deux cas** — seule la toute petite couche qui "va chercher les
octets" change. On a délibérément évité toute solution qui marcherait seulement pour "les documents
qu'on a testés", pour être sûr d'avoir une solution générique dès le premier jour.

---

## 5. Le voyage du projet — du premier essai à aujourd'hui

Racontons l'histoire dans l'ordre, comme un journal de bord.

### Étape 0 — "Est-ce que Mistral sait même lire un cartouche ?" (ÉA1)

Avant de construire quoi que ce soit de compliqué, il fallait vérifier une chose toute simple :
est-ce qu'un outil d'intelligence artificielle appelé **Mistral OCR** (un service en ligne qui sait
lire des images et des PDF) est capable de repérer un cartouche sur un document et d'en recopier le
contenu correctement ?

On a envoyé quelques documents à Mistral avec une consigne "ouverte" : *"trouve le cartouche, et
recopie toutes les paires libellé/valeur que tu vois, sans qu'on te dise à l'avance lesquelles
chercher."* Résultat : ça marchait bien sur des documents simples (A4), mais mal sur les très grands
plans.

### Étape 1 — Le problème des grands plans

Sur un plan technique de 2 mètres de large (format A0), le cartouche est minuscule dans un coin.

**Pourquoi ça posait problème ?** Mistral, comme beaucoup d'outils qui lisent des images, redimensionne
automatiquement l'image reçue à une taille fixe avant de la lire — un peu comme si on rétrécissait
une photo pour l'envoyer par message. Sur un A4, ça ne pose pas de souci : tout reste lisible même en
plus petit. Mais sur un plan de 2 mètres rétréci à la même taille qu'un A4, le petit cartouche dans le
coin devient une bouillie de pixels illisible — comme essayer de lire l'étiquette d'un médicament sur
une photo prise depuis l'autre bout de la pièce.

**La solution : lire en deux fois (« deux passes »).**

1. **Passe 1 — "où est la boîte ?"** On envoie toute la page à Mistral, mais on lui demande
   seulement : *"dans quel coin de la page vois-tu une petite boîte de type cartouche ?"* (pas de lire
   son contenu, juste de dire "en bas à droite", "en haut à gauche", etc.). Cette question grossière
   reste répondable même sur une image rétrécie — un peu comme reconnaître qu'il y a un tableau au mur
   d'une pièce, même sur une photo floue, sans pouvoir lire ce qu'il y a écrit dessus.
2. **Passe 2 — "maintenant, lis cette zone en détail."** Une fois qu'on sait dans quel coin regarder,
   on **découpe** cette zone du PDF original et on la **redessine en très haute résolution**, comme si
   on zoomait avec un appareil photo avant de reprendre la photo. Cette image découpée et zoomée, on
   la renvoie à Mistral avec la vraie question : *"lis toutes les paires libellé/valeur que tu vois."*
   Comme l'image envoyée est petite (juste le coin) mais en haute résolution, le texte reste net après
   le redimensionnement de Mistral.

> **Analogie simple.** C'est comme chercher un mot dans un dictionnaire : d'abord on ouvre le livre à
> peu près à la bonne lettre (passe 1, approximatif mais rapide), puis on zoome sur la bonne page pour
> lire le mot exact (passe 2, précis).

### Étape 2 — "Le cartouche n'est jamais au même endroit"

Une fausse bonne idée aurait été de toujours regarder en bas à droite, puisque c'est l'endroit le plus
courant. **On a délibérément refusé cette solution.** En testant sur plusieurs plans réels, on a
observé des cartouches dans **3 coins différents** sur seulement 4 plans testés au début. Coder "le
cartouche est toujours en bas à droite" aurait cassé le programme sur près d'un plan sur deux dans le
vrai monde. C'est la Passe 1 (ci-dessus) qui résout ça : c'est Mistral qui regarde et qui dit où est
la boîte, document par document — jamais une position fixée d'avance dans le code.

### Étape 3 — "Le contrôle qualité s'est fait avoir par une fausse boîte"

Sur un document (`10.pdf`), la Passe 1 a pointé vers une petite boîte qui **ressemblait** à un
cartouche mais qui était en réalité une **légende de symboles** (une liste "REF / SYMBOLE /
DÉNOMINATION" utilisée pour expliquer des pictogrammes sur le plan, pas pour identifier le document).

**Pourquoi le programme a d'abord accepté cette erreur ?** Le contrôle de qualité (« est-ce que ça
ressemble à un cartouche ? ») vérifiait juste "y a-t-il au moins 3 lignes courtes ?" — et la légende
de symboles avait bien 3 lignes courtes, mais avec des **valeurs vides**. On a corrigé la règle :
une ligne ne compte comme "vraie ligne de cartouche" que si elle a **une valeur réellement remplie**,
pas juste un libellé. Une légende de symboles a des libellés (REF, SYMBOLE...) mais quasiment aucune
valeur remplie à côté → elle est maintenant rejetée, et le programme essaie un autre coin.

### Étape 4 — "Et si aucun coin ne marche du premier coup ?"

Même avec la Passe 1, il arrive que le coin deviné soit faux (par exemple, si la Passe 1 pointe vers
le titre du plan au lieu du cartouche). Solution : après la Passe 2, on vérifie si le résultat obtenu
**ressemble vraiment** à un cartouche (`CartouchePlausibility`, expliqué plus bas). **Si non**, on
essaie automatiquement les autres coins probables l'un après l'autre (bas-droite d'abord, car c'est
le plus fréquent, puis les autres), jusqu'à en trouver un qui passe le contrôle. On ne s'arrête sur un
coin que s'il produit un **vrai** cartouche — jamais "parce que c'est celui par défaut".

### Étape 5 — "Un document de plus de 30 pages fait planter l'appel"

Mistral refuse de traiter un document PDF de plus de 30 pages d'un coup (erreur technique de leur
côté). Or certains documents du corpus de test ont 63, 90, voire 153 pages (souvent des dossiers
complets avec le plan sur la première page seulement).

**Solution.** Au lieu d'envoyer le PDF entier à Mistral, le programme **dessine lui-même une image**
de la première page (celle qui contient le cartouche) et envoie **cette image**, jamais le PDF brut.
Comme une image d'une seule page n'a — par définition — jamais plus de 30 pages, cette limite ne peut
plus jamais être atteinte, quel que soit le nombre de pages du document original. Bonus : ça a aussi
rendu la lecture plus fiable en général, car on contrôle nous-mêmes la qualité de l'image envoyée au
lieu de laisser Mistral redimensionner le PDF à sa manière.

### Étape 6 — "Recommencer un test à chaque fois coûte cher"

Chaque appel à Mistral coûte de l'argent (c'est un service payant). Au début, relancer le test sur les
27 documents rappelait Mistral 27 fois (plus, pour les grands plans qui ont besoin de 2 appels).
Recommencer un test juste pour vérifier un petit changement devenait cher.

**Solution : un cache.** Avant chaque appel à Mistral, le programme calcule une **empreinte unique**
(un peu comme une empreinte digitale) du contenu exact qu'il s'apprête à envoyer. Si cette empreinte a
déjà été vue avant, il **relit la réponse déjà enregistrée sur le disque** au lieu de rappeler
Mistral. Résultat : relancer le test sur les 27 documents une deuxième fois ne coûte quasiment plus
rien (0 appel réseau si rien n'a changé). Dès qu'on change une consigne envoyée à Mistral (par exemple
le texte de la demande, ou la résolution de l'image), l'empreinte change automatiquement, donc le
cache se met à jour tout seul, sans qu'on ait à y penser.

### Étape 7 — "Le dernier document récalcitrant"

Sur `21.pdf` (un très grand plan très dense), la Passe 1 échouait souvent à dire où était le
cartouche (elle répondait "je ne sais pas"). Mais en testant, on s'est rendu compte que **si on
essayait quand même un coin au hasard, l'extraction marchait très bien** — le problème n'était donc
pas "le cartouche est illisible", mais "deviner sa position en un coup d'œil sur toute la page est
trop difficile pour ce document précis".

**Solution.** On a réutilisé exactement le même mécanisme de repli que l'Étape 4 (essayer les coins un
par un jusqu'à trouver le bon), mais **aussi** quand la Passe 1 répond "je ne sais pas" — avant, dans
ce cas, le programme abandonnait tout de suite. Maintenant, il essaie quand même. Résultat : les 27
documents du corpus de test s'extraient désormais tous correctement.

### Étape 8 — Une idée testée, puis abandonnée (et c'est normal !)

On a essayé une idée : et si on donnait à Mistral, en indice dans la consigne, une **liste des champs
qu'on cherche typiquement** (PHASE, EMETTEUR, LOT...) pour l'aider à mieux repérer le cartouche ?

**Résultat du test :** ça n'a pas causé Mistral à inventer des champs qui n'existent pas (bon signe),
**mais** ça a fait disparaître des champs **réels** qui n'étaient pas sur la liste d'indices — sur un
document, le champ "AUTEUR" (vraiment présent) disparaissait systématiquement dès qu'on donnait la
liste d'indices, car Mistral avait tendance à ne recopier que ce qui ressemblait à la liste donnée.

C'est contraire au principe de base du projet : **l'extraction doit tout recopier, sans supposer à
l'avance ce qui doit exister**. On a donc **annulé cette idée** et gardé la version qui recopie tout,
sans indice de champs. Ce n'est pas un échec : tester une idée, mesurer qu'elle ne marche pas bien, et
revenir en arrière proprement, c'est aussi une façon normale et saine d'avancer.

---

## 6. L'architecture : dossier par dossier, fichier par fichier

Voici la structure du projet, avec une explication simple pour chaque élément.

```
OCR-PFA/
├── src/main/java/com/bycn/edoc/
│   ├── EdocOcrApplication.java              (le bouton "démarrer" du programme)
│   ├── config/
│   │   └── DotenvEnvironmentPostProcessor.java  (lit le fichier secret .env)
│   ├── ocr/                                  (TOUT le cœur du moteur est ici)
│   │   ├── MistralOcrProperties.java
│   │   ├── MistralOcrClient.java
│   │   ├── CartoucheAnnotationSchema.java
│   │   ├── CartoucheLocationSchema.java
│   │   ├── TwoPassCartoucheExtractor.java
│   │   ├── CartouchePlausibility.java
│   │   ├── CropRegion.java
│   │   ├── PdfSupport.java
│   │   ├── OcrResponseCache.java
│   │   ├── OcrConfig.java
│   │   └── (les "boîtes de données" : CartoucheField, CartoucheExtraction,
│   │        CartoucheLocation, CartoucheAnalysis, OcrResult, MistralOcrException)
│   └── smoke/
│       └── SmokeTestRunner.java              (le programme de test qu'on lance à la main)
├── src/test/java/...                         (les tests automatiques, voir plus bas)
├── src/main/resources/
│   ├── application.yml                       (réglages du programme)
│   └── application-smoke.yml                 (réglages spéciaux pour le mode test)
├── data/samples/                             (les 27 PDF de test — PAS le produit final, voir §4)
├── docs/                                      (toute la documentation, ce fichier y compris)
│   ├── understand.md
│   ├── howtorun.md
│   ├── instruction.md
│   └── AGENT_CONTEXT.md
├── scripts/
│   ├── check_setup.ps1                       (vérifie que tout est prêt avant de lancer)
│   └── run_smoke.ps1                         (lance le test décisif en une commande)
├── .env                                       (ta clé secrète Mistral — jamais partagée/publiée)
├── .env.example                               (un modèle vide du fichier .env, celui-là est public)
└── pom.xml                                    (la liste des outils/bibliothèques utilisées)
```

### `EdocOcrApplication.java` — le bouton démarrer

C'est le tout premier fichier lu quand le programme se lance. Il ne fait presque rien lui-même : il
dit juste "démarre Spring Boot" (le moteur qui fait tourner toute l'application Java). C'est comme
la clé de contact d'une voiture : elle ne conduit pas, elle allume le moteur.

### `config/DotenvEnvironmentPostProcessor.java` — lire le fichier secret

Le programme a besoin d'une **clé secrète** (un mot de passe) pour parler à Mistral. Cette clé est
écrite dans un fichier `.env` à la racine du projet, qui n'est **jamais** envoyé sur GitHub (pour ne
pas la rendre publique). Ce fichier lit ce `.env` au démarrage et rend la clé disponible au reste du
programme, un peu comme si on glissait un mot de passe sous la porte avant que quelqu'un en ait
besoin.

### Le dossier `ocr/` — le cœur du moteur

C'est ici que tout se passe. Voici chaque fichier, dans l'ordre logique du parcours d'un document :

**1. `MistralOcrProperties.java`** — une petite fiche de réglages : quelle adresse internet appeler,
quel modèle d'IA utiliser (toujours le même, jamais "la dernière version" — pour que les résultats
restent stables d'un jour à l'autre), et si le cache est activé.

**2. `PdfSupport.java`** — la boîte à outils pour manipuler les PDF : compter les pages, mesurer la
taille physique d'une page (pour savoir si c'est un A4 ou un plan géant), et surtout **dessiner une
image** d'une zone précise du PDF à la résolution qu'on veut (utilisé pour les Passes 1 et 2, voir
Étape 1 plus haut).

**3. `CartoucheLocationSchema.java`** — la question posée à Mistral pendant la **Passe 1** : "dans
quel coin de la page vois-tu une boîte qui ressemble à un cartouche ?" (grille de 9 zones possibles +
"je ne sais pas").

**4. `CartoucheAnnotationSchema.java`** — la question posée à Mistral pendant la **Passe 2** (ou la
lecture directe pour les documents simples) : "recopie-moi toutes les paires libellé/valeur du
cartouche, telles qu'imprimées, sans traduire, sans inventer."

**5. `CropRegion.java`** — traduit "le cartouche est en bas à droite" en un vrai rectangle de
découpage (des coordonnées précises sur l'image), avec une zone volontairement **généreuse** (40 % de
la page) pour ne pas rater le cartouche si la Passe 1 s'est un peu trompée.

**6. `MistralOcrClient.java`** — le "téléphone" qui appelle réellement Mistral sur internet. Il
prépare la requête, l'envoie, et transforme la réponse (du texte JSON) en objets Java faciles à
utiliser dans le reste du programme.

**7. `OcrResponseCache.java`** — la "mémoire" qui évite de rappeler Mistral pour une question déjà
posée (voir Étape 6 plus haut).

**8. `CartouchePlausibility.java`** — le videur à l'entrée : "est-ce que ce qu'on vient de lire
ressemble vraiment à un cartouche, ou est-ce qu'on s'est trompé de zone ?" (voir Étape 3 plus haut).

**9. `TwoPassCartoucheExtractor.java`** — **le chef d'orchestre.** C'est ce fichier qui décide, pour
chaque document : "est-ce un document simple (une seule lecture directe) ou un grand plan (deux
passes) ?", qui déclenche la Passe 1 puis la Passe 2, qui vérifie la qualité, et qui relance sur
d'autres coins si besoin (voir Étapes 1, 4 et 7). C'est le fichier le plus important à comprendre si
tu veux suivre "le voyage" d'un document du début à la fin.

**10. `OcrConfig.java`** — le fichier qui "branche" tous les morceaux ensemble au démarrage (un peu
comme un plan de câblage électrique : il dit "connecte ce fil-là à cette prise-là").

**11. Les "boîtes de données"** (`CartoucheField`, `CartoucheExtraction`, `CartoucheLocation`,
`CartoucheAnalysis`, `OcrResult`) — ce sont de simples petites structures qui transportent
l'information d'un morceau du programme à un autre, comme des enveloppes étiquetées. Par exemple,
`CartoucheField` transporte juste une paire `(libellé, valeur)`, par exemple `("PHASE", "EXE")`.

### `smoke/SmokeTestRunner.java` — le testeur

Ce programme (qu'on lance à la main avec `run_smoke.ps1`, voir `howtorun.md`) prend chaque PDF du
dossier `data/samples/`, l'envoie au chef d'orchestre (`TwoPassCartoucheExtractor`), et **affiche à
l'écran** ce qui a été trouvé, pour qu'un humain puisse vérifier si c'est correct. C'est notre outil
de vérification pendant qu'on construit le moteur — il ne fera **pas** partie du produit final (voir
§4 : le produit final recevra les documents par API, pas depuis un dossier).

### Les tests automatiques (`src/test/java/...`)

Ce sont des petits programmes qui vérifient automatiquement que le code fait bien ce qu'il est censé
faire, **sans jamais appeler le vrai Mistral** (donc gratuits et rapides). Par exemple, un test
vérifie que "si Mistral répond telle chose, alors `CartouchePlausibility` doit dire vrai/faux" — sans
avoir besoin d'internet. On en a **29**, et ils passent tous.

---

## 7. Le parcours complet d'un document, étape par étape

Suivons un document fictif `plan.pdf` (un très grand plan) du début à la fin :

1. **On mesure la page.** `PdfSupport` regarde la taille physique de la première page. Si elle fait
   plus de 430 mm de long (plus grande qu'un A3), c'est un "grand format" → deux passes. Sinon, c'est
   un document standard → lecture directe.

2. *(Cas grand format)* **Passe 1 : où est le cartouche ?** Le programme dessine une image de toute la
   page et demande à Mistral : "dans quel coin ?". Mistral répond, par exemple, `"bottom-right"`.

3. **Découpage.** `CropRegion` transforme `"bottom-right"` en un rectangle précis (les 40 % en bas à
   droite de la page).

4. **Passe 2 : lecture en détail.** `PdfSupport` redessine **seulement ce rectangle**, mais en très
   haute résolution (jusqu'à 3400 pixels de large), et l'envoie à Mistral avec la vraie question :
   "recopie toutes les paires libellé/valeur."

5. **Contrôle qualité.** `CartouchePlausibility` regarde le résultat : y a-t-il au moins 3 paires,
   dont au moins 3 qui ressemblent à des codes courts avec une vraie valeur (pas une phrase, pas une
   valeur vide) ? Si oui → c'est bon, on garde. Si non → on essaie un autre coin (repli), jusqu'à 4
   essais.

6. **Résultat final.** Le chef d'orchestre renvoie un objet `CartoucheAnalysis` qui contient : la
   liste des paires trouvées, le coin retenu, si le contrôle qualité a été validé, et combien
   d'essais ont été nécessaires.

7. **Affichage (aujourd'hui) / réponse API (demain).** Aujourd'hui, `SmokeTestRunner` affiche ce
   résultat à l'écran pour vérification humaine. Demain, une API REST renverra ce même résultat sous
   forme de réponse JSON à eDoc.

---

## 8. Récapitulatif des problèmes rencontrés et solutions

| # | Problème rencontré | Pourquoi ça arrivait | Solution appliquée |
|---|---|---|---|
| 1 | Cartouche illisible sur les très grands plans | Mistral rétrécit toute image à une taille fixe ; un petit cartouche dans un coin d'un plan de 2 m devient flou | Lecture en **deux passes** : d'abord deviner le coin (grossier), puis redessiner ce coin en haute résolution et le relire |
| 2 | La Passe 1 pointait vers le titre du plan, pas le cartouche | La consigne demandait "un bloc encadré" sans préciser que le titre n'en est pas un | Consigne réécrite pour insister : le cartouche est un **bloc dense de codes courts**, jamais une phrase |
| 3 | Une légende de symboles acceptée à tort comme cartouche | Le contrôle qualité comptait les libellés, mais pas si les valeurs étaient vraiment remplies | Une paire ne compte comme "vraie ligne de cartouche" que si sa **valeur** est non vide |
| 4 | Un tableau à deux lignes (en-têtes / valeurs) mal recopié | Mistral recopiait les en-têtes avec des valeurs vides, et les valeurs toutes seules, séparément | Consigne précisée : associer chaque en-tête à la valeur juste en dessous |
| 5 | Documents de plus de 30 pages refusés par Mistral | Limite technique du service Mistral | On envoie toujours une **image de la page 1 uniquement**, jamais le PDF entier |
| 6 | Retester coûtait cher (rappeler Mistral à chaque fois) | Aucune mémoire des résultats déjà obtenus | Ajout d'un **cache** : une empreinte du contenu envoyé sert de "ticket" pour retrouver la réponse déjà connue |
| 7 | Un document, très dense, restait bloqué (« je ne sais pas où chercher ») | La Passe 1 échouait souvent sur ce document précis, même si le contenu était lisible en zoomant | Le programme essaie maintenant les coins probables un par un, même quand la Passe 1 dit "je ne sais pas" |
| 8 | Idée testée : donner à Mistral la liste des champs attendus | Objectif : l'aider à mieux repérer le cartouche | Testé, puis **abandonné** : ça faisait disparaître des champs réels absents de la liste d'indices — contraire au principe "tout recopier sans supposer" |

---

## 9. Les mots un peu techniques, expliqués simplement

- **Cartouche** : la petite boîte d'identification d'un plan (numéro, auteur, date, échelle...), pas
  le dessin technique lui-même.
- **OCR** (*Optical Character Recognition*) : la technologie qui "lit" du texte à partir d'une image
  ou d'un scan, comme si un robot regardait une photo et recopiait ce qu'il voit.
- **Prompt** : la question ou la consigne qu'on écrit pour demander quelque chose à une intelligence
  artificielle, en langage humain.
- **Schéma d'annotation "ouvert"** : on ne dit pas à l'avance "je veux les champs PHASE, LOT..." — on
  demande juste "donne-moi toutes les paires libellé/valeur que tu vois", sans présumer lesquelles
  existent.
- **DPI** (*Dots Per Inch*) : la finesse d'une image — plus le DPI est élevé, plus l'image est
  détaillée (comme le nombre de pixels d'une photo).
- **Cache** : une mémoire qui garde les réponses déjà obtenues, pour ne pas refaire le même travail
  deux fois.
- **`byte[]`** (« tableau d'octets ») : le contenu brut d'un fichier, sous forme de nombres, sans
  savoir d'où il vient (un fichier sur le disque, un envoi par internet...). Utiliser `byte[]` partout
  dans le cœur du moteur garantit qu'il fonctionnera pareil, qu'on lui donne un fichier de test ou un
  document envoyé par un vrai utilisateur.
- **Non-déterminisme** : le fait qu'une intelligence artificielle puisse donner des réponses
  légèrement différentes si on lui pose exactement la même question deux fois. On l'a observé sur
  quelques documents très denses — ce n'est pas un bug de notre code, c'est une propriété du service
  Mistral lui-même, à garder en tête pour la suite du projet.

---

## 10. Où en est le projet aujourd'hui ?

- Le moteur d'extraction sait lire correctement le cartouche sur **les 27 documents de test**
  (documents simples A4 et grands plans A0), sans jamais rater un document ni deviner à l'aveugle sa
  position.
- **29 tests automatiques** vérifient que le code se comporte bien, sans appeler Mistral (gratuits et
  rapides à relancer).
- Tout le code est sur `main` (la version officielle et à jour du projet) sur GitHub.
- La prochaine étape (pas encore commencée) est la **classification** : décider, pour chaque paire
  lue, sur quel champ du formulaire eDoc elle correspond — voir `instruction.md` pour les règles déjà
  définies pour cette prochaine étape.
