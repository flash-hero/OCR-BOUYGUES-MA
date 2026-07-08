# Plan de travail v4 — Détection de cartouche + classification générique
**Une architecture indépendante du projet eDoc**
PFA Oussama — Bouygues Construction IT Maroc · Encadrant : M. Boumenzeh
Version 4.0

---

## 0. Le principe, en clair

Jusqu'ici, le pipeline posait une question fermée à l'OCR : "quelle est la valeur de PHASE ? de EMETTEUR ? de LOT ?" — en supposant que ces champs-là, avec ces noms-là, existent partout. Les captures eDoc montrent que ce n'est pas vrai : chaque projet configure sa propre liste de champs ("Codification" + "Champs personnalisés"). Rien ne garantit qu'un projet non encore vu n'a pas une liste encore différente.

Le nouveau principe sépare deux questions qui étaient mélangées en une seule :

1. **Lire** : dans le cartouche, quels libellés et quelles valeurs sont écrits — peu importe lesquels. On copie tout, tel quel.
2. **Classer** : une fois la copie brute obtenue, on la trie dans les bonnes cases — mais "les bonnes cases" dépendent du projet en question.

Analogie : au lieu de donner à quelqu'un un formulaire aux cases déjà nommées ("Nom : ___, Prénom : ___") et de lui demander de le remplir depuis un document, on lui demande d'abord de recopier fidèlement tout ce qui est écrit dans le bloc d'identification — chaque étiquette avec sa valeur, sans interpréter. Ensuite, dans un second temps séparé, on prend cette copie brute et on la range dans le bon formulaire — celui du client en question, puisque chaque client a le sien.

Ce n'est pas une remise à zéro : ce qui a été construit reste utilisable, juste repositionné. Détail module par module en §2.

---

## 1. Ce qui motive le changement

- **Liste des projets (eDoc)** : "New Projet - Mtbc Buche" — le projet dont viennent les 8 tables de référence déjà construites — n'est qu'un projet parmi au moins 9. "Futur Palais de Justice de Paris" en est un autre, séparé.
- **Configuration "Tables Plan" par projet** : deux captures d'écran montrent deux configurations différentes de la section Codification — Phase/Emetteur/Lot/Niveau/Type d'un côté, les mêmes + **Bâtiment** de l'autre. La liste des champs n'est pas fixe d'un projet à l'autre.

Conséquence concrète déjà vérifiée : le document "6.pdf" du corpus de 27 est explicitement un document du projet **Futur Palais de Justice de Paris** (son cartouche l'indique). Le corpus de 27 documents est confirmé comme un **échantillon d'inspiration et de test hétérogène** — pas nécessairement un seul projet eDoc, potentiellement plusieurs. Ce n'est plus un point bloquant : c'est même une bonne nouvelle pour valider la généricité de l'architecture (§0) sur des documents réellement variés, plutôt qu'un biais à corriger.

---

## 2. Architecture — 7 modules

| Module | Rôle | Change par rapport à avant ? |
|---|---|---|
| P0 — Ingestion & routage | Format, page 1 | Inchangé |
| P1 — Préparation | Orientation, rendu image | Inchangé |
| P2 — Détection + extraction générique | Localiser le cartouche, en sortir tous les libellés/valeurs, sans schéma nommé à l'avance | Change de nature — détaillé en §3 |
| P3 — Classification | Ranger chaque (libellé, valeur) brut dans le bon champ cible, selon **la liste de champs requis reçue dans l'appel API** | Nouveau module — détaillé en §4 |
| P4 — Validation | Matching flou contre les tables de référence du champ classé ; règle D11 inchangée | Même logique qu'avant |
| P5 — API REST | Reçoit **le document ET la liste des champs obligatoires à extraire** (fournie par l'appelant — confirmé par Oussama, ça diffère par projet côté eDoc mais ce n'est plus notre problème à déduire) → résultat par champ | Confirmé : pas de logique de détection de projet à construire, l'appelant nous dit quoi chercher |
| P6 — Harnais d'évaluation | Comparatif Tesseract / Mistral, précision par champ | Inchangé dans l'esprit |

Ce qui **ne change pas** : le principe "un lecteur, plusieurs vérificateurs" ; la règle D11 (jamais d'auto-validation sans match, sur aucun champ) ; les deux procédures en test (Tesseract sur page de garde vs Mistral partout, comparées empiriquement) ; les deux familles de documents et leur routage.

Ce qui **est déjà résolu par ce changement**, sans travail supplémentaire : CO_NB n'est plus une question à part. C'est simplement une entrée de plus dans la configuration du projet Mtbc Buche (section Champs personnalisés) — rien de spécial à coder pour lui.

---

## 3. P2 — Détection de cartouche + extraction générique

### Pour Mistral OCR
Le schéma `document_annotation_format` change de forme : au lieu de propriétés nommées (`PHASE`, `EMETTEUR`...), un schéma ouvert, par exemple :
```
{
  "cartouche_trouve": bool,
  "champs": [ { "libelle": string, "valeur": string } ]
}
```
Le `document_annotation_prompt` guide la détection : repérer le bloc d'identification (cartouche), recopier chaque libellé avec sa valeur telle qu'imprimée, sans interpréter ni renommer. C'est un changement de schéma, pas de changement d'outil. `include_blocks` (bounding boxes) reste utile pour vérifier où le modèle a situé le cartouche.

### Pour Tesseract
Plus difficile : Tesseract n'a pas de compréhension sémantique, il ne peut pas "reconnaître un cartouche" comme concept. Détection structurelle nécessaire :
- Détection de grille/tableau via OpenCV (contours ou détection de lignes) — un cartouche est presque toujours une boîte à bordures avec des lignes internes, ce qui le distingue visuellement du reste de la page.
- Une fois la zone candidate trouvée, OCR de cette zone avec `pytesseract` en **`lang='fra+eng'`** (pas seulement `fra`) — le corpus montre des libellés anglais ponctuels ("As indicated", "LEVEL"), puis appariement libellé/valeur par géométrie (colonne gauche = libellé / colonne droite = valeur, ou libellé au-dessus / valeur en dessous selon le gabarit).

**À ne pas sous-estimer** : au moins deux mises en page de cartouche coexistent dans le corpus (libellé à gauche de la valeur sur une ligne ; libellé au-dessus de la valeur sur deux lignes — vu sur le document Palais de Justice). L'heuristique d'appariement doit gérer les deux, ou détecter laquelle s'applique avant d'apparier. C'est un vrai sujet d'itération empirique, pas un détail d'implémentation.

---

## 4. P3 — Classification (nouveau module)

**Mécanisme confirmé par Oussama** : l'appel API fournit le document **et** la liste des champs obligatoires à extraire pour cet appel — c'est l'appelant (eDoc) qui porte la différence entre projets, pas notre pipeline. On n'a donc pas besoin de deviner ou de récupérer une configuration de projet : on reçoit directement quoi chercher.

Entrée : liste de paires (libellé brut, valeur brute, confiance) issues de P2, plus la liste des champs requis pour cet appel (reçue via l'API, pas déduite).
Sortie : chaque paire rangée sur un champ requis si elle correspond, ou classée "hors schéma" sinon (jamais perdue, jamais forcée dans le mauvais champ).

Mécanique : RapidFuzz compare chaque libellé brut aux synonymes connus pour chaque nom de champ **demandé dans cet appel**. Meilleur score au-dessus du seuil → classé. Rien au-dessus du seuil → hors schéma.

Deux usages de RapidFuzz à ne pas confondre dans le code :
- **P3 (classification)** compare un *libellé* (ex. "N° Doc") à une liste de synonymes de champ
- **P4 (validation)** compare une *valeur* (ex. "EX3") à une table de référence

**Conséquence de conception, plus simple que prévu initialement** : `schema_fields.yaml` peut rester **une seule bibliothèque partagée** (nom de champ → synonymes de libellés), pas un fichier par projet — puisque le même nom de champ ("Emetteur", "Bâtiment"...) a probablement le même sens et les mêmes synonymes partout, peu importe le projet qui le demande. Ce que chaque appel API change, c'est **quel sous-ensemble** de cette bibliothèque est actif pour cet appel-là, pas le contenu des synonymes eux-mêmes. À enrichir au fil de l'eau si un nouveau nom de champ apparaît (ex. "Bâtiment" à ajouter dès maintenant, vu dans les captures).

**Confirmé, avec l'exemple qui le précise** : si l'API demande `NUMERO` et `NIVEAU`, et que le document imprime "NUM" et "LEVEL", ce n'est pas une comparaison exacte de chaînes qui doit décider si le champ est trouvé ou `MISSING` — c'est le même mécanisme de correspondance floue (RapidFuzz contre les synonymes de `schema_fields.yaml`) qui s'en charge. Rien de nouveau à construire : c'est exactement ce que ce module fait déjà. Ce qui change, c'est qu'il faut être vigilant à ne **jamais** implémenter cette étape en comparaison stricte (`==`) entre le nom de champ demandé et le libellé lu — ce serait le bug qui ferait passer un champ réellement présent en `MISSING` à tort. Statut `MISSING` réservé au cas où même la correspondance floue ne trouve rien d'assez proche.

Détail utile de l'exemple : "LEVEL" est un libellé anglais pour ce qui correspond à NIVEAU — le corpus contient déjà des indices de ça ("As indicated" en anglais vu sur un document). Le lexique de synonymes doit couvrir le anglais autant que les variantes françaises, pas seulement "N° Doc / N° GED / N° Chrono". Ajusté en §3 (OCR Tesseract) et à garder en tête pour peupler `schema_fields.yaml`.

Configuration confirmée : `config/schema_fields.yaml` (bibliothèque partagée de synonymes, un seul fichier, multilingue) + `config/projects/{project_id}/reference_tables/*.csv` (valeurs acceptées, par projet — le project_id arrive bien dans l'appel API, confirmé). Les 8 CSV déjà produits pour Mtbc Buche n'ont pas besoin d'être refaits — juste rangés sous `config/projects/mtbc_buche/`.

---

## 5. P4 — Validation (reprend l'existant)

Logique identique à ce qui était déjà spécifiée : matching flou contre la table de référence du champ classé, statuts `AUTO_VALIDATED` / `TO_REVIEW` / `MISSING`, règle D11 uniforme (aucun champ n'est un vocabulaire fermé, un non-match déclenche toujours TO_REVIEW, jamais un rejet automatique). **Confirmé** : l'appel API porte le code ou le nom exact du projet, donc P4 sait sans ambiguïté quelle table consulter.

---

## 6. Stack — ce qui s'ajoute ou change de rôle

| Brique | Rôle | Nouveau par rapport à avant ? |
|---|---|---|
| Mistral OCR (`document_annotation_format`) | Détection + extraction générique | Même outil, schéma redessiné (ouvert au lieu de nommé) |
| Tesseract 5 + `pytesseract` | OCR brut sur la zone cartouche détectée | Inchangé |
| **OpenCV — contours / détection de lignes** | **Détection structurelle du cartouche (Tesseract)** | Rôle élargi — avant, juste prétraitement d'image ; maintenant aussi détection de structure |
| RapidFuzz | Classification (libellé→champ) **et** validation (valeur→table) | Même librairie, un usage de plus |
| PyMuPDF | Rendu image, lecture `/Rotate` | Inchangé |
| `config/schema_fields.yaml` + `config/projects/{id}/reference_tables/` | Synonymes de libellés (bibliothèque partagée) + valeurs acceptées (par projet) | Structure affinée — un seul fichier de synonymes, tables de valeurs toujours par projet |

---

## 7. Étapes de travail

| Étape | Durée | Contenu | Sortie |
|---|---|---|---|
| É1 | 2 j | Setup (clé Mistral, Tesseract). **Test décisif prioritaire** : Mistral peut-il détecter et extraire le cartouche en libellés/valeurs génériques, sur 2-3 documents des deux familles ? Conditionne tout le reste | Réponse au test décisif ; environnement prêt |
| É2 | 1 j | P0 (+ identification du projet) + P1 (inchangé) | Tronc d'ingestion prêt |
| É3 | 3–4 j | P2 : schéma générique côté Mistral ; détection structurelle + OCR + appariement côté Tesseract | Paires (libellé, valeur) brutes sur les documents de test, pour les deux moteurs |
| É4 | 2 j | P3 : classification, `schema_fields.yaml` par projet | Champs classés par projet |
| É5 | 1 j | P4 : validation (reprend la logique déjà spécifiée) | Statuts calculés |
| É6 | 1–2 j | P6 : harnais comparatif Tesseract/Mistral (page de garde) | Tableau comparatif chiffré |
| É7 | 1–2 j | Itération sur les points faibles mesurés (l'appariement Tesseract en priorité probable) | Précision en hausse |
| É8 | 1 j | P5 : API REST (+ paramètre projet) | POST/GET fonctionnels |
| É9 | 1–2 j | Rapport + démo | Livrables prêts |

**Total estimé : 13–17 jours ouvrés** — plus que le plan précédent (10–13 j), parce que la détection de cartouche et la classification sont deux problèmes réels de plus à résoudre. En échange : une architecture qui fonctionne sur n'importe quel projet eDoc, pas seulement celui qu'on a sous la main. Si le temps presse, voir §9.

---

## 8. Questions ouvertes — toutes résolues côté architecture

- ~~Les 27 documents appartiennent-ils à un ou plusieurs projets ?~~ Confirmé : corpus d'inspiration/test hétérogène, pas un blocage.
- ~~Config récupérée par API ou export manuel ?~~ Confirmé : l'appel API fournit le document, la liste des champs requis, et le projet.
- ~~L'appel porte-t-il un identifiant de projet ?~~ Confirmé : oui, code ou nom exact, transmis avec chaque appel. P4 peut sélectionner la bonne table sans ambiguïté.
- ~~Comment gérer l'écart entre le nom de champ demandé et le libellé réellement imprimé (NUMERO vs NUM, NIVEAU vs LEVEL) ?~~ Confirmé : c'est le rôle de la correspondance floue déjà prévue en P3, pas un mécanisme à ajouter — la seule vigilance est de ne jamais implémenter cette étape en comparaison stricte.

Plus aucune question ne bloque le début du codage. Restent, indépendantes de ce pivot et à traiter en cours de route plutôt qu'avant É1 : Titre4, Indice par défaut, convention NUMERO (zéros de tête), champ DATE à ajouter au schéma ou non.

---

## 9. Option pour tenir les délais : scope MVP

L'architecture ci-dessus est conçue pour être générique, mais rien n'oblige à la tester sur dix projets pour la valider. Proposition : construire la classification comme un module paramétré par projet (aucun nom de champ codé en dur dans la logique), mais ne réellement peupler et tester que le(s) projet(s) pour lesquels on a des données maintenant — Mtbc Buche à minima. La capacité à gérer un projet totalement nouveau se justifie par la conception, pas par un test exhaustif sur chaque projet eDoc existant — c'est un argument de rapport à part entière : architecture conçue et démontrée générique, testée sur N projets réels.

---

## 10. Risques

| Risque | Impact | Parade |
|---|---|---|
| Détection de cartouche échoue (zone ratée ou mauvaise zone choisie) | Rien à classer, ou du bruit classé à tort | Test décisif dès É1 avant d'investir dans la suite ; garder la lecture "page entière" comme repli si la détection échoue trop souvent |
| Appariement libellé/valeur ambigu (plusieurs mises en page coexistent) | Valeurs mal associées à leur libellé, silencieusement | Détecter le type de mise en page avant d'apparier ; mesurer spécifiquement ce taux d'erreur en É6 |
| Seuil de correspondance floue (P3) mal calibré entre nom de champ demandé et libellé lu | Faux `MISSING` (champ présent mais non reconnu) ou fausse classification (libellé mal apparié) | Mesurer les deux types d'erreur séparément en É6 ; lexique multilingue (fra/eng) dès le départ |
| Configuration projet incomplète (synonymes manquants) | Libellés valides classés à tort en "hors schéma" | Enrichissement itératif du lexique partagé, jamais un blocage |
| Tables de référence actuelles spécifiques à Mtbc Buche, corpus de test plus large | Mesure de précision faussée si on valide un document d'un autre projet contre les mauvaises valeurs | Restreindre l'évaluation par table de référence aux documents dont le projet d'origine est connu et correspond ; ne pas valider "à l'aveugle" |

---

## 11. Rapport

S'ajoute un chapitre : justification du choix architectural détection + classification vs schéma fixe, avec l'exemple concret ayant motivé le changement (deux projets eDoc, deux configurations de champs différentes observées). Argument utile pour la suite (PFE) : le pipeline ne sert pas qu'un seul projet, il sert l'application eDoc dans son ensemblee.
