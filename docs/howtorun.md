# Comment lancer le moteur — guide pratique

Ce guide explique, étape par étape, comment installer, configurer et lancer le moteur OCR sur ta
machine. Aucune connaissance préalable de Java n'est nécessaire pour suivre ces étapes.

> **Rappel important** : ce qui est lancé ici est un **outil de test** (`SmokeTestRunner`), pas
> l'application finale. Il lit des PDF depuis un dossier et affiche le résultat à l'écran, pour
> vérifier "à la main" que l'extraction fonctionne bien. Il n'y a **pas encore** de vraie API REST
> à appeler depuis un navigateur ou un autre programme — voir `understand.md` §4 et §10 pour le
> contexte complet.

---

## 1. Ce qu'il faut avoir installé avant de commencer

| Outil | Version requise | Comment vérifier |
|---|---|---|
| JDK (Java) | **17** exactement (ni plus ancien, ni plus récent) | `java -version` |
| Maven | 3.9 ou plus récent | `mvn -version` |
| Une clé API Mistral | — | https://console.mistral.ai/ |
| PowerShell | déjà présent sur Windows | — |

Si tu n'as pas encore ces outils, installe-les avant de continuer. Le script de l'étape 2 te dira
précisément ce qui manque.

---

## 2. Vérifier que tout est prêt : `check_setup.ps1`

Ouvre un terminal PowerShell **à la racine du projet** et lance :

```powershell
./scripts/check_setup.ps1
```

Ce script vérifie, sans jamais planter avec un message d'erreur incompréhensible :

- que `java` et `javac` sont bien en version 17,
- que Maven est installé et que les dépendances du projet peuvent être téléchargées,
- que la clé API Mistral est bien renseignée (voir étape 3),
- que des documents de test sont présents dans `data/samples/`.

À la fin, tu verras soit :
- `[OK]` en vert partout → tu peux passer à l'étape suivante,
- `[WARN]` en jaune → ça peut marcher quand même, mais regarde le détail,
- `[FAIL]` en rouge → il faut corriger avant de continuer (le message te dit quoi faire).

Astuce : si tu veux juste vérifier rapidement sans re-télécharger les dépendances Maven à chaque
fois, utilise `./scripts/check_setup.ps1 -SkipMaven`.

---

## 3. Configurer ta clé API Mistral

1. Copie le modèle de configuration :

   ```powershell
   Copy-Item .env.example .env
   ```

2. Ouvre le fichier `.env` (nouvellement créé) avec un éditeur de texte, et remplis la ligne :

   ```
   MISTRAL_API_KEY=ta-clé-ici
   ```

3. **Ne partage jamais ce fichier `.env`** (ne le mets pas sur GitHub, ne l'envoie pas par message).
   Il est déjà ignoré automatiquement par git — tu n'as rien de spécial à faire pour ça, c'est déjà
   configuré dans le projet.

> Le fichier `.env` est lu automatiquement au démarrage du programme. Si tu préfères, tu peux aussi
> définir `MISTRAL_API_KEY` comme une vraie variable d'environnement système — elle sera alors
> utilisée en priorité sur le `.env`.

### Cas particulier : utiliser Azure AI Foundry au lieu de "La Plateforme" Mistral

Si ta clé vient d'un déploiement Azure AI Foundry plutôt que du site officiel Mistral, ajoute ces
lignes dans `.env` (en plus de la clé) :

```dotenv
MISTRAL_OCR_BASE_URL=https://<ta-ressource>.services.ai.azure.com
MISTRAL_OCR_PATH=/providers/mistral/azure/ocr?api-version=2024-05-01-preview
MISTRAL_OCR_MODEL=<nom-du-déploiement-azure>
```

Aucune modification de code n'est nécessaire : tout se règle dans `.env`.

---

## 4. Déposer des documents de test

Place tes fichiers PDF dans le dossier `data/samples/`. Le programme lira **tous** les fichiers
`.pdf` présents dans ce dossier, un par un.

> Le projet en contient déjà 27 par défaut (`1.pdf` à `27.pdf`), utilisés pendant le développement.
> Tu peux les laisser, les remplacer, ou en ajouter d'autres — le programme s'adapte automatiquement
> au contenu du dossier.

---

## 5. Lancer le test décisif : `run_smoke.ps1`

C'est la commande principale pour voir le moteur en action :

```powershell
./scripts/run_smoke.ps1
```

Ce que ça fait, concrètement :

1. Compile le projet (si besoin) et démarre le programme en "mode test" (appelé `profil smoke` —
   dans ce mode, aucun serveur web n'est démarré, c'est juste un script qui tourne et s'arrête).
2. Pour **chaque** PDF trouvé dans `data/samples/`, le programme :
   - regarde s'il s'agit d'un document standard (A4/A3) ou d'un très grand plan,
   - lit le cartouche (en une ou deux passes selon le cas — voir `understand.md` §7),
   - affiche à l'écran toutes les paires libellé/valeur trouvées.
3. À la fin, affiche un **bilan** : combien de documents traités, combien en erreur, combien ont eu
   besoin d'un traitement spécial.

**Exemple de sortie que tu peux voir à l'écran :**

```
Document : 16.pdf  (5725 Ko)
Mode           : 2 passes (grand format) — zone retenue : bottom-right  (tentatives passe 2 : 1)
cartoucheFound : true
Paires libelle/valeur (13) :
   - Affaire                    : ...
   - Phase                      : EXE
   - Emetteur                   : CPI
   - Lot                        : 003
   - Indice                     : G
   ...
```

À la toute fin du programme :

```
=== Bilan : 27 traite(s), 0 en erreur, 0 a decouper en tuiles, 0 a verifier ===
Cache : 52 reponse(s) servie(s) depuis le cache (aucun cout API), 0 appel(s) reseau.
```

---

## 6. Le cache : comprendre ce qui coûte de l'argent (ou pas)

Chaque appel réel à Mistral consomme du crédit API. Un **cache** (une mémoire sur le disque, dans le
dossier `.ocr-cache/`) évite de rappeler Mistral pour une question déjà posée exactement à
l'identique.

- **Relancer `./scripts/run_smoke.ps1` une deuxième fois sans rien changer** → quasiment 0 appel réseau
  (tout vient du cache), donc quasiment gratuit.
- **Si tu modifies un document, ou le code qui décide de la consigne envoyée à Mistral** (le prompt,
  la résolution de l'image...) → le cache pour ce document précis se recalcule automatiquement (c'est
  normal, pas un bug).

### Désactiver le cache (par exemple pour un test de fiabilité)

```powershell
mvn -q -DskipTests spring-boot:run "-Dspring-boot.run.profiles=smoke" "-Dmistral.ocr.cache-enabled=false"
```

### Vider complètement le cache

Supprime simplement le dossier `.ocr-cache/` — il sera recréé automatiquement au prochain lancement.

---

## 7. Lancer les tests automatiques (sans appeler Mistral)

Pour vérifier que le code fonctionne correctement, sans consommer le moindre appel API :

```powershell
mvn test
```

Ces tests (29 au total) tournent en quelques secondes et vérifient chaque petit morceau du programme
séparément (voir `understand.md` §6, section "Les tests automatiques"). Tu peux — et tu **dois** —
lancer cette commande après **chaque** modification du code, avant de considérer que le changement
est bon.

---

## 8. Options avancées (diagnostic)

### Tester sur un dossier de documents différent

```powershell
mvn -q -DskipTests spring-boot:run "-Dspring-boot.run.profiles=smoke" "-Dedoc.samples-dir=chemin/vers/un/autre/dossier"
```

### Forcer une zone de découpage précise (diagnostic uniquement)

Pour vérifier "est-ce que le découpage marche, indépendamment de la localisation ?", tu peux forcer
manuellement le coin utilisé pour tous les grands plans (sans passer par la Passe 1) :

```powershell
mvn -q -DskipTests spring-boot:run "-Dspring-boot.run.profiles=smoke" "-Dedoc.force-corner=bottom-right"
```

Valeurs possibles : `top-left`, `top-center`, `top-right`, `left`, `center`, `right`, `bottom-left`,
`bottom-center`, `bottom-right`. **À ne jamais utiliser en usage normal** — c'est un outil de
diagnostic, pas un réglage de production (le principe du projet est justement de ne jamais fixer une
position à l'avance, voir `understand.md` §5 Étape 2).

---

## 9. Problèmes fréquents

| Symptôme | Cause probable | Solution |
|---|---|---|
| `[ARRET] Aucune cle API Mistral` | Le fichier `.env` n'existe pas ou est vide | Refaire l'étape 3 |
| `[ARRET] Dossier d'exemples introuvable` | `data/samples/` n'existe pas | Créer le dossier et y mettre des PDF |
| `[ARRET] Aucun PDF dans data/samples/` | Le dossier existe mais est vide | Y déposer au moins un fichier `.pdf` |
| `Mistral OCR a répondu 400 ... too_many_pages` | Ne devrait plus arriver (corrigé), mais si ça arrive : document corrompu ou format inattendu | Vérifier que le PDF s'ouvre normalement dans un lecteur PDF classique |
| Le programme est très lent | Normal au premier lancement (cache vide, tout doit être calculé) ou beaucoup de documents/grands plans | Relancer une deuxième fois : le cache accélère fortement les lancements suivants |
| `javac introuvable` | Un JRE est installé mais pas le JDK complet | Installer le JDK 17 complet (pas juste le JRE) |

---

## 10. Résumé express (si tu as déjà tout configuré une fois)

```powershell
./scripts/check_setup.ps1   # vérifier que tout va bien (optionnel si déjà fait récemment)
./scripts/run_smoke.ps1     # lancer le test sur tous les PDF de data/samples/
mvn test                    # lancer les tests automatiques (aucun appel réseau)
```
