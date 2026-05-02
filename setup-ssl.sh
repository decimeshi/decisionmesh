#!/usr/bin/env bash
# scripts/setup-ssl.sh — Get Let's Encrypt SSL certificates
# Run once on server after first deploy.
#
# Usage:
#   bash scripts/setup-ssl.sh --domain yourdomain.com --email you@email.com

set -euo pipefail
DOMAIN=""; EMAIL=""
while [[ $# -gt 0 ]]; do
    case $1 in --domain) DOMAIN="$2"; shift 2;; --email) EMAIL="$2"; shift 2;; *) exit 1;; esac
done
[[ -z "$DOMAIN" || -z "$EMAIL" ]] && { echo "Usage: --domain yourdomain.com --email you@email.com"; exit 1; }

cd /opt/decisionmesh

echo "[SSL] Requesting certificates..."
docker run --rm \
    -v certbot_certs:/etc/letsencrypt \
    -v certbot_www:/var/www/certbot \
    certbot/certbot certonly \
    --webroot --webroot-path=/var/www/certbot \
    --email "$EMAIL" --agree-tos --no-eff-email \
    -d "$DOMAIN" -d "api.$DOMAIN" -d "staging.$DOMAIN"

sed -i "s/yourdomain\.com/$DOMAIN/g" nginx/conf.d/decisionmesh.conf
docker exec dm-nginx nginx -s reload

echo "[SSL] Done! Test: curl -I https://api.$DOMAIN/q/health"
