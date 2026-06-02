# Use JRE instead of JDK — smaller image, fewer CVEs
# eclipse-temurin:25-jre-noble uses Ubuntu Noble (24.04 LTS) — fewer OS CVEs than older bases
FROM eclipse-temurin:25-jre-noble

WORKDIR /app

# Create non-root user for security
RUN groupadd -r decisionmesh && useradd -r -g decisionmesh decisionmesh

COPY decisionmesh-bootstrap/target/quarkus-app/lib/ /app/lib/
COPY decisionmesh-bootstrap/target/quarkus-app/*.jar /app/
COPY decisionmesh-bootstrap/target/quarkus-app/app/ /app/app/
COPY decisionmesh-bootstrap/target/quarkus-app/quarkus/ /app/quarkus/

# Set ownership
RUN chown -R decisionmesh:decisionmesh /app

# Run as non-root
USER decisionmesh

EXPOSE 8080

CMD ["java", "-jar", "/app/quarkus-run.jar"]
