FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY benefit-contract/pom.xml benefit-contract/pom.xml
COPY benefit-domain/pom.xml benefit-domain/pom.xml
COPY benefit-application/pom.xml benefit-application/pom.xml
COPY benefit-adapters/pom.xml benefit-adapters/pom.xml
COPY benefit-server/pom.xml benefit-server/pom.xml
RUN mvn -q -DskipTests dependency:go-offline
COPY benefit-contract/src benefit-contract/src
COPY benefit-domain/src benefit-domain/src
COPY benefit-application/src benefit-application/src
COPY benefit-adapters/src benefit-adapters/src
COPY benefit-server/src benefit-server/src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system benefit \
    && useradd --system --gid benefit --home-dir /app benefit
WORKDIR /app
COPY --from=build /workspace/benefit-server/target/benefit-server-*.jar app.jar
USER benefit
EXPOSE 8083
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-Djava.security.egd=file:/dev/urandom","-jar","/app/app.jar"]
