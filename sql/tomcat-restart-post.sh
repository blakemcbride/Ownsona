#!/bin/bash
# Certbot POST hook.  Runs once after ALL renewal attempts finish,
# so a restart here cannot take port 80 down while another domain's
# webroot challenge is still being fetched (the race a per-lineage
# deploy-hook restart has).  If the deploy hook flagged a renewal,
# hand the restart to at(1): atd owns the job, so it survives
# certbot's cgroup being torn down.
#
# Install as: /etc/letsencrypt/renewal-hooks/post/tomcat-restart-post.sh
# Requires:   the `at` package (atd running).
exec >>/var/log/tomcat-restart.log 2>&1

FLAG=/run/tomcat-restart-needed
if [ ! -f "$FLAG" ]; then
    exit 0
fi
echo "=== $(date '+%F %T') post hook: renewal flagged, scheduling restart via at ==="
cat "$FLAG"
rm -f "$FLAG"
echo /usr/local/sbin/tomcat-restart.sh | at now
