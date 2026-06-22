FROM clojure:temurin-21-tools-deps

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/yggdrasil

COPY deps.edn bb.edn tests.edn README.md AGENTS.md ./
COPY src ./src
COPY resources ./resources
COPY bin ./bin
COPY docker ./docker

RUN chmod +x /opt/yggdrasil/bin/ygg /opt/yggdrasil/bin/ygg-mcp /opt/yggdrasil/docker/entrypoint.sh \
    && clojure -P -M:run

ENV YGG_XTDB_PATH=/data/xtdb
ENV PATH="/opt/yggdrasil/bin:${PATH}"

VOLUME ["/data"]
WORKDIR /workspace

ENTRYPOINT ["/opt/yggdrasil/docker/entrypoint.sh"]
CMD ["help"]
