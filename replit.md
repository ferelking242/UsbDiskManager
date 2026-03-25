# USB OTG Disk Manager — Android App (100% Kotlin)

Projet Android natif — aucun Node.js, aucun TypeScript.

## Architecture

```
/ (racine = racine du repo GitHub)
├── app/                         # Module principal (UI, ViewModels, Navigation)
├── core/                        # Modèles, utilitaires, entités
├── usb/                         # Détection USB OTG, permissions, formatage
├── storage/                     # Opérations fichiers (SAF + accès direct)
├── ps2/                         # Module PS2 Studio (conversion, téléchargement, fusion)
├── gradle/
│   ├── libs.versions.toml       # Catalogue de dépendances
│   └── wrapper/
├── .github/
│   └── workflows/
│       └── build.yml            # CI GitHub Actions → APK signé arm64-v8a
├── gradlew / gradlew.bat
├── build.gradle.kts
├── settings.gradle.kts
└── replit.md
```

## Stack technique

| Composant | Technologie |
|---|---|
| Langage | Kotlin 2.1.0 |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture + MVVM |
| DI | Hilt 2.54 |
| Async | Coroutines + StateFlow |
| USB | UsbManager API |
| Fichiers | SAF (Storage Access Framework) + accès direct |
| Navigation | Navigation Compose |
| CI/CD | GitHub Actions → APK arm64-v8a signé |

## Structure dossiers (sur Android)

```
sdcard/usbdiskmanager/PS2Manager/
  ├── ISO/        ← fichiers ISO scannés
  ├── UL/         ← fichiers UL convertis (défaut)
  ├── ART/        ← pochettes de jeux
  └── Downloads/  ← ISO téléchargés
```
Sur USB : fichiers UL toujours à la racine (imposé par OPL).

## applicationId

`com.diskforge.usbmanager` (namespace code : `com.usbdiskmanager`)

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease     # nécessite keystore
```

## Fonctionnalités PS2 Studio

- **Dock flottant** : toujours visible, pill animée, pas d'auto-masquage, mode immersif (sans barre Android)
- **Tab bar** style Telegram : Jeux / Fusionner CFG / Télécharger
- **Multi-sélection** : appui long pour activer, "Tout sélectionner", conversion groupée
- **Destination de conversion** : Défaut (interne) / USB (racine) / Personnalisé
- **Vérification FAT32** avant écriture USB — avertissement si non-FAT32
- **Fusion ul.cfg** : merge de deux fichiers sans casser les entrées existantes
- **Download manager** : HTTP avec reprise (Range), pause, resume, retry

## GitHub Actions — Secrets

| Secret | Valeur |
|---|---|
| `KEYSTORE_BASE64` | Keystore encodé en base64 |
| `KEYSTORE_PASSWORD` | `ferelONDONGO1631@` |
| `KEY_ALIAS` | `ferelONDONGO1631@` |
| `KEY_PASSWORD` | `ferelONDONGO1631@` |

## Modules

- **:core** — DiskDevice, FileItem, Extensions, ShellResult
- **:usb** — UsbDeviceRepository (interface + impl), UsbBenchmarkManager, Hilt DI
- **:storage** — FileRepository (interface + impl), SAF + DocumentFile, Hilt DI
- **:ps2** — IsoScanner, IsoEngine, UlCfgManager, DownloadEngine, FilesystemChecker, Ps2ViewModel, Ps2StudioScreen, UlCfgMergerScreen, Ps2DownloadScreen
- **:app** — MainActivity (mode immersif), AppNavHost (dock fixe), Screens, Theme, Navigation
