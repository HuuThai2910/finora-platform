# 🚀 FINORA Platform — Hệ thống P2P Lending

## 📖 Giới thiệu

FINORA là nền tảng cho vay ngang hàng (Peer-to-Peer Lending) được xây dựng trên kiến trúc **Microservices**, tích hợp **Trí tuệ Nhân tạo (AI)** để chấm điểm tín dụng và **Blockchain (Hyperledger Fabric)** để đảm bảo tính minh bạch, toàn vẹn dữ liệu.

## 🏗️ Kiến trúc

| Service | Port | Công nghệ | Mô tả |
|---------|------|-----------|-------|
| `finora-gateway` | 8080 | Spring Cloud Gateway | Định tuyến API |
| `finora-loan` | 8081 | Spring Boot + PostgreSQL | Vòng đời khoản vay |
| `finora-payment` | 8082 | Spring Boot + PostgreSQL + Redis | Ví, Giải ngân Saga |
| `finora-blockchain` | 8083 | Spring Boot + PostgreSQL + Fabric SDK | Sổ cái phân tán |
| `finora-investment` | 8084 | Spring Boot + MongoDB | Sàn khớp lệnh P2P |
| `finora-user` | 8085 | Spring Boot + MySQL | Quản lý User, eKYC |
| `finora-notification` | 8086 | Spring Boot + Kafka | Thông báo SMS/Email |
| `finora-ai` | 8000 | Python FastAPI | AI Credit Scoring, eKYC |

## 🚀 Khởi chạy

### 1. Chuẩn bị database

Thái dùng một Neon Project riêng cho Loan, Payment, Blockchain và Fineract. Xem hướng dẫn
[NEON-POSTGRESQL-SETUP.md](NEON-POSTGRESQL-SETUP.md). Khi dùng Neon, không cần bật container
PostgreSQL local.

Docker PostgreSQL vẫn được giữ làm offline fallback và cho integration test:

Docker Desktop phải đang chạy. Tạo `docker/.env` từ file mẫu, điền secret local rồi chạy smoke test:

```powershell
Copy-Item docker/.env.example docker/.env
powershell -ExecutionPolicy Bypass -File docker/smoke-infra.ps1 -Scope Loan -KeepRunning
```

Lệnh trên chạy hạ tầng chung và PostgreSQL riêng của Loan. Mỗi service Thái có database
container/user/volume riêng khi chạy offline; xem profile, DBeaver và lệnh dừng tại
[docker/README.md](docker/README.md). User/Keycloak của Hải vẫn dùng MySQL cho tới khi Hải quyết định khác.

Các lệnh build Java bên dưới yêu cầu JDK 21 và Maven 3.9 có trong `PATH`; cũng có thể dùng Maven do IntelliJ quản lý.

### 2. Build toàn bộ Java services
```powershell
mvn clean verify
```

### 3. Chạy từng service
```powershell
# Terminal 1
$env:LOAN_DB_URL='jdbc:postgresql://<neon-host>/neondb?sslmode=require'
$env:LOAN_DB_USERNAME='<default role từ Neon Connect>'
$env:LOAN_DB_PASSWORD='<Neon password>'
mvn -pl finora-loan -am spring-boot:run

# Terminal 2 (ví dụ)
cd finora-payment
mvn spring-boot:run

# ... tương tự cho các service khác
```

### 4. Chạy AI Service (Python)
```powershell
cd finora-ai
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

## 👥 Thành viên

| Thành viên | Phạm vi |
|------------|---------|
| **Thái** | `finora-loan`, `finora-payment`, `finora-blockchain` |
| **Hải** | `finora-ai`, `finora-investment`, `finora-user`, `finora-notification` |

## 📄 License

Dự án phục vụ mục đích học thuật — Khóa luận Tốt nghiệp IUH 2026.
