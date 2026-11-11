#!/usr/bin/env bash
# Prove della regola di versione.sh (le esegue ogni compilazione): bash .github/scripts/versione_test.sh .github/scripts/versione.sh
S="$1"; fails=0; D=$(mktemp -d)
run() { # nome, atteso "V|C|REL|TAG", poi variabili
  local name="$1" want="$2"; shift 2
  local out; out=$(env -i PATH="$PATH" GITHUB_ENV="$D/env" "$@" bash -c ': > "$GITHUB_ENV"; bash "$0" >/dev/null 2>&1; rc=$?; . "$GITHUB_ENV" 2>/dev/null; echo "$VERSION_NAME|$VERSION_CODE|$IS_RELEASE|$REL_TAG|rc=$rc"' "$S")
  if [ "$out" = "$want|rc=0" ]; then echo "ok   $name"; else echo "FAIL $name: $out (atteso $want)"; fails=$((fails+1)); fi
}
f() { printf '%s' "$1" > "$D/versione.txt"; echo "$D/versione.txt"; }
TAGS=$'v1.7.2\nv1.7.3\nv1.7.4\nprova-1\nlatest'
M="GITHUB_REF=refs/heads/main GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=321 TAGS_OK=1"
run "tag v1.7.5: release (come prima)" "1.7.5|10705|1|v1.7.5" GITHUB_REF=refs/tags/v1.7.5 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9
run "tag V3.0.2: release, il tag resta quello scritto" "3.0.2|30002|1|V3.0.2" GITHUB_REF=refs/tags/V3.0.2 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9
run "tag 3.4.5 senza v: release" "3.4.5|30405|1|3.4.5" GITHUB_REF=refs/tags/3.4.5 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9
run "tag con zeri davanti (v1.08.09): non e' ottale" "1.08.09|10809|1|v1.08.09" GITHUB_REF=refs/tags/v1.08.09 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9
run "tag che non e' una versione (prova-1): prova" "0.0.9|9||" GITHUB_REF=refs/tags/prova-1 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9
run "main, versione.txt nuova (1.7.5): release v1.7.5" "1.7.5|10705|1|v1.7.5" $M VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
run "main, versione.txt con a capo e spazi: release" "1.7.5|10705|1|v1.7.5" $M VERSION_FILE="$(f $' 1.7.5 \r\n')" TAGS="$TAGS"
run "main, versione gia' pubblicata (1.7.4): prova" "0.0.321|321||" $M VERSION_FILE="$(f 1.7.4)" TAGS="$TAGS"
run "main, versione piu' bassa dell'ultima (1.6.9): prova" "0.0.321|321||" $M VERSION_FILE="$(f 1.6.9)" TAGS="$TAGS"
run "main, gia' pubblicata con tag V maiuscola: prova" "0.0.321|321||" $M VERSION_FILE="$(f 3.0.2)" TAGS=$'V3.0.2\n3.0.1'
run "main, gia' pubblicata con tag senza v: prova" "0.0.321|321||" $M VERSION_FILE="$(f 3.0.1)" TAGS=$'3.0.1'
run "main, salto di versione (2.0.0): release" "2.0.0|20000|1|v2.0.0" $M VERSION_FILE="$(f 2.0.0)" TAGS="$TAGS"
run "main, nessun tag ancora (prima versione): release" "1.0.0|10000|1|v1.0.0" $M VERSION_FILE="$(f 1.0.0)" TAGS=""
run "main, elenco dei tag non letto: prova (nel dubbio non pubblica)" "0.0.321|321||" GITHUB_REF=refs/heads/main GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=321 VERSION_FILE="$(f 1.7.5)" TAGS=""
run "main, TAGS_OK=0: prova" "0.0.321|321||" GITHUB_REF=refs/heads/main GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=321 TAGS_OK=0 VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
for bad in "v1.7.5" "1.7" "1.7.5.1" "1.7.x" "" "1.7.100" "1.100.0" "1,7,5" "1.7.5-beta"; do
  run "main, versione.txt non valido («$bad»): prova" "0.0.321|321||" $M VERSION_FILE="$(f "$bad")" TAGS="$TAGS"
done
run "main, file che non c'e': prova" "0.0.321|321||" $M VERSION_FILE="$D/manca.txt" TAGS="$TAGS"
run "altro ramo con versione nuova: prova" "0.0.321|321||" GITHUB_REF=refs/heads/prova GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=321 TAGS_OK=1 VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
run "ramo che si chiama main-2: prova" "0.0.321|321||" GITHUB_REF=refs/heads/main-2 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=321 TAGS_OK=1 VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
run "main avviato a mano (workflow_dispatch): prova" "0.0.321|321||" GITHUB_REF=refs/heads/main GITHUB_EVENT_NAME=workflow_dispatch GITHUB_RUN_NUMBER=321 TAGS_OK=1 VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
run "richiesta di modifica (pull_request): prova" "0.0.321|321||" GITHUB_REF=refs/pull/7/merge GITHUB_EVENT_NAME=pull_request GITHUB_RUN_NUMBER=321 TAGS_OK=1 VERSION_FILE="$(f 1.7.5)" TAGS="$TAGS"
run "tag: vale anche se versione.txt dice altro" "1.7.6|10706|1|v1.7.6" GITHUB_REF=refs/tags/v1.7.6 GITHUB_EVENT_NAME=push GITHUB_RUN_NUMBER=9 VERSION_FILE="$(f 9.9.9)" TAGS="$TAGS" TAGS_OK=1
run "main, tag con ^{} e righe strane nell'elenco" "1.7.5|10705|1|v1.7.5" $M VERSION_FILE="$(f 1.7.5)" TAGS=$'v1.7.4\n\nv1.7.4^{}\nrefs/tags/x\nv1.7'
rm -rf "$D"; [ $fails -eq 0 ] && echo "regola: tutto ok" || { echo "regola: $fails FALLITE"; exit 1; }
