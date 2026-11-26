# syntax=docker/dockerfile:1.7
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

ARG RUNTIME_USER=0
ARG NPM_TARBALL_CDN=
USER root
COPY deploy/docker/csot-root-ca.crt /usr/local/share/ca-certificates/csot-root-ca.crt
RUN set -eux; \
    if ! command -v update-ca-certificates >/dev/null 2>&1; then \
      if command -v apk >/dev/null 2>&1; then \
        apk add --no-cache ca-certificates; \
      elif command -v apt-get >/dev/null 2>&1; then \
        apt-get update; \
        apt-get install -y --no-install-recommends ca-certificates; \
        rm -rf /var/lib/apt/lists/*; \
      else \
        echo "No supported package manager for CA certificates" >&2; \
        exit 1; \
      fi; \
    fi; \
    update-ca-certificates

ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    PIP_CERT=/etc/ssl/certs/ca-certificates.crt \
    CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt \
    GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt \
    ZHIYE_NPM_TARBALL_CDN=${NPM_TARBALL_CDN}
USER ${RUNTIME_USER}
