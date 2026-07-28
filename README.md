# 🚀 FINORA Platform — Hệ thống P2P Lending

## 📖 Giới thiệu

FINORA là nền tảng cho vay ngang hàng (Peer-to-Peer Lending) được xây dựng trên kiến trúc **Microservices**, tích hợp **Trí tuệ Nhân tạo (AI)** để chấm điểm tín dụng và **Blockchain (Hyperledger Fabric)** để đảm bảo tính minh bạch, toàn vẹn dữ liệu.

## 🏗️ Kiến trúc

| Service | Port | Công nghệ | Mô tả |
|---------|------|-----------|-------|
| `finora-gateway` | 8080 | Spring Cloud Gateway | Định tuyến API |
| `finora-loan` | 8081 | Spring Boot + MySQL | Vòng đời khoản vay |
| `finora-payment` | 8082 | Spring Boot + MySQL + Redis | Ví, Giải ngân Saga |
| `finora-blockchain` | 8083 | Spring Boot + Fabric SDK | Sổ cái phân tán |
| `finora-investment` | 8084 | Spring Boot + MongoDB | Sàn khớp lệnh P2P |
| `finora-user` | 8085 | Spring Boot + MySQL | Quản lý User, eKYC |
| `finora-notification` | 8086 | Spring Boot + Kafka | Thông báo SMS/Email |
| `finora-ai` | 8000 | Python FastAPI | AI Credit Scoring, eKYC |

## 🚀 Khởi chạy

### 1. Dựng hạ tầng
```bash
cd docker
docker-compose up -d
```

### 2. Build toàn bộ Java services
```bash
mvn clean install -DskipTests
```

### 3. Chạy từng service
```bash
# Terminal 1
cd finora-loan && mvn spring-boot:run

# Terminal 2
cd finora-payment && mvn spring-boot:run

# ... tương tự cho các service khác
```

### 4. Chạy AI Service (Python)
```bash
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
