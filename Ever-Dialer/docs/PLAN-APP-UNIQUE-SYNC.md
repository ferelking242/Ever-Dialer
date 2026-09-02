# 📱 Plan — Ever Dialer « App Unique » avec sync P2P A→B

> Fork de base : `ferelking242/Ever-Dialer` · Objectif final : **UN seul APK** = Dialer + Enregistreur + Shizuku embarqué + Synchronisation directe Tecno (A) → Samsung S21 (B), sans aucun serveur ni app supplémentaire.

---

## État des lieux (audit du 25/08/2026)

| Constat | Conséquence |
|---|---|
| Le dialer (`com.coolappstore.everdialer.by.svhp`) ne contient **aucun** code d'enregistrement (packages : `controller`, `view`, `widget`, `liquidglass`, `modal`) | L'enregistrement est délégué à une **app séparée** « Ever Call Recorder » (rebrand de ShizuCallRecorder). Il faut la **fusionner** dans l'APK |
| Le README impose l'app **Shizuku externe** (+ fork thedjchi pour le Watchdog) | Il faut **embarquer** le démarrage/la surveillance du serveur Shizuku dans notre app |
| Aucune fonction de synchronisation | Il faut créer un **module de sync P2P** de toutes pièces |

Architecture cible :

```
┌──────────────────── UN SEUL APK : Ever Dialer+ ────────────────────┐
│  UI Dialer existante                                               │
│   ├─ Appels / Contacts / Journal                                   │
│   ├─ Module Recorder (code ShizuCallRecorder vendored, GPL-3)      │
│   ├─ Module PrivilegedRuntime                                      │
│   │    ├─ Pairage ADB local façon LADB (Android 11+)               │
│   │    ├─ Lanceur serveur Shizuku (app_process)                    │
│   │    └─ Watchdog intégré (foreground service léger)              │
│   └─ Module SyncEngine                                             │
│        ├─ Découverte mDNS + TLS direct                             │
│        ├─ Manifest/diff/chunks reprenables                         │
│        └─ Sync métadonnées (Room → deltas JSON)                    │
└────────────────────────────────────────────────────────────────────┘
          │ WiFi commun                                    ▲
          ▼                                                │
   Tecno (A) ── fichiers .opus/.mka + infos appel ──► Samsung S21 (B)
```

---

## Phase 0 — Mise en place du chantier (½ journée)

1. Cloner `https://github.com/ferelking242/Ever-Dialer` dans ce workspace.
2. Vérifier que le projet compile (`./gradlew assembleDebug`) et identifier :
   - où l'app externe « Ever Call Recorder » est invoquée (intent/package name),
   - le point d'accroche post-appel (`controller/CallService.kt`),
   - la version min/target SDK et les dépendances.
3. Créer une branche `feature/app-unique`.

## Phase 1 — Fusionner l'enregistreur dans l'app (2–4 jours)

1. Vendor le code source de ShizuCallRecorder (GPL-3.0 ✔ compatible) comme module Gradle `:recorder` ou sous-package `...everdialer.recorder`.
2. Remplacer les invocations par intent vers l'app externe par des **appels directs internes** :
   - `RecordingDecisionEngine.executeDecisionPipeline()` appelé depuis le hook post-appel du dialer,
   - `RecordingForegroundService` déclaré dans NOTRE manifest,
   - suppression des écrans d'onboarding redondants (permissions gérées par l'app hôte).
3. Résultat : dialer + enregistreur = **1 APK**, plus aucune dépendance à l'app séparée.

## Phase 2 — Shizuku embarqué (5–10 jours, la partie dure)

Objectif : plus jamais besoin d'installer l'app Shizuku (même pas le fork thedjchi).

**Voie recommandée : ADB local embarqué (fonctionne sans root, Android 11+)**

1. Ajouter une lib client ADB : `com.tananaev:adblib` ou libadb-android (celle de LADB).
2. Écran « Privilèges système » (one-shot) :
   - guider l'activation du **débogage sans fil**,
   - l'utilisateur saisit le **code de jumelage** affiché sur le téléphone lui-même → l'app se paire à **elle-même** via localhost/mDNS,
   - clés ADB chiffrées dans **Android Keystore** → reconnexion automatique ensuite.
3. Démarrage du serveur via la connexion ADB locale :
   ```
   push shizuku-server.jar → /data/local/tmp/
   CLASSPATH=/data/local/tmp/shizuku.jar app_process / moe.shizuku.server.ShizukuServer
   ```
   (le jar est celui du fork `thedjchi/Shizuku`, qui intègre déjà le watchdog côté serveur).
4. **Watchdog intégré** : foreground service discret qui ping le binder Shizuku ; si mort → relance auto via la connexion ADB locale stockée. Reprend l'idée « Watchdog » du fork thedjchi mais DANS notre app.
5. Consommation : dépendances officielles `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider` — tout le pipeline recorder existant continue de fonctionner tel quel.
6. Fallbacks : root (`su`) si dispo ; app Shizuku externe toujours supportée si présente.

⚠️ **Limite honnête à tester dès la Phase 2.0** : après un reboot, certains constructeurs coupent le débogage sans fil (port fermé). Sur beaucoup de ROMs il persiste (reconnexion auto OK via mDNS + clés) ; sur HiOS/Tecno ça se teste. Si échec : l'app détecte l'état mort et propose un re-pairing en 20 secondes. C'est LA contrainte principale du « zéro app externe » — aucun moyen magique de contourner sans root.

## Phase 3 — Sync P2P sans serveur A→B (5–7 jours)

1. **Pairage initial** des deux téléphones : QR code contenant empreinte de clé + PSK générés, stockés en Keystore des deux côtés.
2. **Découverte automatique** : NSD/mDNS `_everdial._tcp` quand les deux sont sur le même WiFi (le S21 n'a pas de SIM → WiFi suffit ✔).
3. **Transfert** : socket TLS direct (certificats échangés au pairage), protocole par lots :
   - `MANIFEST` = liste {nom fichier, SHA-256, métadonnées appel},
   - diff → envoi uniquement des fichiers manquants, **chunks reprenables**,
   - ACK + accusé de purge optionnel côté B.
4. **Données synchronisées** = fichiers audio (.opus/.mka) **+ toutes les infos d'appel** : numéro, nom du contact, direction (entrant/sortant), durée, horodatage, SIM utilisée, notes liées au contact. Stockées Room côté A → deltas JSON envoyés avant les fichiers.
5. **Déclencheurs** : fin d'appel (hook Phase 1), changement de réseau, WorkManager avec backoff exponentiel + reprise après interruption.
6. **UI sur B** : section « 📞 Appels du téléphone A » dans Ever Dialer — journal distant complet + lecteur audio intégré + recherche par contact/date.
7. Plus tard (hors même WiFi) : Nearby Connections (BT/WiFi Direct) ou WebRTC DataChannel + STUN public — même protocole applicatif par-dessus.

## Phase 4 — Robustesse batterie/réseau (1 jour)

- Demande d'exemption d'optimisation batterie (légitime : c'est ton propre téléphone),
- foreground service type `dataSync` pendant les transferts, file d'attente persistante.

---

## Licence & garde-fous

- Ever Dialer, ShizuCallRecorder, Shizuku : GPL-3.0/Apache → ton fork **reste GPL-3.0**, sources publiques obligatoires si tu distribues l'APK.
- Usage : tes propres appareils uniquement. Pas de mode furtif, pas de masquage d'icône — l'app reste visible sur A (c'est un dialer de toute façon).

## Estimation totale

| Phase | Durée |
|---|---|
| 0. Setup | ½ j |
| 1. Fusion enregistreur | 2–4 j |
| 2. Shizuku embarqué | 5–10 j |
| 3. Sync P2P MVP | 5–7 j |
| 4. Robustesse | 1 j |
| **Total** | **≈ 3–5 semaines** |

## Prochain pas concret

Cloner le fork dans le workspace et démarrer la Phase 0 (audit de build), puis enchaîner Phase 1.
