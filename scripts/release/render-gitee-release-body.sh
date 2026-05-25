#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <release-body.md> <gitee-attachments.jsonl> <gitee-owner/repo>" >&2
  exit 2
fi

release_body_file="$1"
attachments_file="$2"
gitee_repo="${3#/}"
gitee_repo="${gitee_repo%/}"

if [ ! -f "$release_body_file" ]; then
  echo "Release body file not found: $release_body_file" >&2
  exit 1
fi

if [ ! -f "$attachments_file" ]; then
  echo "Gitee attachments file not found: $attachments_file" >&2
  exit 1
fi

if [ -z "$gitee_repo" ]; then
  echo "Gitee repository must be owner/repo" >&2
  exit 1
fi

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
cp "$release_body_file" "$rendered"

while IFS= read -r attachment_json || [ -n "$attachment_json" ]; do
  if [ -z "$attachment_json" ]; then
    continue
  fi

  attachment_id="$(jq -r '.id // .attach_file_id // .attachFileId // empty' <<<"$attachment_json")"
  attachment_name="$(jq -r '.name // .filename // .file_name // .fileName // .path // empty' <<<"$attachment_json")"

  if [ -z "$attachment_id" ] || [ "$attachment_id" = "null" ]; then
    echo "Gitee attachment response is missing id: $attachment_json" >&2
    exit 1
  fi

  if [ -z "$attachment_name" ] || [ "$attachment_name" = "null" ]; then
    echo "Gitee attachment response is missing filename: $attachment_json" >&2
    exit 1
  fi

  escaped_name="$(printf '%s' "$attachment_name" | perl -pe 's/([\\\/.^$|(){}\[\]*+?])/\\$1/g')"
  gitee_download_url="https://gitee.com/${gitee_repo}/attach_files/${attachment_id}/download"
  escaped_url="$(printf '%s' "$gitee_download_url" | perl -pe 's/[\\&]/\\$&/g')"

  perl -0pi -e \
    "s#https://github\\.com/[^)\\s]+/releases/download/[^)\\s]+/${escaped_name}(?=[)\\s]|$)#${escaped_url}#g" \
    "$rendered"
done < "$attachments_file"

if grep -Eq 'https://github\.com/[^)[:space:]]+/releases/download/' "$rendered"; then
  echo "Rendered Gitee release body still contains GitHub release download links" >&2
  exit 1
fi

cat "$rendered"
