# LibreHU (appli téléphone)

Appli **téléphone** de LibreHU : règle l'autoradio et les applis LibreHU depuis le téléphone, montre la batterie et
les valeurs OBD de la voiture, et les met dans **Android Auto** (réglages rapides compris).

**C'est LibreHU-service (sur l'autoradio) qui décrit les pages de réglages** ; cette appli les dessine sans les
connaître : un nouveau réglage arrive sans mise à jour du téléphone. Protocole et API :
[LibreHU-service, docs/bridge.md](https://github.com/LibreHU/LibreHU-service/blob/main/docs/bridge.md).

## Télécharger

Dernière version (Android 8 et plus) :
[LibreHU-Bridge-main.apk](https://github.com/LibreHU/LibreHU-bridge-app/releases/download/latest-main/LibreHU-Bridge-main.apk)
— aussi en QR code dans l'assistant de premier démarrage de LibreHU Launcher.

## Associer

- **QR code** : sur l'autoradio, LibreHU-service → Bluetooth → Téléphone → *Appli téléphone LibreHU* → *Associer un
  téléphone* ; dans l'appli, *Scanner le QR code*. Le QR code contient l'empreinte de la clé de l'autoradio, un
  jeton à usage unique (5 minutes), ses adresses Wi-Fi et Bluetooth.
- **Bluetooth** : téléphone déjà appairé en Bluetooth avec l'autoradio → *Bluetooth* → choisir l'autoradio, puis
  *Autoriser* sur l'autoradio.

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
grille). Seuls les hôtes Android Auto signés par Google sont acceptés. Une appli installée hors Play Store
n'apparaît dans Android Auto qu'avec **Sources inconnues** activé dans ses paramètres développeur.

## Écrans

- Autoradios associés (oublier), association par QR code ou Bluetooth.
- Un autoradio : état de la liaison (Wi-Fi / Bluetooth), voiture (batterie, OBD, valeurs constructeur), touches
  (précédent, lecture, suivant, volume, muet), pages de réglages (Autoradio, Son LibreHU, Lumières, OBD et
  batterie, et celles des autres applis LibreHU).
- Une page : interrupteurs, curseurs, choix, boutons (avec confirmation), informations, envoyés tout de suite.

## Compiler

`./gradlew :app:assembleRelease` (JDK 17+, Android SDK). La CI publie `latest-main` à chaque push ; la clé de
signature LibreHU vient des secrets de la CI (`LIBREHU_KEYSTORE_B64`), sinon l'APK est signé avec la clé de debug.

Bibliothèques : Jetpack Compose, Car App Library, kotlinx.serialization, zxing-android-embedded (Apache 2.0).
