FROM openjdk:17-ea-slim-buster

WORKDIR /app

# Copy the JAR file (built by GitHub Actions)
COPY target/ks-gateway-autodns-server-1.0.0.jar /app/app.jar

# Create non-root user
RUN useradd -r -u 1001 -g root appuser && \
    chown -R appuser:root /app

USER appuser

EXPOSE 8080

# Environment variables (these will be injected from ConfigMap and Secret)
ENV INGRESS_CLASS_PREFIX="" \
    HOST_SUFFIX="" \
    KUBESPHERE_NAMESPACE="" \
    INGRESS_CLASS_SUFFIX="" \
    EXTERNAL_DOMAIN_SUFFIX="" \
    RECORD_RETENTION_DAYS="" \
    DB_URL="" \
    DB_USERNAME="" \
    DB_PASSWORD="" \
    ALIYUN_DOMAIN="" \
    ALIYUN_REGION_ID="" \
    ALIYUN_ACCESS_KEY_ID="" \
    ALIYUN_ACCESS_KEY_SECRET="" \
    APISIX_ADMIN_URL="" \
    APISIX_ADMIN_KEY="" \
    API_TOKEN=""

ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", \
    "/app/app.jar"]
