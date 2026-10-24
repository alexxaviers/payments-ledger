#!/usr/bin/env bash
# Publish a file as one GitHub Actions annotation (visible on the run page and through the API).
#   annotate.sh notice|error "Title" path/to/file
level="$1"; title="$2"; file="$3"
[ -s "$file" ] || exit 0
body=$(head -c 3500 "$file" | sed -e 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}')
echo "::${level} title=${title}::${body}"
