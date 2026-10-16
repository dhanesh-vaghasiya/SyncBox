FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy the maven wrapper and pom.xml
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Grant execute permission to the wrapper
RUN chmod +x ./mvnw

# Download dependencies (this step is cached if pom.xml doesn't change)
RUN ./mvnw dependency:go-offline -B

# Copy the source code and build
COPY src src
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install ca-certificates for secure HTTPS communication with Backblaze B2 cloud storage
RUN apk add --no-cache ca-certificates

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

# Default environment variables (can be overridden at runtime or via docker-compose / .env)
ENV STORAGE_PROVIDER=b2 \
    B2_KEY_ID="" \
    B2_APPLICATION_KEY="" \
    B2_BUCKET_NAME="" \
    B2_ENDPOINT="https://s3.us-west-004.backblazeb2.com" \
    B2_REGION="us-west-004"

ENTRYPOINT ["java", "-jar", "app.jar"]