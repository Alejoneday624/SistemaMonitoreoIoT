# Etapa 1: construir el JAR con Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copiar el pom primero para aprovechar la caché de capas
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copiar el código fuente y empaquetar (sin tests: requieren base de datos)
COPY src ./src
RUN mvn -B -DskipTests package

# Etapa 2: imagen ligera para ejecutar la aplicación
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Usuario no root
RUN useradd -m -u 1001 appuser
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 && grep -q "UP" <&3' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
