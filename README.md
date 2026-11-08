# Kairos TV

App IPTV **nativa in stile Netflix** per **Fire TV, Android TV, Google TV e telefoni**, pensata per essere **fluida anche sulle chiavette datate** (da Android 5.1).
Logo **blu** (schermo TV con la K e il tasto play). Si installa **accanto** a Kairos Pro e Kairos Lite (pacchetto `com.kairos.tv`) e usa lo **stesso pannello Kairos**: stessi clienti, stesso codice di attivazione `KX..-....`.

![logo](docs/logo-512.png)

## Perché è nativa

La prima versione era una pagina web dentro l'app (come Kairos Pro) e sulle chiavette si bloccava. Questa è scritta con le viste Android classiche, come Kairos Lite (stesso motore, già collaudato sulle chiavette vecchie), ma con la grafica stile Netflix:
- liste su disco (SQLite): anche 100.000 titoli non pesano sulla memoria;
- ogni riga legge solo i suoi primi 20 titoli e solo quando sta per comparire;
- immagini caricate alla misura mostrata, in pausa mentre si scorre col tasto tenuto premuto;
- **modalità leggera automatica** sulle chiavette con poca memoria (niente zoom, immagini più piccole);
- nessuna WebView, nessuna animazione, nessuna sfocatura; l'anteprima dei canali parte solo con OK.

## Cosa c'è

- **Home stile Netflix**: schede in alto (Home, Film, Serie TV, In diretta, La mia lista), riquadro grande con un titolo in evidenza (fisso), righe di locandine: Continua a guardare, La mia lista, aggiunti di recente, categorie. «Vedi tutti» in fondo a ogni riga apre la categoria intera.
- **Film** e **Serie TV** con tutte le categorie a righe, scheda del titolo, stagioni ed episodi, episodio successivo automatico.
- **TV in diretta** con guida adesso/dopo e **anteprima leggerissima**: **OK una volta** il canale parte nel riquadro (versione più leggera del canale, al massimo 480p), **OK di nuovo** si apre lo schermo intero. Mentre scorri non parte niente. Si spegne da Impostazioni → Anteprima canali.
- Ricerca, La mia lista (tieni premuto OK su una locandina), profili (anche bambini).
- Lettore **ExoPlayer** con qualità adattiva.
- Dal pannello: attivazione e scadenza, playlist M3U/Xtream, cambio DNS, *Disconnetti*, visioni contemporanee, permessi, profili, collegamento di altre TV, messaggi, VPN, *Errori applicazione*. In *Utenti online* la versione compare come `tv 2.0.0`.
- **Aggiornamenti automatici** dalle release di questo repository.

Non ci sono, di proposito: VLC, registrazioni, multi-schermo, speed test, assistente IA, add-on, trailer.

## Qualità dell'immagine (versioni dello stesso canale 4K / FHD / HD / SD)

Con la linea buona la qualità non si tocca, **anche sulle chiavette da 1 GB** (regole in `QualityPolicy.kt`, provate in `QualityPolicyTest`):

- **Il canale parte nella versione scelta.** Il dispositivo toglie solo il 4K, che non mostra: le chiavette deboli non lo decodificano e sugli schermi non 4K si parte dal FHD, che sembra uguale. Prima la chiavetta da 1 GB faceva partire tutto al massimo in HD (o in SD) anche con internet buono.
- **La linea abbassa la qualità solo con prove recenti:** blocchi su almeno 2 canali nell'ultima mezz'ora (solo in memoria) oppure banda misurata da meno di 2 ore sotto i 4 Mbps (SD solo sotto i 2,5 Mbps). Prima un giudizio valeva 12 ore e una banda vecchia poteva far partire tutto in SD per sempre.
- **Immagine «a scatti» con l'audio che va** (meno del 35% dei fotogrammi per 6 s): ricarica, versione più leggera, formato alternativo; sulle chiavette da 1 GB niente decodifica software oltre la SD. Se succede su 2 canali diversi in 7 giorni (decodifica hardware), i canali partono un gradino sotto per 7 giorni, poi si riprova.
- **Risalita** dopo 6 minuti senza blocchi verso la versione piena per il dispositivo. «Leggerissima»: canali in HD, come prima.
- Il dispositivo debole tiene solo i buffer più piccoli (memoria); i flussi HLS a più qualità seguono la linea, non la chiavetta.
- Anteprima nella lista canali: i canali FHD e 4K in HD se la playlist ce l'ha (prima l'SD).

## Messa in funzione (una volta sola)

1. **Il repository deve essere pubblico**: altrimenti Downloader e l'aggiornamento automatico non possono scaricare l'APK.
2. **Chiave di firma**: in *Settings → Secrets and variables → Actions → New repository secret* crea `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS` (file `LEGGIMI-SEGRETI.txt`).
   > ⚠️ Se perdi la chiave di firma non potrai più aggiornare l'app sui dispositivi dei clienti.
3. Facoltativo, in *Variables*: `TV_APP_NAME` (nome sotto l'icona, predefinito «Kairos TV»), `KAIROS_SERVER_URL` (se il pannello cambia indirizzo; predefinito quello su workers.dev).

## Pubblicare una versione

*Releases → Draft a new release* → tag `v2.0.0` (poi `v2.0.1`, `v2.1.0`…) → *Publish*. Dopo pochi minuti alla release sono allegati `kairos-tv.apk` e `kairos-tv.json`.

- Link fisso per **Downloader** e per lo store:
  `https://github.com/mariopiccolo8562-lang/kairos-tv/releases/latest/download/kairos-tv.apk`
- Le app installate leggono `kairos-tv.json` dell'ultima release e propongono l'aggiornamento da sole.

Numerazione: correzioni sulla terza cifra, cambiamenti grossi sulla versione maggiore.

## Struttura

| Cartella | Cosa contiene |
|---|---|
| `app/src/main/java/com/kairos/tv/` | Codice: `BrowseActivity` (Home/Film/Serie stile Netflix), `LiveActivity`, `DetailActivity`, `PlayerActivity`, `Sync`/`Db` (liste su disco), `Panel` (pannello Kairos) |
| `app/src/main/res/` | Grafica: colori Netflix, layout, logo |
| `docs/` | Logo (SVG e PNG) |
| `.github/workflows/` | Compilazione e pubblicazione, preparazione dei segreti |
