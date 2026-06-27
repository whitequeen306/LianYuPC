#!/bin/sh
set -eu
/generate-cors-map.sh
exec nginx -g 'daemon off;'
