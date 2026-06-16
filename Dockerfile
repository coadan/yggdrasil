FROM clojure:temurin-21-tools-deps

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/agraph

COPY deps.edn bb.edn tests.edn README.md AGENTS.md ./
COPY src ./src
COPY resources ./resources
COPY bin ./bin
COPY docker ./docker

RUN chmod +x /opt/agraph/bin/agraph /opt/agraph/bin/agraph-mcp /opt/agraph/docker/entrypoint.sh \
    && clojure -P -M:run

ENV AGRAPH_XTDB_PATH=/data/xtdb
ENV PATH="/opt/agraph/bin:${PATH}"

VOLUME ["/data"]
WORKDIR /workspace

ENTRYPOINT ["/opt/agraph/docker/entrypoint.sh"]
CMD ["help"]
