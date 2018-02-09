#!/usr/bin/env bash
set -euo pipefail

project="$1"
version="$2"
jar_path="$3"

# Don't load build.boot
# make Clojure files in src/ available
# load analysis namespace
# run analysis on supplied jar

echo "Running build for $project v$version"
echo "Jar: $jar_path"

BOOT_JVM_OPTIONS=-Dclojure.spec.check-asserts=true

boot \
    -B \
    --source-paths src \
    --resource-paths resources \
    --init "(require 'cljdoc.analysis.task)" \
    cljdoc.analysis.task/copy-jar-contents --jar "$jar_path" \
    cljdoc.analysis.task/analyze -p "$project" -v "$version" \
    sift --include "^cljdoc-edn" \
    target -d /tmp/cljdoc-target/
