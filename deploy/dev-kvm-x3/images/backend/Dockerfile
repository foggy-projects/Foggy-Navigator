FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY launcher/target/launcher-*.jar /app/app.jar

RUN useradd --system --create-home --home-dir /app navigator \
    && mkdir -p /app/logs \
    && chown -R navigator:navigator /app

USER navigator
EXPOSE 8112

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
