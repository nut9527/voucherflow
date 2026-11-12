FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
WORKDIR /app
RUN groupadd --system supplier && useradd --system --gid supplier supplier \
    && mkdir -p /app/.local && chown supplier:supplier /app/.local
COPY --chown=supplier:supplier target/supplier-simulator.jar /app/supplier-simulator.jar
USER supplier
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Duser.home=/app/.local", "-jar", "/app/supplier-simulator.jar"]
