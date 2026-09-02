#!/usr/bin/env bash
# Regenerates the checked-in expected sources under src/test/resources/goldens.
#
# Run from the repository root after changing the generator, then read the diff.
# A change to a golden file is a change to the API embedders write against, so
# the diff is the review.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

resources="bindgen-processor/src/test/resources"
classpath="$(mktemp)"

mvn -q -pl bindgen-processor install -DskipTests
mvn -q -pl bindgen-processor dependency:build-classpath \
    -Dmdep.outputFile="$classpath" -DincludeScope=test

cp="bindgen-processor/target/classes:$(cat "$classpath")"

# Each host is compiled on its own, because two of the example worlds are both
# named hello-world and would otherwise generate the same file twice.
for host in "$resources"/*Host.java; do
    name="$(basename "$host" .java)"
    work="$(mktemp -d)"
    mkdir -p "$work/endive/testing" "$work/out"
    cp -r "$resources/wit" "$work/wit"
    cp "$host" "$work/endive/testing/"

    javac -cp "$cp:$work" -proc:full \
        -processor run.endive.cm.bindgen.BindgenProcessor \
        -s "$work/out" -d "$work/out" "$work/endive/testing/$name.java"

    mkdir -p "$resources/goldens/$name"
    rm -f "$resources/goldens/$name"/*.java
    (
        cd "$work/out/endive/testing"
        find . -name '*.java' | while read -r file; do
            flat="$(echo "${file#./}" | tr '/' '_')"
            cp "$file" "$root/$resources/goldens/$name/$flat"
        done
    )
    rm -rf "$work"
    echo "regenerated $name"
done

rm -f "$classpath"
