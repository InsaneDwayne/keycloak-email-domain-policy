# Images for the integration tests; used by compose.yaml, not on their own.
ARG KEYCLOAK_VERSION=latest

# Builds the JAR and runs the unit tests.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package

# Keycloak with the JAR installed, as users would install it.
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS keycloak
COPY --from=build /src/target/keycloak-email-domain-policy-*.jar /opt/keycloak/providers/
# Copied into the shared volume the first time it is mounted.
COPY integration-tests/disposable-domains.txt /domains/disposable.txt

# Runs the *IT classes against the keycloak service.
FROM build AS tests
CMD ["mvn", "-B", "-ntp", "-Pintegration", "verify"]
