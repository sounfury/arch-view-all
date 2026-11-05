# Shared, reproducible analyzers for every Clojure repository on this agent.
FROM clojure:temurin-25-tools-deps-bookworm-slim

ARG CRAP_REV=b5bd03f8244167f39aa6e00ccee114a2a1589820
ARG CLJ_KONDO_VERSION=2026.10.26
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
ENTRYPOINT []

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl git unzip python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/crap \
    && /opt/crap/bin/pip install --no-cache-dir \
       "git+https://github.com/sounfury/crap-everything.git@${CRAP_REV}" \
    && ln -s /opt/crap/bin/crap /usr/local/bin/crap \
    && case "$(uname -m)" in \
         aarch64) kondo_arch=linux-aarch64 ;; \
         x86_64) kondo_arch=linux-amd64 ;; \
         *) exit 1 ;; \
       esac \
    && curl -fsSL -o /tmp/kondo.zip \
       "https://github.com/clj-kondo/clj-kondo/releases/download/v${CLJ_KONDO_VERSION}/clj-kondo-${CLJ_KONDO_VERSION}-${kondo_arch}.zip" \
    && unzip /tmp/kondo.zip -d /usr/local/bin \
    && rm /tmp/kondo.zip

WORKDIR /opt/quality
COPY deps.edn clojure_metrics.clj clojure_metrics_test.clj ./
RUN clojure -Srepro -P \
    && clojure -Srepro -M -m clojure-metrics-test
COPY clojure-quality dead-code /usr/local/bin/
RUN chmod +x /usr/local/bin/clojure-quality /usr/local/bin/dead-code
