# Replica image for the Tailored BFT VDR (plan §10, deploy/).
#
# Digest-pinned base: plan §11 M8 requires image digests to be published BEFORE any measurement,
# so a reviewer can reproduce the exact binary that produced a number. Replace the digest below
# when you bump the JDK, and record the change in docs/ alongside the affected result set.
FROM eclipse-temurin:21-jdk-jammy@sha256:REPLACE_WITH_PINNED_DIGEST AS build
WORKDIR /src
COPY vdr-core ./vdr-core
COPY build.sh ./
RUN ./build.sh

FROM eclipse-temurin:21-jre-jammy@sha256:REPLACE_WITH_PINNED_DIGEST
WORKDIR /opt/vdr
COPY --from=build /src/build/classes ./classes
# hosts.config and system.config are mounted from the Helm ConfigMap, not baked in: the same
# image must serve every replica id, or the digest stops identifying the binary under test.
VOLUME ["/opt/vdr/config"]
ENV REPLICA_ID=0
EXPOSE 11000 11001
# The fault-injection adapter is never present in this image (plan §4, §13). Cluster's
# assertNoByzantineInBenchmark() fails the process at startup if it somehow is.
ENTRYPOINT ["sh", "-c", "cd /opt/vdr/config && exec java -cp /opt/vdr/classes vdr.replication.ReplicaMain "${REPLICA_ID##*-}""]
