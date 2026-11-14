#!/bin/sh
# Compiles CompanionView.java against stand-ins for Android's drawing classes and runs Check.java on it (JDK only).
#   tests/companion-view/run.sh [output folder for the pictures]
set -e
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:-$repo/obj/companion-view}
classes=$out/classes
rm -rf "$classes" && mkdir -p "$classes"
log=$out/run.log
javac -nowarn -d "$classes" $(find "$here/stubs" "$here/src" -name '*.java') \
    "$repo/android/java/io/github/morerokk/mhgurecomp/CompanionView.java" \
    "$repo/android/java/io/github/morerokk/mhgurecomp/AreaMap.java" > "$log" 2>&1 || { grep -v "^Picked up" "$log"; exit 1; }
status=0
java -Djava.awt.headless=true -cp "$classes" io.github.morerokk.mhgurecomp.Check "$repo/android/assets" "$out" > "$log" 2>&1 || status=1
grep -v "^Picked up" "$log"
exit $status
