#!/bin/bash
# Restart (or start) the production Tomcat.  Invoked through
# `at now` by the certbot post hook so it runs in atd's cgroup,
# outside certbot's.  Also safe to run by hand as root.
#
# Install as: /usr/local/sbin/tomcat-restart.sh
exec >>/var/log/tomcat-restart.log 2>&1
echo "=== $(date '+%F %T') tomcat restart ==="

# Debounce: skip if we already restarted in the last 5 minutes.
STAMP=/run/tomcat-restart.stamp
if [ -f "$STAMP" ] && [ $(( $(date +%s) - $(stat -c %Y "$STAMP") )) -lt 300 ]; then
    echo "restarted within last 5 min; skipping"
    exit 0
fi
touch "$STAMP"

/home/ownsona/tomcat/bin/shutdown.sh
sleep 2

for i in $(seq 60); do
    ss -ltn | grep -qE ':(443|80) ' || break
    sleep 1
done

if ss -ltn | grep -qE ':(443|80) '; then
    echo "port still held after 60s; forcing"
    pkill -f org.apache.catalina.startup.Bootstrap
    sleep 5
fi

/home/ownsona/tomcat/bin/startup.sh
echo "started"
