#!/bin/bash
# Certbot DEPLOY hook.  Runs once per successfully renewed lineage,
# INSIDE certbot's cgroup.  Never start or stop Tomcat from here:
# any process spawned in this cgroup is killed when certbot's unit
# or snap scope exits.  That happens on scheduled renewals
# (snap.certbot.renew.service) AND on a manual `certbot renew` run
# from an SSH session that later logs out.  setsid/nohup do NOT
# escape a cgroup kill.  All this hook does is record that a restart
# is needed; the post hook acts on it.
#
# Install as: /etc/letsencrypt/renewal-hooks/deploy/tomcat-restart-flag.sh
echo "$(date '+%F %T') lineage=${RENEWED_LINEAGE:-manual}" >> /run/tomcat-restart-needed
