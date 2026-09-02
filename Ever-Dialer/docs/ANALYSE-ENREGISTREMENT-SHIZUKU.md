# 🎙️ Analyse : comment Ever Dialer capture chaque appel (sans annonce) avec Shizuku

> Analyse du code source open-source de [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) (GPL-3.0), le moteur intégré par Ever Dialer pour son "Ever Call Recorder".

---

## 1. Le problème à résoudre

Depuis Android 9/10, une app normale **ne peut plus** :
- ouvrir un `AudioRecord` sur la source `VOICE_CALL` (audio réel de la ligne téléphonique) → protégé par `android.permission.CAPTURE_AUDIO_OUTPUT`, permission *signature|privileged* ;
- utiliser l'API officielle d'enregistrement d'appel sans que le système joue l'annonce « Cet appel est enregistré » et bippe.

La parade : exécuter du code avec l'identité **shell (UID 2000)** — celle d'ADB — car Android lui accorde des permissions spéciales via `/system/etc/permissions/platform.xml` :

```xml
<assign-permission name="android.permission.CAPTURE_AUDIO_OUTPUT" uid="shell" />
<assign-permission name="android.permission.MODIFY_AUDIO_ROUTING" uid="shell" />
```

C'est exactement ce que fait **Shizuku** : il expose un daemon ADB local auquel les apps peuvent demander un processus « UserService ». C'est là que tout se joue.

---

## 2. Architecture globale (trouvée dans le code)

```
┌─────────────────────────── Processus App (non privilégié) ───────────────────────────┐
│                                                                                       │
│  Détection d'appel                        Enregistrement                              │
│  ┌──────────────────────────┐             ┌─────────────────────────────────────┐      │
│  │ InCallService (Android12+)│  décide →  │ RecordingForegroundService          │      │
│  │   ou PhoneStateReceiver  │            │  └ AudioRecordingEngine             │      │
│  └──────────────────────────┘            │    ├ ScrcpyClient (lit le pipe)     │      │
│                                          │    └ ScrcpyAudioMuxer (.opus/.mka)  │      │
└──────────────────────────────────────────┼─────────────────────────────────────┘───────
                          AIDL / Shizuku   │  ParcelFileDescriptor (read-end)
┌──────────────────────────────────────────▼─────────────────────────────────────┐
│                Processus Shell Shizuku — UID 2000 (ou root = 0)                 │
│  ShellService (IShellService.Stub)                                              │
│   ├ ShellAudioPipeline                                                          │
│   │   ├ LocalServerSocket("scrcpy_<random>")                                    │
│   │   ├ lance : CLASSPATH=scrcpy-server.jar                                     │
│   │   │         app_process / com.genymobile.scrcpy.Server ...                  │
│   │   │           audio_source=voice-call  audio_codec=opus                     │
│   │   └ coroutine relay : socket ──► pipe write-end                             │
│   └ ShellCommandExecutor : grantAppOp / grantRole (auto-permissions)            │
└─────────────────────────────────────────────────────────────────────────────────┘
```

Fichiers clés analysés :

| Fichier | Rôle |
|---|---|
| `services/shell/ShellService.kt` | Service AIDL lancé par Shizuku dans le processus shell |
| `services/shell/ShellAudioPipeline.kt` | Crée le pipe + socket, lance scrcpy-server, relaie les octets |
| `services/shell/ShellCommandExecutor.kt` | S'auto-accorde les AppOps/rôles nécessaires |
| `services/callDetection/*` | Détecte automatiquement CHAQUE appel (2 modes) |
| `services/recording/AudioRecordingEngine.kt` | Lit le flux côté app, mux en fichier audio |
| `integrations/scrcpy/ScrcpyConfig.kt` | Construit la ligne de commande scrcpy |
| `integrations/scrcpy/ScrcpyAudioSource.kt` | Liste des sources audio (`voice-call`, uplink, downlink…) |

---

## 3. Étape par étape : la capture d'un appel

### Étape 1 — Détecter automatiquement chaque appel

Deux modes (`CallDetectionMode.kt`) :

**Mode `InCallService` (Android 12+, préféré)** — `callDetection/incall/InCallService.kt`
- L'app se déclare comme service Telecom **non-UI** (`IN_CALL_SERVICE_TYPE_NON_UI`).
- Normalement il faut être le dialer par défaut… mais depuis Android 12, `InCallController` du framework accepte aussi les apps ayant l'AppOps **`android:manage_ongoing_calls`** (simple condition OR dans AOSP).
- Shizuku s'auto-accorde cette AppOps (chaîne d'escalade dans le code : AppOp paquet → AppOp UID → rôles `COMPANION_DEVICE_WATCH/GLASSES` en repli pour les ROMs chinoises strictes).
- Résultat : le système **lie le service à lui-même et pousse chaque appel** (`onCallAdded`).
- Quand l'appel passe en `STATE_ACTIVE` : extraction du numéro (normalisé), direction (`DIRECTION_INCOMING/OUTGOING`), nom de l'appelant, package source → objet `RawCallData`.
- `RecordingDecisionEngine.executeDecisionPipeline()` applique les règles (enregistrement auto, filtres contacts, appels tiers autorisés ou non…) puis démarre le service d'enregistrement.

**Mode `PhoneState` (Android 11+)** — `phoneState/PhoneStateReceiver.kt`
- Classique : permissions `READ_PHONE_STATE` + `READ_CALL_LOG`, écoute `CALL_STATE_OFFHOOK` / `IDLE`, géré par `PhoneStateSessionManager`.

### Étape 2 — Préparer le binaire privilégié (scrcpy-server)

- L'app embarque un **scrcpy-server.jar** (le serveur de scrcpy, utilisé ici uniquement en mode `video=false audio=true`) et l'extrait vers le seul stockage lisible à la fois par l'app ET par shell : `/storage/emulated/0/Android/data/<pkg>/files/`.
- Vérification **SHA-256** du JAR avant exécution (anti-manipulation, cf. `ServerExtractor.verifyServerHash`).

### Étape 3 — Lancer la capture dans le processus shell

`ShellService.startRecording()` (AIDL) → `ShellAudioPipeline.startCapture()` :

1. Création d'un **pipe noyau** (`ParcelFileDescriptor.createPipe()`) : write-end gardé côté shell, read-end renvoyé à l'app.
2. Ouverture d'un **LocalServerSocket** abstrait au nom aléatoire `scrcpy_xxxxxxxx`.
3. Lancement du processus fils :

```
CLASSPATH=/storage/emulated/0/Android/data/<pkg>/files/scrcpy-3.x-server.jar \
app_process / com.genymobile.scrcpy.Server 3.x \
  log_level=info video=false audio=true control=false \
  tunnel_forward=false send_dummy_byte=false scid=<socket> \
  audio_source=voice-call audio_codec=opus audio_bit_rate=16000
```

4. Comme ce processus tourne en **UID 2000**, son `AudioRecord` sur la source **`VOICE_CALL`** est accepté par AudioFlinger grâce à `CAPTURE_AUDIO_OUTPUT`. Il capte **uplink + downlink** (les deux voix), encode en Opus/AAC (48 kHz stéréo) et écrit le flux sur le socket.

Sources disponibles dans `ScrcpyAudioSource.kt` :
- `voice-call` → les deux voix ✅ (défaut idéal)
- `voice-call-uplink` / `voice-call-downlink` → votre micro / votre interlocuteur séparément
- `mic-voice-communication` → repli matériel (fonctionne partout mais qualité variable)

### Étape 4 — Renvoyer l'audio à l'app « normalement non privilégiée »

- Une coroutine relay copie en boucle : `socket scrcpy` → `pipe write-end`.
- Le **read-end** du pipe est retourné à l'app via Binder (un fd peut traverser les processus).
- Côté app, `AudioRecordingEngine` lit le flux, vérifie le FourCC du codec, et mux chaque paquet Opus/AAC dans le fichier final (SAF, nom formaté avec numéro/date/direction). Pause possible (`isPaused` jette les paquets).

### Étape 5 — Fin d'appel = fin d'enregistrement propre

`onCallRemoved` / état `DISCONNECTED` → `endRecordingSession()` → `stopCapture()` :
ordre soigné dans le code : flag atomique → `process.destroy()` + 2 s de grâce pour les derniers octets → attente du relay → fermeture du write-end **en dernier** → muxer finalise l'en-tête du fichier.

---

## 4. Pourquoi « sans annonce » ?

Aucune API publique d'enregistrement n'est utilisée : pas de `MediaProjection`, pas de role `CALL_SCREENING` officiel avec beep, pas de bouton d'enregistrement du système. La voix est captée **directement à la source (AudioFlinger/HAL)** par un processus shell. L'annonce de Google ne se déclenche que sur l'API officielle → elle n'existe jamais ici. Seul indice visible : la notification discrète de l'app elle-même.

⚠️ **Rappel légal** : selon les pays, enregistrer un appel exige le consentement des parties (RGPD/art. 226-1 en France). À utiliser uniquement sur vos propres appareils et dans le respect des lois locales.

---

## 5. Résumé en une phrase

> Shizuku prête à l'app l'identité **shell (UID 2000)** qui possède `CAPTURE_AUDIO_OUTPUT` ; dans ce contexte, un petit serveur scrcpy lancé via `app_process` ouvre un `AudioRecord(VOICE_CALL)` qui capte les deux voix de chaque appel détecté automatiquement par l'`InCallService`, puis l'audio encodé en Opus transite par un socket Unix → pipe → fichier `.opus/.mka`, sans jamais toucher l'API d'enregistrement officielle donc sans aucune annonce.
