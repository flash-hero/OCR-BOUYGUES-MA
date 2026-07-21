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
2. **Classification (fait, voir §10)** : prendre chaque ligne lue
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
| **Classification** — ranger chaque paire sur le bon champ du formulaire | ✅ **Terminé et testé** |
| **Validation** — vérifier chaque champ classé contre les listes officielles eDoc | ✅ **Terminé et testé** |
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

### Étape 9 — "Poser deux fois exactement la même question ne donne pas toujours la même réponse"

En comparant deux lancements sur le même document, avec exactement les mêmes octets envoyés à
Mistral, on a eu une mauvaise surprise : parfois la réponse change. Sur un document (`12.pdf`), un
lancement a trouvé 9 paires et a dit "oui, j'ai trouvé un cartouche" ; un autre lancement, sur la
**même image, avec la même question**, a répondu "non, je ne trouve rien". Sur un autre document
(`26.pdf`), le nombre de paires trouvées a varié entre 61 et 9 selon le tirage.

**Pourquoi ça arrive ?** Ce n'est pas un bug de notre code : c'est une propriété du service Mistral
lui-même. On a vérifié dans la documentation officielle de l'API s'il existait un réglage du genre
"donne-moi toujours exactement la même réponse" (souvent appelé *seed* ou *temperature* sur d'autres
services d'IA) — il n'en existe **aucun** sur cet endpoint précis. Impossible donc de "forcer" la
reproductibilité d'un seul coup de téléphone à Mistral.

**Solution : ne plus se fier à une seule réponse, mais à un vote.** Au lieu de poser la question une
seule fois, le programme la pose maintenant **5 fois d'un coup, en même temps** (donc ça ne prend pas
plus de temps, voir Étape 11), et regarde les 5 réponses ensemble :
1. Si la majorité des réponses dit "oui, cartouche trouvé", on fait confiance à ce camp majoritaire
   (sinon, au camp qui dit "non").
2. Parmi les réponses du camp gagnant, on garde celle qui a un nombre de paires **ni trop petit ni
   trop grand** (la médiane) — jamais la réponse "vide" par accident, ni la réponse qui a
   exceptionnellement trouvé beaucoup trop de choses.

> **Analogie simple.** C'est comme demander à 5 personnes de lire un mot flou sur une photo : si 4
> disent "PHASE" et une dit autre chose, on suit les 4. Et si les réponses valables donnent des
> longueurs différentes de texte recopié, on garde celle qui a l'air la plus "normale", pas celle qui
> n'a presque rien recopié, ni celle qui a recopié un texte anormalement long.

Ce mécanisme de vote s'appelle le **consensus** (`OcrConsensus.java`, voir §6).

### Étape 10 — "Une mauvaise zone peut, elle aussi, ressembler à un cartouche"

Sur un document (`20.pdf`), le programme a longtemps renvoyé la **mauvaise boîte** : au lieu du petit
cartouche d'identification, il recopiait un grand tableau listant les intervenants du chantier —
l'entreprise, le maître d'œuvre, le bureau de contrôle — avec leurs **adresses postales complètes et
leurs numéros de téléphone**.

**Pourquoi le contrôle qualité (Étape 3-4) laissait passer cette erreur ?** Ce contrôle vérifiait
seulement "y a-t-il des lignes courtes remplies ?" — et ce tableau d'intervenants en a bien (des noms
de sociétés courts, des numéros). Il ressemblait donc, au sens strict de la règle, à un cartouche.

**Solution : un score qui regarde *ce qui est écrit*, pas juste *la forme*.** On a ajouté un système
de notation (`CartoucheScore.java`) qui donne des points **en plus** ou **en moins** selon le contenu
de chaque paire lue :
- **Points en plus** pour les libellés typiques d'un cartouche d'identification (Phase, Indice,
  Échelle, Lot, N° document...) et pour les valeurs courtes de type code.
- **Points en moins** pour tout ce qui ressemble à une adresse (rue, cedex...), un numéro de
  téléphone (beaucoup de chiffres à la suite), un code postal, une adresse e-mail, ou un rôle
  d'intervenant (constructeur, mainteneur, coordonnateur...).

Maintenant, quand plusieurs coins passent le contrôle qualité de base, le programme ne garde plus
"le premier qui passe" — il garde **celui qui obtient le meilleur score**. Résultat : sur `20.pdf`, le
panneau des intervenants (score très négatif) perd face au vrai cartouche d'identification (score
positif), même si le panneau des intervenants avait été essayé en premier.

### Étape 11 — "Aller plus vite sans perdre en exactitude"

Sur certains très grands plans, lire le cartouche prenait longtemps — parfois plus d'une minute pour
un seul document. Plutôt que de deviner pourquoi, on a **chronométré chaque étape séparément** pour
trouver le vrai responsable.

**Découverte n° 1 : le temps dépend du texte à lire, pas du nombre d'appels.** On a isolé un seul
document, tout seul, sans aucun autre traitement en même temps — et il a quand même pris plusieurs
dizaines de secondes. Ce n'était donc pas "trop d'appels à la suite qui se bousculent" : c'est que
**chaque appel à Mistral prend plus de temps quand l'image envoyée contient beaucoup de texte dense**
(un plan A0 très chargé), un peu comme il faut plus de temps pour lire à voix haute une page pleine de
texte qu'une page presque vide.

**Découverte n° 2 : la Passe 1 (deviner le coin) était l'appel le plus lent de tous.** La Passe 1
envoie **la page entière** à Mistral — sur un plan dense, c'est donc un OCR complet de tout le plan,
même si on ne lui demande qu'une réponse grossière ("quel coin ?"). En mesurant, on s'est rendu compte
qu'elle représentait à elle seule près de la moitié du temps total, pour un rôle qui n'est, au fond,
que de **départager** entre plusieurs coins quand le score (Étape 10) hésite.

**Ce qu'on a changé :**
1. **Une seule vague au lieu de plusieurs.** Avant, le programme essayait un coin, attendait la
   réponse, puis essayait le suivant si besoin — les temps s'additionnaient. Maintenant, les **quatre
   coins candidats sont envoyés à Mistral en même temps** (en parallèle), comme si on envoyait quatre
   équipes de recherche dans quatre pièces en même temps au lieu de les envoyer une par une.
2. **La Passe 1 désactivée par défaut.** Comme elle ne sert qu'à départager, et qu'en la testant on a
   vu que le score (Étape 10) choisissait **exactement le même coin** avec ou sans elle sur les
   documents essayés, on l'a désactivée par défaut — ça évite de payer son coût (le plus lent) pour un
   rôle de simple appoint. Elle reste réactivable par un réglage, sans toucher au code, si un jour ça
   s'avère utile sur d'autres documents.
3. **Deux idées essayées, puis abandonnées (mesurées, pas supposées).** On a testé si envoyer une image
   plus petite à Mistral irait plus vite : mesuré que **non**, et en plus ça a dégradé la lecture d'un
   document (13 paires bien lues devenues 29 paires confuses). On a aussi testé si découper une zone
   plus petite autour du coin irait plus vite : mesuré que ça **coupait carrément le cartouche** sur un
   autre document. Les deux idées ont été annulées — exactement comme à l'Étape 8, on teste, on
   mesure, et si ça ne marche pas vraiment, on revient en arrière plutôt que de garder une fausse bonne
   idée.
4. **Un échantillon raté ne fait plus planter tout le document.** Il arrive qu'un des 5 échantillons du
   vote (Étape 9) revienne avec une réponse coupée en plein milieu (illisible). Avant, ça faisait
   planter tout le document avec une erreur. Maintenant, cet échantillon raté compte juste comme "rien
   trouvé" pour ce tirage-là, et le vote continue avec les 4 autres. En bonus, si Mistral répond
   temporairement "trop de demandes à la fois" ou qu'il y a un petit souci réseau, le programme
   **réessaie automatiquement** une ou deux fois avant d'abandonner.

**Résultat mesuré :** les documents standards se lisent maintenant en 5 à 15 secondes, la plupart des
grands plans en 13 à 25 secondes. Les trois plans A0 les plus denses et les plus lourds du corpus
restent autour de 26 à 28 secondes — c'est le temps d'**un seul** appel Mistral à pleine qualité sur ce
genre de document ; descendre en dessous, on l'a mesuré deux fois, dégrade la lecture plutôt que de
la rendre plus rapide.

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
│   │   ├── OcrConsensus.java
│   │   ├── CartoucheAnnotationSchema.java
│   │   ├── CartoucheLocationSchema.java
│   │   ├── TwoPassCartoucheExtractor.java
│   │   ├── CartouchePlausibility.java
│   │   ├── CartoucheScore.java
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
utiliser dans le reste du programme. Depuis l'Étape 9, c'est aussi lui qui envoie les **5 échantillons
en parallèle** pour chaque question posée, plutôt qu'une seule fois.

**7. `OcrConsensus.java`** — le "compteur de votes" (voir Étape 9). Reçoit les 5 réponses d'un même
appel et décide laquelle garder : vote majoritaire sur "cartouche trouvé ou pas", puis, parmi le camp
gagnant, celle dont le nombre de paires est ni trop petit ni trop grand. Ne fusionne ni ne modifie
jamais une paire — il choisit juste **laquelle des 5 réponses réelles** on garde, telle quelle.

**8. `OcrResponseCache.java`** — la "mémoire" qui évite de rappeler Mistral pour une question déjà
posée (voir Étape 6 plus haut). C'est le **résultat du vote** (pas chaque échantillon individuel) qui
est mémorisé : relancer sur un document déjà traité renvoie directement ce résultat, sans reposer les
5 questions.

**9. `CartouchePlausibility.java`** — le premier videur à l'entrée : "est-ce que ce qu'on vient de lire
ressemble à un formulaire de codes courts remplis, ou est-ce qu'on s'est trompé de zone ?" (voir
Étape 3 plus haut).

**10. `CartoucheScore.java`** — le second videur, plus fin (voir Étape 10). Parmi tout ce qui a passé
le premier videur, il donne une note à chaque coin : des points pour les libellés d'identification
(Phase, Indice, Échelle...) et les valeurs courtes de type code, des points en moins pour les adresses,
téléphones, e-mails et rôles d'intervenants. Sert à départager plusieurs coins qui, sans lui,
sembleraient tous également valables.

**11. `TwoPassCartoucheExtractor.java`** — **le chef d'orchestre.** C'est ce fichier qui décide, pour
chaque document : "est-ce un document simple (une seule lecture directe) ou un grand plan ?", qui
déclenche le rendu des quatre coins et (si activée) la Passe 1 **en une seule vague** (voir Étape 11),
qui applique les deux videurs (Étapes 9-10), et qui renvoie le meilleur résultat — validé si un coin a
passé le contrôle, marqué "à vérifier" sinon plutôt que de perdre les données lues. C'est le fichier le
plus important à comprendre si tu veux suivre "le voyage" d'un document du début à la fin.

**12. `OcrConfig.java`** — le fichier qui "branche" tous les morceaux ensemble au démarrage (un peu
comme un plan de câblage électrique : il dit "connecte ce fil-là à cette prise-là"), et qui lit les
réglages de latence de l'Étape 11 (résolution d'image, activer ou non la Passe 1, taille de la zone
découpée par coin).

**13. Les "boîtes de données"** (`CartoucheField`, `CartoucheExtraction`, `CartoucheLocation`,
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
   plus de 430 mm de long (plus grande qu'un A3), c'est un "grand format" → analyse par coins. Sinon,
   c'est un document standard → lecture directe (avec vote, voir point 3).

2. *(Cas grand format)* **Découpage des quatre coins candidats, tous en même temps.** `CropRegion`
   découpe les quatre coins possibles (bas-droite, bas-gauche, haut-droite, haut-gauche) en rectangles
   précis, et `PdfSupport` les redessine chacun en très haute résolution (jusqu'à 3400 pixels de
   large). Si la Passe 1 est activée (désactivée par défaut, voir Étape 11), la page entière est aussi
   redessinée pour elle, en parallèle du reste.

3. **Vote : chaque coin est interrogé 5 fois d'un coup.** Pour chaque coin (et pour la Passe 1 si
   activée), le programme envoie la même question à Mistral **5 fois en parallèle**, puis
   `OcrConsensus` choisit la réponse la plus représentative (voir Étape 9). Tous les coins et la Passe 1
   sont traités **dans la même vague** — rien n'attend son tour.

4. **Score : quel coin est le VRAI cartouche ?** Parmi tous les coins dont le résultat passe le
   contrôle qualité (`CartouchePlausibility`, ≥ 3 paires, ≥ 3 paires « courtes » remplies), le
   programme calcule un score (`CartoucheScore`, voir Étape 10) et garde celui qui a le **meilleur
   score** — pas juste le premier trouvé. Si la Passe 1 est activée, son coin suggéré sert seulement à
   départager les égalités de score.

5. **Repli si rien ne passe.** Si aucun coin ne passe le contrôle qualité, le programme renvoie quand
   même le coin le plus riche (le plus de paires trouvées), mais **marqué "à vérifier"** pour qu'un
   humain le confirme — jamais perdu silencieusement. Ce n'est que si **tous** les coins reviennent
   vides qu'on signale un vrai échec de localisation.

6. **Résultat final.** Le chef d'orchestre renvoie un objet `CartoucheAnalysis` qui contient : la
   liste des paires trouvées, le coin retenu, si le contrôle qualité a été validé, et le nombre de
   coins évalués (toujours 4, puisqu'ils sont maintenant tous essayés en parallèle plutôt qu'un par un).

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
| 9 | Deux questions identiques donnent parfois deux réponses différentes | Propriété du service Mistral (aucun réglage de reproductibilité disponible sur cet endpoint) | **Vote** : 5 échantillons en parallèle, on garde la réponse majoritaire au nombre de paires médian |
| 10 | Un panneau d'intervenants (adresses, téléphones) accepté à tort comme cartouche | Le contrôle qualité ne regardait que la forme (lignes courtes remplies), pas le contenu | **Score de coin** : bonus aux codes d'identification, malus aux adresses/téléphones/rôles ; on garde le meilleur score |
| 11 | Certains grands plans très denses mettaient plus d'une minute à se lire | Coins essayés un par un (temps additionnés) + Passe 1 = OCR complet du plan, l'appel le plus lent | **Une seule vague** (4 coins en parallèle) + Passe 1 désactivée par défaut (le score suffit) ; résolution/zone plus petites testées et **rejetées** (aucun gain, lecture dégradée) |

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
  Mistral lui-même (voir Étape 9). Le vote (consensus) est la façon dont on s'en protège.
- **Consensus (vote)** : au lieu de poser une question une seule fois à l'IA, on la pose plusieurs
  fois en parallèle et on garde la réponse la plus représentative, plutôt que de subir le hasard d'un
  seul tirage (voir Étape 9, `OcrConsensus`).
- **Score de coin** : une note calculée sur le contenu d'une extraction, pour départager plusieurs
  zones qui semblent toutes valables au premier regard — favorise les codes d'identification courts,
  pénalise les adresses et numéros de téléphone (voir Étape 10, `CartoucheScore`).

---

## 10. Où en est le projet aujourd'hui ?

- Le moteur d'extraction sait lire correctement le cartouche sur **les 27 documents de test**
  (documents simples A4 et grands plans A0), sans jamais rater un document ni deviner à l'aveugle sa
  position.
- Il est maintenant **robuste au non-déterminisme de Mistral** (vote sur 5 échantillons, Étape 9),
  **capable de préférer la bonne zone parmi plusieurs plausibles** (score de coin, Étape 10), et
  **rapide** : documents standards en 5 à 15 secondes, la plupart des grands plans en 13 à 25 secondes,
  les plans A0 les plus denses autour de 26 à 28 secondes (Étape 11).
- **102 tests automatiques** vérifient que le code se comporte bien, sans appeler Mistral (gratuits et
  rapides à relancer).
- Tout le code est sur `main` (la version officielle et à jour du projet) sur GitHub.
- La **classification** (décider, pour chaque paire lue, sur quel champ du formulaire eDoc elle
  correspond) et la **validation** (comparer chaque champ classé aux listes officielles eDoc) sont
  toutes les deux terminées et testées. La prochaine étape (pas encore commencée) est l'**API REST** —
  voir `instruction.md` et `AGENT_CONTEXT.md` pour l'état détaillé.
