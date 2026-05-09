# config/openbao.hcl
# OpenBao server-mode configuration for prod and staging.
# Mounted at /openbao/config/openbao.hcl inside the container.
#
# ============================================================
# FIRST-TIME INIT (run once after first `docker compose up -d`)
# ============================================================
#
#   # 1. Initialize with a single unseal key for simplicity.
#   #    For production hardening use -key-shares=5 -key-threshold=3
#   #    and store keys in separate secure locations.
#   docker compose exec openbao \
#     bao operator init -key-shares=1 -key-threshold=1 \
#     -format=json > openbao-init.json
#
#   # 2. Store the output securely — you cannot recover it later.
#   cat openbao-init.json
#   # Note: "unseal_keys_b64" and "root_token"
#
#   # 3. Unseal (required after every OpenBao restart).
#   docker compose exec openbao \
#     bao operator unseal <unseal_key_from_init>
#
#   # 4. Set VAULT_TOKEN in your .env to the root_token value,
#   #    then restart the API so it picks it up.
#
# For automated unseal on a single-node setup, consider
# OpenBao's Transit auto-unseal or a cloud KMS in the future.
# ============================================================

ui = false

storage "file" {
  path = "/openbao/data"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1  # TLS is handled by Caddy; keep internal comms plain
}

api_addr     = "http://0.0.0.0:8200"
cluster_addr = "http://0.0.0.0:8201"

# Telemetry (optional — enable if you add Prometheus scraping)
# telemetry {
#   prometheus_retention_time = "30s"
#   disable_hostname          = true
# }
