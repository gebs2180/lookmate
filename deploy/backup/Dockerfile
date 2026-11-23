# Backup runner: Postgres 18 client tools (pg_dump matches the server), rclone for R2, and age to encrypt.
# Also has the server binaries, which restore-test.sh (run on Zach's machine with the key) uses to restore into a throwaway cluster.
# age built from source on a current Go: the Debian package and the v1.3.2 release binary both use a Go whose
# stdlib CVEs fail the Images scan (2026-11-23). go install checks the module against sum.golang.org.
FROM golang:1.27.4-bookworm AS age
ARG AGE_VERSION=v1.3.2
RUN CGO_ENABLED=0 GOBIN=/out go install -trimpath -ldflags "-s -w" filippo.io/age/cmd/age@${AGE_VERSION}

FROM postgres:18
# rclone from its release, not Debian's: the packaged 1.60 got 501 NotImplemented from R2 on every first
# attempt (2026-10-28). Bump the version and both checksums together (downloads.rclone.org/<version>/SHA256SUMS).
ARG RCLONE_VERSION=v1.76.0
ARG RCLONE_SHA256_AMD64=b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7
ARG RCLONE_SHA256_ARM64=ee169016675e23a69d77240565133ff7dbc9b33d16fa8f7053d4af39f1eaeb78
# apt-get upgrade picks up Debian security fixes newer than the postgres:18 image (krb5, zlib on 2026-11-23).
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl ca-certificates unzip \
    && arch=$(dpkg --print-architecture) \
    && case "$arch" in amd64) sum=$RCLONE_SHA256_AMD64 ;; arm64) sum=$RCLONE_SHA256_ARM64 ;; *) echo "no rclone checksum for $arch" >&2; exit 1 ;; esac \
    && curl -fsSLo /tmp/rclone.zip "https://downloads.rclone.org/${RCLONE_VERSION}/rclone-${RCLONE_VERSION}-linux-${arch}.zip" \
    && echo "${sum}  /tmp/rclone.zip" | sha256sum -c - \
    && unzip -q -j /tmp/rclone.zip "*/rclone" -d /usr/local/bin \
    && chmod 755 /usr/local/bin/rclone \
    && rm /tmp/rclone.zip \
    && apt-get purge -y unzip && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /usr/local/bin/gosu
# The base image's gosu is built with an old Go (2026-11-23 scan) and only its entrypoint, which we replace, used it.
# restore-test.sh drops to postgres with setpriv instead.
COPY --from=age /out/age /usr/local/bin/age
COPY age-recipients.sh backup.sh backup-check.sh decrypt-dump.sh restore-test.sh restore-photos.sh schedule.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/backup-check.sh /usr/local/bin/decrypt-dump.sh /usr/local/bin/restore-test.sh /usr/local/bin/restore-photos.sh /usr/local/bin/schedule.sh
# The image's own postgres user, not root (security review 2026-10-26, #8): the dumps go over the network, the
# restore test's throwaway server runs as postgres anyway, and nothing here is kept on a volume.
USER postgres
ENTRYPOINT []
CMD ["schedule.sh"]
