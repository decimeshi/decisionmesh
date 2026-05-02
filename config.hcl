# infra/openbao/config.hcl
# Production OpenBao — file storage (persistent across restarts).
# TLS is handled by nginx — internal traffic uses plain HTTP.

ui = false

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

storage "file" {
  path = "/openbao/data"
}

api_addr     = "http://0.0.0.0:8200"
cluster_addr = "http://0.0.0.0:8201"
disable_mlock = false
log_level = "warn"
