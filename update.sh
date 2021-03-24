#!/bin/bash

[[ -n "${VERBOSE}" ]] && set -x
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

ensure_on_path() {
  command -v "$1" >/dev/null 2>&1 || { echo >&2 "I require '$1' but it's not installed. Aborting."; exit 1; }
}

display_help() {
  echo    >&2 "$0 <url> <version> <commit>"
  echo -e >&2 "\turl: URL to Azure Icons"
  echo -e >&2 "\tcommit: Auto-commit changes"
}

ensure_on_path curl
ensure_on_path java
ensure_on_path mvn
ensure_on_path git
ensure_on_path unzip

if [[ $# -lt 1 ]]; then
  display_help
  exit 1
fi

URL=$1
COMMIT=$2

TMP_DIR=$(mktemp -d)
[[ -z "${NO_CLEANUP}" ]] && trap "{ echo Removing ${TMP_DIR}; rm -rf ${TMP_DIR}; }" EXIT

echo >&2 "Downloading $1"
curl "${URL}" --output "${TMP_DIR}/azure-icons.zip"
unzip -o "${TMP_DIR}/azure-icons.zip" -d "${TMP_DIR}"

ICON_DIR=$(cd "${TMP_DIR}"/Azure_Public_Service_Icons/Icons && pwd)
ICON_VERSION=''
REGEX_NUMBER='([0-9]+)'
if ! [[ ${URL} =~ ${REGEX_NUMBER} ]]; then
  echo >&2 "Couldn't parse version number from URL: ${URL}"
  exit 1
else
  ICON_VERSION="${BASH_REMATCH[1]}"
fi

if [ -z "$ICON_VERSION" ]; then
  echo >&2 "Couldn't parse version number from URL: ${URL}"
  exit 1
fi

echo "Detected version: ${ICON_VERSION}"

# Compile our translator
pushd "${DIR}/translator"
mvn package
popd

# Clear our old .graphml files
rm -f "${DIR}"/*.graphml

fix_mistakes() {
  case "$(echo $1 | tr '[:upper:]' '[:lower:]')" in
    "internet of things")
      echo "IoT"
      ;;
    *)
      echo "$1"
      ;;
  esac
}

find "${ICON_DIR}" -mindepth 1 -type d | sort | while read section; do
  SECTION_NAME="$(echo ${section} | tr ' ' '_')"
  SECTION_NAME="$(basename ${SECTION_NAME})"
  SECTION_NAME="$(echo ${SECTION_NAME} | tr '_-' ' ' | tr -s ' ' ' ')"
  SECTION_NAME="$(fix_mistakes "${SECTION_NAME}")"
  if [ -z "$SECTION_NAME" ]; then
    echo >&2 "Got empty section when parsing ${section}"
    exit 1
  fi
  SECTION_NAME="Azure - ${SECTION_NAME}"
  echo "Found: ${SECTION_NAME}"
  mkdir -p "${TMP_DIR}/sections/${SECTION_NAME}"
  cp "${section}"/*.svg "${TMP_DIR}/sections/${SECTION_NAME}" || \
    echo "WARNING: ${section} had no SVGs!"
done

find "${TMP_DIR}/sections/${SECTION_NAME}" -type d -empty -prune \
  -print -exec rmdir {} \;

for section in "${TMP_DIR}/sections/"*; do
  SECTION_NAME="${section#${TMP_DIR}/sections/}"
  if [ -z "$SECTION_NAME" ]; then
    echo >&2 "Got empty section when parsing ${section}"
    exit 1
  fi
  echo "Generating palette for: ${SECTION_NAME}"
  java -jar "${DIR}/translator/target/yed-translator-1.0-SNAPSHOT.jar" \
      --out "${DIR}/${SECTION_NAME}.graphml" \
      --url "${URL}" \
      --version "${ICON_VERSION}" \
      "${section}/"*.svg
done

echo "AZURE_ICONS_VERSION=${ICON_VERSION}" > "${DIR}/metadata.config"
echo "AZURE_ICONS_URL=${URL}" >> "${DIR}/metadata.config"

if [[ -n "${COMMIT}" ]]; then
  git add "${DIR}/"*.graphml
  git add "${DIR}/metadata.config"
  # Glob matching doesn't pick up our deleted .graphml files
  git ls-files --deleted | grep ".graphml" | tr \\n \\0 | xargs -0 git add
  git commit -m "Updating .graphml files to version ${ICON_VERSION}"
  echo "git: Updating .graphml files to version ${ICON_VERSION}"
fi
