#!/usr/bin/env bash
# owner: @camunda/reliability-testing
#
# Selects the load-tests/setup folders to deploy as release load tests and reads their image
# tags from the versions.yaml of each folder in the current checkout.
#
# Usage: load-test-select-release-folders.sh
#
# Environment:
#   EVENT_NAME   push, or any other value (workflow_dispatch, workflow_call) to use VERSIONS
#   VERSIONS     Comma-separated folder names or minor versions, such as "8.8,8.10,main"
#   BEFORE       Push only: SHA of the head before the push
#   SHA          Push only: SHA of the head after the push
#   SETUP_DIR    Optional: path of the setup directory (default: load-tests/setup)
#
# Outputs (GITHUB_OUTPUT):
#   folders      JSON array of the selected folders, in canonical order
#   tags         JSON object, folder -> {orchestration, optimize, identity, connectors,
#                operate, tasklist}; operate and tasklist are set for stable-87 only

set -o nounset
set -o errexit
set -o pipefail

GITHUB_OUTPUT="${GITHUB_OUTPUT:-/dev/stdout}"
SETUP_DIR="${SETUP_DIR:-load-tests/setup}"
EVENT_NAME="${EVENT_NAME:-}"
VERSIONS="${VERSIONS:-}"
BEFORE="${BEFORE:-}"
SHA="${SHA:-}"

ALL_FOLDERS=(main stable-87 stable-88 stable-89 stable-810)
SHARED_REGEX="^${SETUP_DIR}/(common\.mk|utils\.sh|newLoadTest\.sh|charts/|scenarios/)"

selected=()

select_all() {
  selected=("${ALL_FOLDERS[@]}")
}

select_from_versions() {
  local token folder f
  local -A wanted=()
  IFS=',' read -ra tokens <<<"${VERSIONS}"
  for token in "${tokens[@]}"; do
    token="$(tr -d '[:space:]' <<<"${token}")"
    [[ -z "${token}" ]] && continue
    case "${token}" in
      main) folder=main ;;
      stable-87 | stable-88 | stable-89 | stable-810) folder="${token}" ;;
      8.7 | 8.8 | 8.9 | 8.10) folder="stable-${token/./}" ;;
      *)
        echo "::error::Unknown version '${token}'. Use one of: main, 8.7, 8.8, 8.9, 8.10, or a folder name (stable-87, stable-88, stable-89, stable-810)."
        exit 1
        ;;
    esac
    wanted[${folder}]=1
  done
  for f in "${ALL_FOLDERS[@]}"; do
    [[ -n "${wanted[${f}]:-}" ]] && selected+=("${f}")
  done
  if [[ ${#selected[@]} -eq 0 ]]; then
    echo "::error::The versions input is empty."
    exit 1
  fi
}

select_from_push() {
  local changed f
  if [[ -z "${BEFORE}" || "${BEFORE}" =~ ^0+$ ]]; then
    echo "No usable 'before' SHA, deploying all folders."
    select_all
    return
  fi
  if ! git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
    git fetch --no-tags --depth=1 origin "${BEFORE}" || true
  fi
  if ! changed="$(git diff --name-only "${BEFORE}" "${SHA}" 2>/dev/null)"; then
    echo "Cannot diff ${BEFORE}..${SHA}, deploying all folders."
    select_all
    return
  fi
  echo "Changed files:"
  echo "${changed}"
  if grep -qE "${SHARED_REGEX}" <<<"${changed}"; then
    echo "A shared file changed, deploying all folders."
    select_all
    return
  fi
  for f in "${ALL_FOLDERS[@]}"; do
    if grep -qE "^${SETUP_DIR}/${f}/" <<<"${changed}"; then
      selected+=("${f}")
    fi
  done
}

if [[ "${EVENT_NAME}" == "push" ]]; then
  select_from_push
else
  select_from_versions
fi

tags='{}'
for f in "${selected[@]}"; do
  file="${SETUP_DIR}/${f}/versions.yaml"
  if [[ "${f}" == "stable-87" ]]; then
    entry="$(yq -o=json -I=0 '{"orchestration": .zeebe.image.tag, "operate": .operate.image.tag, "tasklist": .tasklist.image.tag, "optimize": .optimize.image.tag, "identity": .identity.image.tag, "connectors": .connectors.image.tag}' "${file}")"
  else
    entry="$(yq -o=json -I=0 '{"orchestration": .orchestration.image.tag, "operate": "", "tasklist": "", "optimize": .optimize.image.tag, "identity": .identity.image.tag, "connectors": .connectors.image.tag}' "${file}")"
  fi
  if jq -e 'to_entries | map(select(.key != "operate" and .key != "tasklist")) | any(.value == null or .value == "")' <<<"${entry}" >/dev/null; then
    echo "::error::${file} lacks a required image tag: ${entry}"
    exit 1
  fi
  tags="$(jq -c --arg f "${f}" --argjson e "${entry}" '. + {($f): $e}' <<<"${tags}")"
done

if [[ ${#selected[@]} -eq 0 ]]; then
  folders='[]'
else
  folders="$(printf '%s\n' "${selected[@]}" | jq -R . | jq -sc .)"
fi

echo "folders=${folders}" >>"${GITHUB_OUTPUT}"
echo "tags=${tags}" >>"${GITHUB_OUTPUT}"
echo "Selected folders: ${folders}"
