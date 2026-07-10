# Documents d'exemple (non versionnés)

Déposez ici les 3 PDF représentatifs du corpus, tels quels :

| Fichier  | Description                                             |
|----------|---------------------------------------------------------|
| `6.pdf`  | page de garde A4, **texte natif**                       |
| `16.pdf` | plan dense A0, **texte natif**                          |
| `25.pdf` | page de garde A4 **scannée** (sans couche texte)        |

Ces fichiers sont **ignorés par git** (voir `.gitignore` : `data/samples/*.pdf`) car ce sont
des plans internes potentiellement confidentiels. Seuls ce `README.md` et `.gitkeep` sont versionnés.

Le test décisif (`run_smoke.ps1`) lit **tous** les `*.pdf` de ce dossier, quel que soit leur nom.
