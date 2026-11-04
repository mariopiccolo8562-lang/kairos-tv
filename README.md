# Kairos TV

App IPTV **stile Netflix** per **Fire TV, Android TV, Google TV e telefoni**, pensata per essere fluida anche sulle **chiavette datate**.
Ha la stessa grafica di Kairos Pro, ma è **più leggera** e ha il suo **logo blu** (schermo TV con la K e il tasto play). Si installa **accanto** a Kairos Pro e Kairos Lite (pacchetto `com.kairos.tv`) e usa lo **stesso pannello Kairos**: stessi clienti, stesso codice di attivazione `KX..-....`.

![logo](docs/logo-512.png)

## Cosa c'è

- Home stile Netflix con riquadro grande, righe e Top 10, «Continua a guardare», «Già visto», La mia lista
- Film, Serie (stagioni ed episodi, episodio successivo automatico), TV in diretta con anteprima, Guida TV, Ricerca, promemoria dei programmi, archivio TV
- Lettore **ExoPlayer** con recupero automatico e qualità adattiva (passa da solo alla versione più leggera del canale se la linea non regge), audio, sottotitoli, salta intro
- Profili (anche bambini) con i 48 avatar, collegamento di altre TV con codice a 6 cifre, messaggi, VPN dal pannello, controllo genitori e PIN, più playlist, segnalazioni e richieste, manutenzione, *Disconnetti*
- **Aggiornamenti automatici** dalle release di questo repository

## Cosa è stato tolto (per la fluidità)

- Lettore **VLC** (APK molto più piccolo e meno memoria): solo ExoPlayer
- Registrazioni, multi-schermo, speed test, assistente IA
- Store add-on (temi, skin, avatar extra), contenuti speciali
- Trailer, età consigliata, cerca per attore (niente richieste a internet mentre scorri)
- Eventi sportivi «Dove vederla» e «Consigliati per te»
- Organizza (tag, coda, rinomina e riordino delle categorie, modifica informazioni), guida TV associata a mano
- Effetti sonori, animazioni di passaggio tra le pagine, sfondo animato e parte pesante dell'intro
- **Impostazioni** tolte: lettore avanzato (motore, buffer, accelerazione, formato flussi, indicatore risoluzione), guida TV avanzata (link EPG personale, spostamento orario), PIN per playlist e ordinamenti, colore, orientamento, schermata iniziale, dimensione testi, loghi automatici. Restano: Account, Profili e dispositivi, Playlist, Lettore (qualità, formato immagine, sottotitoli, episodio successivo), TV in diretta e guida TV, Controllo genitori, Privacy e VPN, Generali, Informazioni.

Nel codice dell'interfaccia (`player/index.html`) le parti tolte sono segnate con `ED_TV` / `.ed-tv` / `TV_FIX`: per rimetterne una basta toglierla da lì.

## Messa in funzione (una volta sola)

1. **Il repository deve essere pubblico**: altrimenti Downloader e l'aggiornamento automatico non possono scaricare l'APK.
2. **Chiave di firma**: in *Settings → Secrets and variables → Actions → New repository secret* crea `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`.
   - Più semplice: usa **gli stessi valori** di Kairos Pro (file `LEGGIMI-SEGRETI.txt` che hai conservato).
   - Oppure: *Actions → «Prepara segreti» → Run workflow*, scarica l'allegato e copia i 4 valori. Conserva `release.jks` e poi elimina l'esecuzione.
   > ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti.
3. Facoltativo, in *Variables*: `TV_APP_NAME` (nome sotto l'icona, predefinito «Kairos TV»), `KAIROS_SERVER_URL` (indirizzo del pannello senza `/panel`; predefinito quello su workers.dev).

## Pubblicare una versione

*Releases → Draft a new release* → tag `v1.0.0` (poi `v1.0.1`, `v1.1.0`…) → *Publish*. Dopo pochi minuti alla release sono allegati `kairos-tv.apk` e `kairos-tv.json`.

- Link fisso per **Downloader** e per lo store:
  `https://github.com/mariopiccolo8562-lang/kairos-tv/releases/latest/download/kairos-tv.apk`
- Il pannello legge `kairos-tv.json` dell'ultima release e le app installate propongono l'aggiornamento da sole. Kairos TV non riceve l'interfaccia della Pro: la sua è dentro l'APK.

Numerazione: correzioni sulla terza cifra (1.0.1, 1.0.2…), cambiamenti grossi sulla versione maggiore (2.0.0).

## Pannello

Nel pannello Kairos (repository della Pro) Kairos TV ha filtro e badge **TV** nei Clienti, la sua voce in *Impostazioni → App e aggiornamenti* (versione pubblicata, link APK, codice Downloader) e una scheda cliente senza le funzioni che qui non ci sono.

## Struttura

| Cartella | Cosa contiene |
|---|---|
| `app/` | App Android (WebView + ExoPlayer nativo, VPN WireGuard) |
| `player/index.html` | Interfaccia stile Netflix (copiata nell'APK dal workflow) |
| `docs/` | Logo (SVG e PNG) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
