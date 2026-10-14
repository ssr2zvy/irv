ARG IRV_BASE=irv-blank:local
FROM node:22-bookworm-slim AS node
FROM ${IRV_BASE}
USER root
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless maven && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
RUN python3 -c 'import os,json;json.dump([{"root":"image","path":p} for root in ["/opt","/usr/lib/jvm"] for p,d,f in os.walk(root)]+[{"root":"image","path":p} for p in ["/usr/bin","/usr/local/bin","/tmp"]],open("/opt/irv/image-tree.json","w"))'
