#!/bin/sh
# Compiles UpdateInfo.java (the part of the in-app updater that reads a GitHub release) with a stand-in for org.json and
# runs Check.java on it (JDK only).   tests/update/run.sh [work folder]
set -e
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:-$repo/obj/update-test}
rm -rf "$out" && mkdir -p "$out/classes"
javac -nowarn -d "$out/classes" $(find "$repo/tests/companion-view/stubs/org" -name '*.java') "$here/src/io/github/morerokk/mhgurecomp/Check.java" \
    "$repo/android/java/io/github/morerokk/mhgurecomp/UpdateInfo.java" 2>&1 | grep -v "^Picked" || true
java -cp "$out/classes" io.github.morerokk.mhgurecomp.Check 2>&1 | grep -v "^Picked"
