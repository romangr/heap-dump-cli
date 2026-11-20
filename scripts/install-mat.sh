#!/usr/bin/env bash
# Installs the Eclipse Memory Analyzer (MAT) core plugin JARs into the local Maven repository (~/.m2),
# so Gradle can resolve them via mavenLocal().
#
# MAT is not published to Maven Central. We download the official p2 repository archive, verify its
# SHA-512 against a pinned value, and install the core plugins under the (unofficial) coordinates
#   org.eclipse.mat:<bundle-symbolic-name>:<MAT_VERSION>
#
# MAT is licensed under EPL-2.0 (https://www.eclipse.org/legal/epl-2.0/). Source: https://github.com/eclipse-mat/mat
#
# Usage: scripts/install-mat.sh [--force]
set -euo pipefail

MAT_VERSION="1.17.0"
MAT_BUILD="202606011933"
MAT_SHA512="9c303416f1ec9279e70af05dcd2aa2390a53ea051d69af02dd5d3b769261d5c5604cd6587d04e3857824e030a583f9d3ad449b48a53f022fc013dfff595e461f"
MAT_FILE="/mat/${MAT_VERSION}/MemoryAnalyzer-${MAT_VERSION}.${MAT_BUILD}.zip"
BUNDLES=(org.eclipse.mat.report org.eclipse.mat.api org.eclipse.mat.parser org.eclipse.mat.hprof)

M2_REPO="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
GROUP_DIR="$M2_REPO/org/eclipse/mat"
FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ $FORCE -eq 0 ]]; then
  missing=0
  for b in "${BUNDLES[@]}"; do
    [[ -f "$GROUP_DIR/$b/$MAT_VERSION/$b-$MAT_VERSION.jar" ]] || missing=1
  done
  if [[ $missing -eq 0 ]]; then
    echo "MAT $MAT_VERSION already installed in $GROUP_DIR (use --force to reinstall)"
    exit 0
  fi
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading MAT $MAT_VERSION ($MAT_BUILD)..."
curl -fL --retry 3 -o "$work/mat.zip" "https://download.eclipse.org${MAT_FILE}" \
  || curl -fL --retry 3 -o "$work/mat.zip" "https://www.eclipse.org/downloads/download.php?file=${MAT_FILE}&r=1"

actual="$(shasum -a 512 "$work/mat.zip" | awk '{print $1}')"
if [[ "$actual" != "$MAT_SHA512" ]]; then
  echo "SHA-512 mismatch for MAT archive" >&2
  echo "  expected: $MAT_SHA512" >&2
  echo "  actual:   $actual" >&2
  exit 1
fi
echo "Checksum OK"

mkdir -p "$work/x"
for b in "${BUNDLES[@]}"; do
  unzip -q -o "$work/mat.zip" "plugins/${b}_${MAT_VERSION}.${MAT_BUILD}.jar" -d "$work/x"
  dir="$GROUP_DIR/$b/$MAT_VERSION"
  mkdir -p "$dir"
  cp "$work/x/plugins/${b}_${MAT_VERSION}.${MAT_BUILD}.jar" "$dir/$b-$MAT_VERSION.jar"
  cat > "$dir/$b-$MAT_VERSION.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.eclipse.mat</groupId>
  <artifactId>$b</artifactId>
  <version>$MAT_VERSION</version>
  <description>Eclipse Memory Analyzer $MAT_VERSION.$MAT_BUILD, installed locally by heap-dump-cli</description>
  <licenses><license><name>EPL-2.0</name><url>https://www.eclipse.org/legal/epl-2.0/</url></license></licenses>
</project>
EOF
  echo "Installed org.eclipse.mat:$b:$MAT_VERSION"
done
