# LibreHU (appli téléphone)

Appli **téléphone** de LibreHU : règle l'autoradio, les applis LibreHU et Android depuis le téléphone, montre la
batterie, l'OBD, les températures et le GPS, lance les applis de l'autoradio, le redémarre (normal, recovery,
fastboot), récupère ses journaux, envoie et reçoit des fichiers, et met batterie et OBD dans **Android Auto**
(réglages rapides compris).

**C'est LibreHU-service (sur l'autoradio) qui décrit les pages de réglages** ; cette appli les dessine sans les
connaître : un nouveau réglage arrive sans mise à jour du téléphone. Protocole et API :
[LibreHU-service, docs/bridge.md](https://github.com/LibreHU/LibreHU-service/blob/main/docs/bridge.md).

## Télécharger

Dernière version (Android 8 et plus) :
[LibreHU-Bridge-main.apk](https://github.com/LibreHU/LibreHU-bridge-app/releases/download/latest-main/LibreHU-Bridge-main.apk)
— aussi en QR code dans l'assistant de premier démarrage de LibreHU Launcher.

## Associer

- **QR code** : sur l'autoradio, dans l'assistant de premier démarrage de LibreHU Launcher (*Afficher le QR code
  d'association*) ou LibreHU-service → Bluetooth → Téléphone → *Appli téléphone LibreHU* → *Associer un
  téléphone* ; dans l'appli, *Associer un autoradio* → *QR code*. Le QR code contient l'empreinte de la clé de l'autoradio, un
  jeton à usage unique (5 minutes), ses adresses Wi-Fi et Bluetooth.
- **Bluetooth** : téléphone déjà appairé en Bluetooth avec l'autoradio → *Bluetooth* → choisir l'autoradio, puis
  *Autoriser* sur l'autoradio (notification, carte de LibreHU-service ou assistant de premier démarrage du
  launcher).

Ensuite l'appli se connecte seule : en **Wi-Fi** si le téléphone et l'autoradio sont sur le même réseau (point
d'accès du téléphone ou de l'autoradio), sinon en **Bluetooth**.

## Sécurité

Liaison chiffrée de bout en bout (`protocol/BridgeChannel.kt`, identique à celui du service) : échange de clés ECDH
P-256, l'autoradio signe l'échange (ECDSA P-256) et le téléphone vérifie sa clé avec l'empreinte du QR code ; clés
HKDF-SHA256, trames AES-256-GCM numérotées. Le téléphone prouve sa clé d'accès (32 octets, donnée à l'association)
par HMAC-SHA256 ; elle est gardée chiffrée par le **keystore Android** du téléphone. L'autoradio liste les téléphones
associés et peut les **oublier**.

## Android Auto

Écran **LibreHU** (Car App Library, catégorie IoT) : batterie de la voiture, vitesse, régime, liquide de
refroidissement, carburant, huile, et **Réglages rapides** (tous les interrupteurs des pages de l'autoradio, en
grille). Seuls les hôtes Android Auto signés par Google sont acceptés.

**Pourquoi elle n'apparaît pas dans Android Auto** : une appli faite avec la Car App Library n'est listée que si elle
vient d'une source de confiance comme Google Play. Le réglage développeur « Sources inconnues » d'Android Auto ne vaut
que pour les applis média, messagerie et « à l'arrêt », pas pour celles de la Car App Library
([developer.android.com, Test Android apps for cars](https://developer.android.com/training/cars/testing)).
Android Auto regarde l'installateur enregistré pour l'appli : installée avec Google Play comme installateur, elle est
listée (méthode communautaire, [aa4mg](https://github.com/sn-00-x/aa4mg) ; non garantie pour toutes les versions
d'Android Auto). L'appli affiche son installateur actuel et propose :

- avec root sur le téléphone : **Réinstaller comme Google Play** (`pm install -r -i com.android.vending`) ;
- sans root, depuis un ordinateur, après avoir désinstallé l'appli :
  `adb install -r -i com.android.vending LibreHU-Bridge-main.apk`.

Puis Android Auto → Personnaliser le lanceur → cocher LibreHU. Désinstaller efface l'association : la refaire.

## Écrans

Interface Material 3 aux couleurs LibreHU (bleu, thème clair / sombre du téléphone), transitions entre écrans.

- Autoradios associés (oublier), association par QR code ou Bluetooth.
- Un autoradio, en cinq onglets (barre du bas) :
  - **Accueil** : batterie de la voiture (anneau de charge ou de tension), **GPS** comme sur le launcher (icône et
    couleurs : recherche en jaune, position en vert ; satellites utilisés / visibles sur un anneau, chaque
    constellation, barre de signal de chaque satellite, précision, vitesse, altitude, temps du premier fix ; le GPS
    n'est écouté sur l'autoradio que pendant l'affichage ; **jamais la position**), **système** (température de
    chaque sonde, la plus chaude en tête — le SoC —, en vert / jaune / rouge ; cœurs et fréquence, charge, durée de
    fonctionnement, mémoire, stockage), valeurs OBD et constructeur, touches média ;
  - **Réglages** : toutes les pages de LibreHU-service et de chaque appli LibreHU, rangées par appli, et une
    **recherche dans tous les réglages** (toutes les pages lues à la première lettre) ;
  - **Applis** : les applis de l'autoradio en grille avec leurs icônes, recherche, applis système en option ; un
    appui l'ouvre sur l'autoradio ;
  - **Fichiers** : stockage interne, clés USB / cartes SD (tout le système seulement si l'autoradio l'autorise, avec
    un avertissement), dossiers d'abord ; enregistrer un fichier sur le téléphone (sélecteur d'Android), envoyer un
    fichier du téléphone, renommer, supprimer, nouveau dossier ; transferts par blocs de 512 Kio avec progression,
    annulables ;
  - **Outils** : redémarrer (normal, recovery, fastboot, chacun confirmé), **journaux** (LibreHU, logcat complet,
    noyau) écrits sur l'autoradio puis enregistrés sur le téléphone, **réglages Android avancés** (toutes les clés de
    system / secure / global, recherche, modifier, ajouter, supprimer ; les plus courants sont sur la page
    Autoradio), à propos (empreinte, API, root).
  Un LibreHU-service plus ancien (bridge niveau 1) n'a que les deux premiers onglets, avec un message.
- Une page : interrupteurs, curseurs (valeur écrite comme sur l'autoradio : « +4 dB », « 2.5 s »), choix, champs
  texte, boutons (avec confirmation), informations, envoyés tout de suite ; la page est relue après chaque
  changement (les options qui dépendent d'une autre apparaissent). Le service publie **tous ses réglages** (audio,
  véhicule, recul, vidéo, OBD, Bluetooth, Android Auto avec tous les réglages d'Open Headunit, CarPlay, écran,
  lumières, système, GPS, Tweaks), voir
  [docs/bridge.md](https://github.com/LibreHU/LibreHU-service/blob/main/docs/bridge.md).
- Android Auto : réglages rapides des pages utiles en roulant (Autoradio, Audio, Écran, Lumières) et des applis
  LibreHU.

## Compiler

`./gradlew :app:assembleRelease` (JDK 17+, Android SDK). La CI publie `latest-main` à chaque push ; la clé de
signature LibreHU (la même pour toutes les applis LibreHU) est `keystore/librehu.jks`, ou celle des secrets de la CI
(`LIBREHU_KEYSTORE_B64`) quand ils existent. Les versions publiées avant étaient signées avec une clé de debug qui
changeait à chaque build : désinstaller une fois l'appli avant d'installer celle-ci (l'association est à refaire).

Bibliothèques : Jetpack Compose, Car App Library, kotlinx.serialization, zxing-android-embedded (Apache 2.0).

## Optimisation (R8)

L'APK publié par la CI (`assembleRelease`) est **optimisé par R8** : code et ressources réduits et optimisés, ce qui
rend Jetpack Compose nettement plus fluide ([Compose performance](https://developer.android.com/develop/ui/compose/performance)),
**sans renommage** (`-dontobfuscate` dans `app/proguard-rules.pro`) : les traces des journaux restent lisibles. Les
règles gardent aussi ce qui n'est appelé que par son nom (méthodes natives, helpers root lancés par `app_process`). Si
une fonction plante avec « class / method not found », regarder `usage.txt` dans l'artefact *R8 reports* de la CI (ce
que R8 a retiré) et ajouter une règle `-keep`.
