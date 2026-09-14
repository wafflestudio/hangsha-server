#!/usr/bin/env bash

set -euo pipefail

readonly BASE_REF="${1:?usage: check-flyway-migrations.sh BASE_REF HEAD_REF [RESULT_REF]}"
readonly HEAD_REF="${2:?usage: check-flyway-migrations.sh BASE_REF HEAD_REF [RESULT_REF]}"
readonly RESULT_REF="${3:-$HEAD_REF}"
readonly MIGRATION_DIR="hangsha/src/main/resources/db/migration"
readonly MIGRATION_PATTERN='^V([0-9]+)__[A-Za-z0-9_]+\.sql$'

errors=0

report_error() {
  echo "::error::$1"
  errors=$((errors + 1))
}

normalize_version() {
  local version="$1"
  version="${version#"${version%%[!0]*}"}"
  echo "${version:-0}"
}

declare -A seen_versions=()

while IFS= read -r path; do
  filename="${path##*/}"

  if [[ ! "$filename" =~ $MIGRATION_PATTERN ]]; then
    report_error "Invalid Flyway migration filename: $path (expected V<number>__<description>.sql)"
    continue
  fi

  version="$(normalize_version "${BASH_REMATCH[1]}")"
  if [[ -n "${seen_versions[$version]:-}" ]]; then
    report_error "Duplicate Flyway version V$version: ${seen_versions[$version]} and $path"
  else
    seen_versions[$version]="$path"
  fi
done < <(git ls-tree -r --name-only "$RESULT_REF" -- "$MIGRATION_DIR")

merge_base="$(git merge-base "$BASE_REF" "$HEAD_REF")"

while IFS=$'\t' read -r status path renamed_path; do
  [[ -z "$status" ]] && continue

  if [[ "$status" != "A" ]]; then
    if [[ -n "${renamed_path:-}" ]]; then
      path="$path -> $renamed_path"
    fi
    report_error "Existing Flyway migrations are immutable: $status $path"
  fi
done < <(git diff --name-status "$merge_base" "$HEAD_REF" -- "$MIGRATION_DIR")

base_max_version=0
while IFS= read -r path; do
  filename="${path##*/}"
  if [[ "$filename" =~ $MIGRATION_PATTERN ]]; then
    version="$(normalize_version "${BASH_REMATCH[1]}")"
    if (( 10#$version > 10#$base_max_version )); then
      base_max_version="$version"
    fi
  fi
done < <(git ls-tree -r --name-only "$BASE_REF" -- "$MIGRATION_DIR")

while IFS= read -r path; do
  filename="${path##*/}"
  [[ "$filename" =~ $MIGRATION_PATTERN ]] || continue

  version="$(normalize_version "${BASH_REMATCH[1]}")"
  if (( 10#$version <= 10#$base_max_version )); then
    report_error "New migration $path must use a version greater than the base maximum V$base_max_version"
  fi
done < <(git diff --diff-filter=A --name-only "$merge_base" "$HEAD_REF" -- "$MIGRATION_DIR")

if (( errors > 0 )); then
  echo "Flyway migration validation failed with $errors error(s)."
  exit 1
fi

echo "Flyway migration validation passed. Base maximum: V$base_max_version"
