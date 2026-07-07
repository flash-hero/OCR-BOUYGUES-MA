# PFA Bouygues — Moteur OCR eDoc

Remplissage automatique du formulaire eDoc « Nouveau document » à partir du document déposé.
Quand un utilisateur dépose un plan technique, le système lit son **cartouche** (le bloc d'identification du plan) et pré-remplit les champs du formulaire à sa place. L'utilisateur vérifie et corrige l'exception, au lieu de tout saisir.

**Moteur de lecture : Mistral OCR.** Stack : Python, FastAPI.

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
| Texte du cartouche minuscule sur un plan géant | Stratégie de repli : crop des 4 coins en haute résolution |
| Libellés différents selon les clients (N° Doc / N° GED / N° Chrono) | Lexique de synonymes par champ, en configuration |
| Documents scannés, parfois pivotés | Redressement systématique avant lecture (`/Rotate` + correction locale si besoin) |
| Hallucination sur champ codé | Aucune auto-validation sans match en table de référence |
| Nom de fichier trompeur | Jamais utilisé comme source de vérité ; seul le contenu fait foi |
| Formats multiples (PDF, Word, Excel, IFC) | PDF → OCR ; Word/Excel → lecture directe ; IFC → pas de cartouche |

---

## Deux stratégies d'extraction

- **Stratégie A (défaut)** — page 1 entière envoyée au lecteur. La plus simple, validée en premier.
- **Stratégie B (repli plans denses)** — si A échoue sur les grands formats, crop des 4 coins en haute résolution. Le choix se tranche par la mesure, pas par opinion.

---

## Leviers en réserve (si la précision l'exige)

Mistral OCR reste le seul lecteur ; on peut ajouter des *vérificateurs* :
- **Texte natif du PDF** comme contre-lecture gratuite (accord → confiance renforcée).
- **Double lecture** (deux formulations, comparaison des réponses).
- **Enrichissement du lexique** par client — modification de configuration, sans toucher au code.

---

## Structure du dépôt (cible)

```
edoc-ocr/
├── src/
│   ├── ingestion/     # réception, page 1, orientation
│   ├── extraction/    # client Mistral OCR, schéma d'annotation, stratégies A/B, cache
│   ├── validation/    # matching tables de référence, normalisation, statuts
│   ├── api/           # FastAPI, schémas Pydantic, jobs
│   └── evaluation/    # harnais de mesure, métriques
├── config/
│   ├── schema_fields.yaml   # champs + synonymes de libellés
│   ├── thresholds.yaml      # seuils de match, pondérations de confiance
│   └── reference_tables/    # tables de référence eDoc (CSV)
├── data/
│   ├── raw/                 # documents du dataset
│   ├── annotations.xlsx     # vérité terrain
│   └── cache/               # réponses brutes de l'OCR
└── tests/
```

**Conventions :** code, commentaires et identifiants en anglais ; documentation en français.

---

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

---

## Points à confirmer avec l'encadrant

- **Titre4** — présent sur le formulaire, absent du schéma discuté : à extraire ?
- **Champ Projet** — pré-rempli par le contexte projet : ne vient pas du cartouche (à confirmer).
- **Indice** — défaut « A » : la valeur lue doit-elle le remplacer ?
- **Fichier source** — lecture sur la copie PDF quand natif + PDF déposés (à confirmer).
- **Format du numéro** — convention unique de zéros de tête (« 085 » vs « 000439 »).
- **Libellés en hypothèse** — Spécialité/Discipline → LOT, Localisation → ZONE, DOC → TYPE : à valider avant activation.
