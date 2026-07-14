FROM clojure:temurin-21-tools-deps

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      bash \
      ca-certificates \
      git \
      python3 \
      python3-venv \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/yggdrasil

COPY deps.edn bb.edn tests.edn README.md AGENTS.md ./
COPY src ./src
COPY resources ./resources
COPY bin ./bin
COPY docker ./docker
COPY scripts/ygg-server-client.py \
     scripts/local-embedding-worker.py \
     scripts/local-vector-requirements.txt \
     scripts/parser-worker.py \
     scripts/parser-worker-requirements.txt \
     scripts/ygg-maintenance-codex.sh \
     ./scripts/

RUN chmod +x /opt/yggdrasil/bin/ygg /opt/yggdrasil/bin/ygg-mcp /opt/yggdrasil/docker/entrypoint.sh \
    /opt/yggdrasil/scripts/ygg-maintenance-codex.sh \
    && clojure -P -M:run

ENV YGG_STORAGE_ROOT=/data
ENV YGG_CONFIG_HOME=/data/config
ENV PATH="/opt/yggdrasil/bin:${PATH}"

VOLUME ["/data"]
WORKDIR /workspace

ENTRYPOINT ["/opt/yggdrasil/docker/entrypoint.sh"]
CMD ["help"]
