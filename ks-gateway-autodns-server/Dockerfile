# Stage 1: Build
FROM container-registry.oracle.com/graalvm/native-image:17-ol8 AS builder

WORKDIR /app

# Copy pom.xml
COPY pom.xml ./

# Download dependencies
RUN microdnf install -y maven && \
    mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build native image
RUN mvn -Pnative native:compile

# Stage 2: Runtime
FROM oraclelinux:8-slim

WORKDIR /app

# Copy the native executable
COPY --from=builder /app/target/ks-gateway-autodns-server /app/ks-gateway-autodns-server

# Create non-root user
RUN useradd -r -u 1001 -g root appuser && \
    chown -R appuser:root /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["/app/ks-gateway-autodns-server"]
