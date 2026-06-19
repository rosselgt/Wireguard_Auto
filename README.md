# WireGuard AutoToggle

App Android che attiva/disattiva automaticamente un tunnel WireGuard in base
alla rete Wi-Fi a cui sei connesso: su una rete "fidata" (casa, ufficio, ne
puoi aggiungere quante vuoi) il tunnel si spegne; su qualsiasi altra rete (o
senza Wi-Fi) si accende da solo. Usa la libreria ufficiale
`com.wireguard.android:tunnel` (lo stesso backend userspace dell'app
ufficiale WireGuard) — **non serve il root**.

## L'APK si compila da solo, nel cloud, senza che tu installi nulla

Questo progetto contiene un file `.github/workflows/build-apk.yml`: è una
"ricetta" che dice a GitHub di compilare l'APK automaticamente ogni volta
che carichi il codice. Tu non devi installare Android Studio né usare la
riga di comando. Ecco i passaggi (10 minuti, una volta sola):

### 1. Crea un account GitHub (se non l'hai già)

https://github.com/signup — è gratuito.

### 2. Crea un nuovo repository

- Vai su https://github.com/new
- Dagli un nome, es. `wg-autotoggle`
- Lascialo **Private** se preferisci (va benissimo, le Actions funzionano
  anche su repository privati gratuiti)
- Non aggiungere README/licenza dal sito, lascia tutto vuoto
- Crea il repository

### 3. Carica i file di questo pacchetto

Il modo più semplice senza usare `git` da riga di comando:

- Estrai questo zip sul tuo computer.
- Sulla pagina del repository che hai appena creato, clicca
  **"uploading an existing file"** (link che compare nella pagina vuota del
  repo) e trascina dentro **tutte** le cartelle e i file estratti
  (`app/`, `gradle/`, `.github/`, `gradlew`, `gradlew.bat`,
  `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
  `.gitignore`).
- In basso scrivi un messaggio di commit qualsiasi (es. "primo caricamento")
  e clicca **"Commit changes"**.

> Nota: l'upload da browser di GitHub a volte non mostra le cartelle vuote o
> i file che iniziano con un punto (come `.github`). Se non vedi comparire
> la cartella `.github/workflows`, è più sicuro usare **GitHub Desktop**
> (https://desktop.github.com, gratuito, con interfaccia grafica): clona il
> repository vuoto, copia dentro tutti i file estratti dallo zip mantenendo
> le sottocartelle, poi "Commit" e "Push" dal programma. Pochi click, niente
> terminale.

### 4. Guarda l'APK compilarsi

- Vai sulla tab **"Actions"** del repository.
- Dovresti vedere un workflow "Build debug APK" in esecuzione (parte da
  solo dopo il caricamento). Se non parte, clicca su di esso e poi
  **"Run workflow"**.
- Aspetta 3-5 minuti.
- Quando è verde (completato), clicca sull'esecuzione, scorri in basso fino
  ad **"Artifacts"** e scarica **wg-autotoggle-debug-apk** (è uno zip che
  contiene il file `app-debug.apk`).

### 5. Installa l'APK sul telefono

- Copia `app-debug.apk` sul telefono (es. tramite cavo, email, Google
  Drive...).
- Aprilo dal telefono: Android chiederà di confermare l'installazione da
  fonte "sconosciuta" (è normale, perché non viene dal Play Store) — vai su
  conferma/consenti e installa.

D'ora in poi, ogni volta che modifichi qualcosa nel codice e fai un nuovo
"commit" su GitHub, una nuova APK verrà compilata automaticamente nella tab
Actions.

## Uso dell'app

1. Incolla la tua configurazione WireGuard (quella di un file `.conf`
   esportato dal tuo server/provider) nel campo di testo, oppure tocca
   "Importa file .conf". Poi tocca **Salva configurazione**.
2. Aggiungi le reti fidate: connettiti a casa/ufficio e tocca
   **"Aggiungi rete Wi-Fi attuale"**, oppure scrivi a mano il nome (SSID).
   Puoi aggiungerne quante vuoi.
3. Attiva l'interruttore **"Servizio attivo"**: l'app chiederà il permesso
   di posizione (serve solo a leggere il nome della rete Wi-Fi, è un
   requisito di Android, non qualcosa che uso per altro), il permesso
   notifiche, e poi il permesso di creare una VPN.
4. Da quel momento il tunnel si attiva/disattiva da solo in base alla rete:
   su una rete fidata si spegne, appena ti disconnetti da quella rete (altra
   rete, dati mobili, nessuna rete) si riaccende. Trovi anche due pulsanti
   "Attiva ora" / "Disattiva ora" per testarlo a mano.

## Note importanti

- **Ottimizzazione batteria**: su molti telefoni (Xiaomi, Samsung, Huawei,
  OnePlus...) il sistema può terminare i servizi in background per
  risparmiare batteria. Vai in Impostazioni → Batteria → (nome app) e
  scegli "Nessuna restrizione" / disattiva l'ottimizzazione batteria,
  altrimenti il monitoraggio potrebbe fermarsi nel tempo.
- **VPN "Always-on" di sistema**: lascia spenta l'opzione di sistema "VPN
  sempre attiva" per questa app, perché la gestiamo noi via codice.
- L'APK generato non è firmato per il Play Store: va bene per uso personale
  (installazione diretta), non per la pubblicazione.
- Il riconoscimento dell'SSID richiede che la Posizione del telefono sia
  attiva: è una restrizione imposta da Android dalla versione 8 in poi, non
  da questa app.

## Se la build su GitHub Actions fallisce

Le versioni delle librerie usate in `app/build.gradle.kts` sono le più
recenti disponibili su Maven Central al momento della scrittura. Se
`./gradlew assembleDebug` fallisce per una versione non trovata, apri il
log dell'errore nella tab Actions: di solito basta sostituire il numero di
versione indicato con quello più recente segnalato da Maven Central
(es. https://central.sonatype.com/artifact/com.wireguard.android/tunnel).
