# ==============================================================================
# Chitthi (चिठ्ठी) - Multi-Stage Production Dockerfile
# ==============================================================================
# Stage 1: Build & Layer Extraction
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Install Maven
RUN apk add --no-cache maven

# 1. Cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 2. Copy source and compile production artifact
COPY src ./src
RUN mvn clean package -DskipTests -B

# 3. Extract Spring Boot 3.4 layered JAR for optimized layer caching
RUN java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted

# ==============================================================================
# Stage 2: Minimal Hardened Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: install wget for healthcheck & create unprivileged non-root user
RUN apk add --no-cache wget && \
    addgroup -g 1001 chitthi && \
    adduser -u 1001 -G chitthi -s /bin/sh -D chitthi

WORKDIR /app

# Copy layered directories in order of change frequency (least frequent to most frequent)
COPY --from=builder /build/extracted/dependencies/ ./
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/extracted/application/ ./

# Set permissions for non-root execution
RUN chown -R chitthi:chitthi /app

USER chitthi:chitthi

# JVM tuning: Java 21 Generational ZGC & Virtual Threads for low latency I/O
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
