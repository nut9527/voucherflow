FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
WORKDIR /app
RUN groupadd --system voucherflow && useradd --system --gid voucherflow voucherflow \
    && mkdir -p /app/.local/rocketmq && chown -R voucherflow:voucherflow /app/.local
COPY --chown=voucherflow:voucherflow target/voucherflow.jar /app/voucherflow.jar
USER voucherflow
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Duser.home=/app/.local", "-Drocketmq.log.root=/app/.local/rocketmq", "-jar", "/app/voucherflow.jar"]
