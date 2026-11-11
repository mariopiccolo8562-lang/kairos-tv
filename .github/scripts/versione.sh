#!/usr/bin/env bash
# Decide numero di versione e tipo di compilazione: di prova oppure release. Lo usa .github/workflows/*.yml.
#
# La release parte in due modi:
#   1. da un tag vX.Y.Z creato a mano (come sempre);
#   2. DA SOLA, quando su main il file versione.txt contiene una versione non ancora pubblicata e più alta dell'ultima:
#      il tag vX.Y.Z e la release li crea questa stessa compilazione. Per pubblicare basta cambiare quel numero su main.
# In tutti gli altri casi (altri rami, versione già pubblicata, numero non valido o più basso, elenco dei tag non letto)
# si compila solo l'APK di prova 0.0.<numero della compilazione>: nel dubbio non si pubblica.
#
# Legge: GITHUB_REF, GITHUB_EVENT_NAME, GITHUB_RUN_NUMBER, VERSION_FILE (predefinito versione.txt), RELEASE_BRANCH (main),
#        TAGS (i tag che esistono già, uno per riga) e TAGS_OK (1 = elenco letto davvero).
# Scrive in $GITHUB_ENV: VERSION_NAME, VERSION_CODE, IS_RELEASE (1 oppure vuoto), REL_TAG.
set -euo pipefail
FILE="${VERSION_FILE:-versione.txt}"
REF="${GITHUB_REF:-}"; EVENT="${GITHUB_EVENT_NAME:-}"; RUN="${GITHUB_RUN_NUMBER:-0}"
OUT="${GITHUB_ENV:-/dev/stdout}"
code() { echo $(( 10#$1 * 10000 + 10#$2 * 100 + 10#$3 )); }   # es. 1.2.3 → 10203

V="0.0.$RUN"; C="$RUN"; REL=""; TAG=""                          # compilazione di prova
if [[ "$REF" =~ ^refs/tags/[vV]?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  V="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  C=$(code "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
  REL=1; TAG="${REF#refs/tags/}"
elif [[ "$REF" == "refs/heads/${RELEASE_BRANCH:-main}" && "$EVENT" == "push" && -f "$FILE" ]]; then
  F="$(tr -d ' \t\r\n' < "$FILE")"
  if [[ "$F" =~ ^([0-9]+)\.([0-9]{1,2})\.([0-9]{1,2})$ ]]; then
    WANT=$(code "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
    MAX=0; HAVE=""
    while IFS= read -r t; do
      [[ "$t" =~ ^[vV]?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || continue
      c=$(code "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
      if (( c > MAX )); then MAX=$c; fi
      if (( c == WANT )); then HAVE=1; fi
    done <<< "${TAGS:-}"
    if [[ "${TAGS_OK:-}" != "1" ]]; then
      echo "::warning::Non ho potuto leggere i tag già pubblicati: nel dubbio non pubblico ($FILE = $F). Compilazione di prova"
    elif [[ -n "$HAVE" ]]; then
      echo "::notice::$FILE = $F: versione già pubblicata. Compilazione di prova"
    elif (( WANT <= MAX )); then
      echo "::warning::$FILE = $F è più bassa dell'ultima versione pubblicata: non pubblico. Compilazione di prova"
    else
      V="$F"; C=$WANT; REL=1; TAG="v$F"
      echo "::notice::$FILE = $F: versione nuova, pubblico la release $TAG"
    fi
  else
    echo "::warning::$FILE non valido («$F»): serve N.N.N, al massimo due cifre dopo ogni punto. Compilazione di prova"
  fi
fi
{ echo "VERSION_NAME=$V"; echo "VERSION_CODE=$C"; echo "IS_RELEASE=$REL"; echo "REL_TAG=$TAG"; } >> "$OUT"
