# HITIF Video Downloader — Android App

Navigateur Android intégré avec détection automatique de médias, portage direct de l'extension Chrome HITIF.

## Architecture

```
app/
├── BrowserActivity       ← Navigateur WebView principal
├── BrowserViewModel      ← State (liste médias, URL, titre…)
├── MediaPanelFragment    ← Bottom sheet holographique
├── MediaAdapter          ← RecyclerView des médias détectés
│
├── network/
│   ├── MediaDetector     ← Moteur de détection (port extension)
│   ├── JsBridge          ← Script JS injecté dans chaque page
│   └── JsInterface       ← Bridge natif Android ↔ JS
│
├── download/
│   ├── DownloadHelper    ← Enqueue via Android DownloadManager
│   └── DownloadReceiver  ← Notification de fin
│
└── model/
    └── MediaItem         ← Données d'un média détecté
```

## Comment construire

### Prérequis
- Android Studio Hedgehog (2023.1.1) ou plus récent
- JDK 17
- SDK Android 34 (API level 34)

### Étapes

1. **Ouvrir le projet** dans Android Studio :
   `File → Open → hitif_android/`

2. **Polices** (optionnel, téléchargées automatiquement via Google Fonts) :
   Si offline, placer dans `app/src/main/res/font/` :
   - `orbitron_regular.ttf`
   - `rajdhani_medium.ttf`
   Et mettre à jour `res/values/themes.xml` pour référencer les fichiers locaux.

3. **Construire** :
   ```
   ./gradlew assembleDebug
   ```
   APK généré : `app/build/outputs/apk/debug/app-debug.apk`

4. **Installer** sur un appareil / émulateur :
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Fonctionnalités

| Fonctionnalité | Détail |
|---|---|
| Navigateur WebView | User-agent Chrome mobile, JS activé |
| Détection réseau | Interception `shouldInterceptRequest` |
| Détection JS | Script injecté : `<video>`, XHR, fetch, MSE |
| HEAD sniffing | Vérification MIME via OkHttp |
| Formats supportés | MP4, WebM, MKV, HLS (.m3u8), DASH (.mpd), MP3, AAC… |
| Téléchargement | Android DownloadManager → `/Downloads/HITIF/` |
| UI holographique | Orbitron + Rajdhani, palette cyan/magenta, dark |
| Badge compteur | Icône pulsante avec nombre de médias détectés |
| Partage URL | Intent Android standard |

## Permissions requises
- `INTERNET` — navigation web
- `DOWNLOAD_WITHOUT_NOTIFICATION` — téléchargements en arrière-plan
- `WRITE_EXTERNAL_STORAGE` — Android ≤ 9 uniquement
- `POST_NOTIFICATIONS` — notifications Android 13+

## Notes de développement

### Détection de médias
Le `MediaDetector` reprend la logique de l'extension Chrome :
1. **Filtrage par extension URL** (`.mp4`, `.m3u8`, etc.) → immédiat
2. **HEAD sniffing** pour les URLs ambiguës → vérifie le Content-Type
3. **JS injecté** (`JsBridge.INJECT_SCRIPT`) → capture blob:, MSE, XHR/fetch

### Limitations connues
- Les vidéos DRM (Widevine) ne peuvent pas être téléchargées
- Certains sites bloquent les WebViews (détection user-agent)
- Les streams live HLS → téléchargement partiel possible

### Extension vs App
| Extension Chrome | App Android |
|---|---|
| `content_script.js` | `JsBridge.INJECT_SCRIPT` |
| `shouldInterceptRequest` (N/A) | `WebViewClient.shouldInterceptRequest` |
| `chrome.downloads.download()` | `Android DownloadManager` |
| Popup HTML/CSS | `MediaPanelFragment` + XML |
| `background.js` | `DownloadReceiver` |

## Nouvelles fonctionnalités (v1.1)

### 📝 Nommage intelligent (SmartNaming)
Port direct de l'algorithme de l'extension HITIF :
- **Détection Saison/Épisode** : S01E02, Season 1 Episode 2, 1x02, Épisode 5, Ep.5
- **Nettoyage du titre** : supprime "YouTube", "| Netflix", "Regarder en streaming", etc.
- **Qualité** : 4K, 1080p, 720p, HDR détectés et ajoutés au nom
- **Organisation automatique** : `Downloads/HITIF/NomSérie/Season_01/NomSérie_S01E02_1080p.mp4`

### 📚 Historique des téléchargements (Room DB)
- **Base de données locale** Room persistante entre les sessions
- Affichage groupé par **date**
- Statuts colorés : ✓ COMPLÉTÉ / ✗ ÉCHEC / ⬇ EN COURS / ⏳ EN FILE
- **Relancer** un téléchargement échoué en un tap
- Supprimer par entrée ou tout effacer
- Badge sur le bouton historique (nombre de téléchargements complétés)

### 📺 Téléchargement de saisons
- **Détection automatique** : si ≥ 2 épisodes de la même série sont détectés, le bouton "📺 SAISONS" apparaît
- **Sélection par épisode** : cocher/décocher chaque épisode individuellement
- Boutons "TOUT" / "AUCUN" pour sélection rapide
- **Téléchargement en lot** d'un tap
- Les fichiers sont organisés en sous-dossiers `Saison_XX/`
