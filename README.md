# USB Disk Manager

[![Build APK](https://github.com/ferelking242/UsbDiskManager/actions/workflows/build.yml/badge.svg)](https://github.com/ferelking242/UsbDiskManager/actions/workflows/build.yml)

Application Android native pour gérer les clés USB et disques OTG. Détection automatique, explorateur de fichiers, formatage, benchmark — le tout sans root pour les opérations courantes.

---

## Fonctionnalités

- Détection et montage automatique des périphériques USB OTG
- Explorateur de fichiers complet (copier, coller, déplacer, supprimer, renommer)
- Formatage : FAT32, exFAT, NTFS, EXT4 *(root requis pour certains systèmes de fichiers)*
- Benchmark lecture/écriture en temps réel
- Logs système accessibles directement depuis l'app
- Interface Material 3 avec dark mode

## Stack

| Composant | Choix |
|---|---|
| Langage | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture + MVVM |
| Injection de dépendances | Hilt |
| Async | Coroutines + StateFlow |
| USB bas niveau | libaums |
| Accès fichiers | SAF + MANAGE_EXTERNAL_STORAGE |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

## Structure du projet

```
UsbDiskManager/
├── :core       → Modèles partagés, extensions, constantes
├── :usb        → Détection USB, permissions, benchmark, formatage
├── :storage    → Opérations fichiers via SAF
└── :app        → UI, ViewModels, Navigation, Service background
```

## Build local

**Prérequis** : Android Studio Ladybug (2024.2+), JDK 17, Android SDK API 35

```bash
# Debug
./gradlew :app:assembleDebug

# Release (nécessite une keystore)
./gradlew :app:assembleRelease
```

L'APK se trouve dans `app/build/outputs/apk/`.

## CI/CD

Le workflow GitHub Actions (`.github/workflows/build.yml`) se lance à chaque push sur `master` ou `main` :

1. Build APK arm64-v8a Release
2. Upload en artifact GitHub (30 jours de rétention)
3. Création d'une GitHub Release automatique *(si la keystore est configurée)*

### Configurer la signature automatique

Dans `Settings → Secrets and variables → Actions`, ajouter :

| Secret | Valeur |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i votre_keystore.jks` |
| `KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `KEY_ALIAS` | Alias de la clé |
| `KEY_PASSWORD` | Mot de passe de la clé |

Générer une keystore :

```bash
keytool -genkey -v -keystore usbdiskmanager.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias usbdiskmanager
```

## Permissions

```
MANAGE_EXTERNAL_STORAGE   accès complet aux fichiers
READ/WRITE_EXTERNAL_STORAGE   Android ≤ 12 / ≤ 9
android.hardware.usb.host   mode USB Host (OTG)
FOREGROUND_SERVICE   opérations I/O en arrière-plan
```

## Compatibilité

- Android 8.0 → Android 16
- Appareils avec USB OTG (USB Host mode)
- Architecture arm64-v8a
