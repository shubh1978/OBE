# ─── Stage 1: Build ───────────────────────────────────────────────────────────
# Use Maven + Java 21 to build the JAR inside Docker (no local Maven needed)
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml first — lets Docker cache dependencies if pom.xml hasn't changed
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Now copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─── Stage 2: Run ─────────────────────────────────────────────────────────────
# Slim Java 21 JRE — much smaller image (~200MB vs ~500MB)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

# Copy only the built JAR from the builder stage
COPY --from=builder /app/target/obe-application-0.0.1-SNAPSHOT.jar app.jar

# Render injects PORT automatically; default to 8080 locally
EXPOSE 8080

# Run the Spring Boot app with memory limits safe for Render's free tier (512MB)
# -Xms64m  → start with 64MB heap
# -Xmx256m → max 256MB heap (leaves ~256MB for OS, Metaspace, threads)
# -XX:+UseSerialGC → lower GC overhead vs default G1GC
# -XX:MaxMetaspaceSize=128m → cap Metaspace (class definitions)
ENTRYPOINT ["java", \
  "-Xms64m", \
  "-Xmx256m", \
  "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=128m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

