#!/bin/sh
set -eu
export DISPLAY=:99
Xvfb "$DISPLAY" -screen 0 1024x768x24 -ac -nolisten tcp >/tmp/mcicon-xvfb.log 2>&1 &
attempt=0
until glxinfo -B >/tmp/mcicon-glxinfo.log 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        cat /tmp/mcicon-xvfb.log /tmp/mcicon-glxinfo.log >&2
        exit 1
    fi
    sleep 1
done
exec "$@"
