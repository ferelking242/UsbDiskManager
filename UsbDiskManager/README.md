# USB Disk Manager

**Application Android native ultra-performante pour la gestion de disques USB OTG.**

[![Build APK](https://github.com/YOUR_USERNAME/UsbDiskManager/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/UsbDiskManager/actions/workflows/build.yml)

---

## Fonctionnalités

| Fonctionnalité | Statut |
|---|---|
| Détection automatique USB OTG | ✅ |
| Informations disque (nom, espace, FS) | ✅ |
| Explorateur de fichiers complet | ✅ |
| Copier / Coller / Déplacer / Supprimer | ✅ |
| Formatage FAT32, exFAT, NTFS, EXT4 | ✅ |
| Montage / Démontage | ✅ |
| Benchmark lecture/écriture | ✅ |
| Logs système temps réel | ✅ |
| UI Material 3 dark mode | ✅ |
| Architecture Clean + MVVM | ✅ |
| Injection Hilt | ✅ |

---

## Stack technique

- **Langage** : Kotlin 2.1 (100% natif)
- **UI** : Jetpack Compose + Material 3
- **Architecture** : Clean Architecture + MVVM
- **DI** : Hilt
- **Async** : Coroutines + Flow
- **USB** : `android.hardware.usb` (UsbManager, UsbDevice)
- **Storage** : SAF (Storage Access Framework) + MANAGE_EXTERNAL_STORAGE
- **Min SDK** : 26 (Android 8.0)
- **Target SDK** : 35 (Android 15)
- **ABI** : arm64-v8a uniquement

## Modules

```
UsbDiskManager/
├── :core       → Modèles de données, utilitaires
├── :usb        → Gestion USB (détection, permissions, benchmark, formatage)
├── :storage    → Opérations fichiers (copie, suppression, déplacement)
└── :app        → UI Compose, ViewModels, Navigation, Service
```

---

## Build

### Prérequis

- Android Studio Ladybug (2024.2+) ou supérieur
- JDK 17+
- Android SDK avec API 35

### Build local

```bash
./gradlew :app:assembleDebug
```

L'APK se trouve dans : `app/build/outputs/apk/debug/`

### Build release avec signature

```bash
export KEYSTORE_PATH=path/to/keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
./gradlew :app:assembleRelease
```

---

## GitHub Actions (CI/CD automatique)

Le workflow `.github/workflows/build.yml` :

1. Se déclenche à chaque push sur `main`
2. Build l'APK arm64-v8a Release
3. Upload l'APK en artifact GitHub
4. Crée une GitHub Release automatique si la keystore est configurée

### Configuration secrets GitHub

Dans `Settings → Secrets → Actions`, ajouter :

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i keystore.jks` → copier le résultat |
| `KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `KEY_ALIAS` | Alias de la clé |
| `KEY_PASSWORD` | Mot de passe de la clé |

### Générer une keystore

```bash
keytool -genkey -v -keystore usbdiskmanager.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias usbdiskmanager
```

---

## Permissions requises

```xml
android.permission.MANAGE_EXTERNAL_STORAGE  <!-- Accès total fichiers -->
android.permission.READ_EXTERNAL_STORAGE    <!-- Android ≤ 12 -->
android.permission.WRITE_EXTERNAL_STORAGE   <!-- Android ≤ 9 -->
android.hardware.usb.host                   <!-- USB OTG -->
android.permission.FOREGROUND_SERVICE       <!-- I/O en background -->
```

---

## Note sur le formatage

Le formatage nécessite un accès root (`mkfs.*`) car Android ne permet pas de formater des partitions
depuis le mode utilisateur standard. L'application :

1. Tente les commandes `mkfs.vfat`, `mkfs.exfat`, `mkfs.ntfs`, `mkfs.ext4`
2. Informe l'utilisateur si root est requis
3. Fonctionne sur certains appareils rooted ou avec accès shell

---

## Librairies utilisées

| Librairie | Usage |
|---|---|
| `libaums` (magnusja, GitHub) | USB Mass Storage bas niveau |
| Hilt | Injection de dépendances |
| Jetpack Compose | UI déclarative |
| Navigation Compose | Navigation |
| Accompanist Permissions | Permissions runtime |
| Timber | Logging |
| Coroutines + Flow | Async/réactif |
| DataStore | Préférences persistantes |

---

## Compatibilité

- Android 8.0 → Android 16
- arm64-v8a uniquement
- OTG : tous appareils supportant USB Host mode
