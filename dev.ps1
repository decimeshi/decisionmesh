Write-Host "Building all modules..." -ForegroundColor Cyan
mvn clean install -DskipTests

if ($LASTEXITCODE -eq 0) {
    Write-Host "Build successful!" -ForegroundColor Green
    Write-Host "Starting Quarkus dev mode..." -ForegroundColor Cyan
    mvn quarkus:dev -pl decisionmesh-api
} else {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}