@echo off
echo Building all modules...

set MAVEN_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED

call mvn clean install -DskipTests

if %ERRORLEVEL% EQU 0 (
    echo Build successful!
    echo Starting Quarkus dev mode...
    call mvn quarkus:dev -pl decisionmesh-bootstrap
) else (
    echo Build failed!
    exit /b 1
)