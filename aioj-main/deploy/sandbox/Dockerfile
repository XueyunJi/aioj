FROM criyle/go-judge:v1.12.0@sha256:62a26cac4c70dc6f47e15d87eb19524901048e5e4450479d512f1778386896d8 AS go-judge

FROM eclipse-temurin:17-jdk-jammy@sha256:e8ab220ad4c1a9382a4e7e2a21bc6462796a1beaa94ed114de0d5cddd24c3585

RUN apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=3 \
      g++ \
      python3 \
      wget \
    && mkdir -p /usr/lib/jvm \
    && cp -a /opt/java/openjdk /usr/lib/jvm/temurin-17 \
    && ln -sf /usr/lib/jvm/temurin-17/bin/java /usr/bin/java \
    && ln -sf /usr/lib/jvm/temurin-17/bin/javac /usr/bin/javac \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt
COPY --from=go-judge /opt/go-judge /opt/mount.yaml /opt/

EXPOSE 8090/tcp 5051/tcp 5052/tcp

ENTRYPOINT ["./go-judge"]
