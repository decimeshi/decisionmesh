# 🚀 DecisionMesh — AI Control Plane

DecisionMesh is an **enterprise AI control plane** for governing, securing, and observing AI interactions across applications.

---

## 🧩 Tech Stack

- Backend: Quarkus (Java)
- Frontend: React (Vite)
- Database: PostgreSQL (Docker)
- Auth: Keycloak (Docker)
- Containerization: Docker Compose

---

## 📁 Project Structure

ai-control-plane/
├── decisionmesh-ui/        # React frontend  
├── keycloak/              # Keycloak realm export  
├── src/                   # Quarkus backend  
├── docker-compose.yml  
├── build.bat  
└── build.sh  

---

## ⚙️ Prerequisites

### ☕ Java
- Recommended: JDK 21
- Verify:
  java -version

### 📦 Maven
- Verify:
  mvn -v

### 🐳 Docker
- Verify:
  docker -v
  docker compose version

### 🟢 Node.js
- Verify:
  node -v
  npm -v

---

## 🔧 Backend Setup

git clone https://github.com/thirupala/ai-control-plane.git
cd ai-control-plane

### Start Infra
docker compose up -d

### Run Backend (Windows)
build.bat

### Run Backend (Linux/Mac)
./build.sh

### Verify
http://localhost:8080  
http://localhost:8080/q/health

---

## 🔐 Keycloak Setup (Auto Import)

Realm file already exists:

keycloak/realm-export.json

Docker config:

- start-dev --import-realm
- volume: ./keycloak:/opt/keycloak/data/import

Access:
http://localhost:8081

Admin:
admin / admin

---

## 🐘 Database

Host: localhost  
Port: 5432  
DB: decisionmesh  
User: postgres  
Password: postgres  

---

## 🌐 Frontend Setup

cd decisionmesh-ui  
npm install  
npm run dev  

Access:
http://localhost:5173

---

## 🌐 Frontend Env File

decisionmesh-ui/.env.development

Example:

VITE_API_BASE_URL=http://localhost:8080  
VITE_KEYCLOAK_URL=http://localhost:8081  
VITE_KEYCLOAK_REALM=decisionmesh  
VITE_KEYCLOAK_CLIENT=decisionmesh-client  

---

## 🔄 Full Startup

docker compose up -d  
build.bat  
cd decisionmesh-ui  
npm install  
npm run dev  

---

## 🧯 Troubleshooting

- Ensure Docker is running
- Check port conflicts (8080, 8081, 5432, 5173)
- Use correct Java version
- Run npm install if UI fails

---

## 📜 License

MIT
