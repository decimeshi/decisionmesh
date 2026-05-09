#!/bin/bash
echo "Building all modules..."
mvn clean install -DskipTests

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "Starting Quarkus dev mode..."
    mvn quarkus:dev -pl decisionmesh-bootstrap
else
    echo "Build failed!"
    exit 1
fi