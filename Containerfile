# One image, two roles. The role is the first argument:
#   podman run iiif-gateway:2.0.0 web
#   podman run iiif-gateway:2.0.0 worker
#
# Both roles share the same config and the same database. See docs/DESIGN.md.

FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependency layer: this only changes when a POM does, so the (slow) dependency download is cached
# across source edits.
#
# One COPY per module, and the list has to match the reactors. It did not once: these were the module
# names from the original plan, three of them renamed before any code was written, so the build failed
# on the first COPY and the image had never been built at all.
COPY pom.xml .
COPY iiif-kramerius/pom.xml iiif-kramerius/
COPY iiif-kramerius/iiif-kramerius-model/pom.xml iiif-kramerius/iiif-kramerius-model/
COPY iiif-kramerius/iiif-kramerius-build/pom.xml iiif-kramerius/iiif-kramerius-build/
COPY iiif-kramerius/iiif-kramerius-embedded/pom.xml iiif-kramerius/iiif-kramerius-embedded/
COPY gateway/pom.xml gateway/
COPY gateway/iiif-kramerius-spi/pom.xml gateway/iiif-kramerius-spi/
COPY gateway/iiif-kramerius-rest/pom.xml gateway/iiif-kramerius-rest/
COPY gateway/iiif-gateway/pom.xml gateway/iiif-gateway/
# Failures here are not fatal: go-offline cannot always resolve a reactor's own modules, and this
# layer exists only to warm the cache. The real build below is what must succeed.
RUN mvn -q -B -DskipTests dependency:go-offline -f iiif-kramerius/pom.xml || true
RUN mvn -q -B -DskipTests dependency:go-offline -f gateway/pom.xml || true

COPY . .
# The library first and INTO THE LOCAL REPOSITORY, because the gateway resolves it as a released
# artifact rather than as a sibling module — that is what keeps the dependency one-way. The shared
# build-config POM goes with it, since the library's own POMs name it as their parent.
RUN mvn -q -B -N install \
 && mvn -q -B -DskipTests install -f iiif-kramerius/pom.xml \
 && mvn -q -B -DskipTests package -f gateway/pom.xml

FROM docker.io/library/eclipse-temurin:21-jre AS runtime

ARG SYSTEM_VERSION=dev
ARG SYSTEM_COMMIT=unknown
ENV SYSTEM_VERSION=${SYSTEM_VERSION} \
    SYSTEM_COMMIT=${SYSTEM_COMMIT}

# Never run as root; nothing here needs it.
RUN useradd --system --uid 10001 --create-home iiif
USER iiif
WORKDIR /app

COPY --from=build --chown=iiif:iiif /src/gateway/iiif-gateway/target/iiif-gateway-*.jar /app/iiif-gateway.jar

EXPOSE 8080
# 8081 is the admin listener and is deliberately NOT exposed — publishing it would undo the whole
# reason write routes live on a separate port.

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/iiif-gateway.jar"]
CMD ["web"]
