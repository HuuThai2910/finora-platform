# Registry cố định

Thay đổi giá trị trong bảng MUST cập nhật cấu hình, tài liệu liên quan và file này trong cùng change. Không tự đổi giá trị chỉ để tránh conflict local.

| Thành phần | Port | Storage/định danh |
|---|---:|---|
| `finora-gateway` | 8080 | Không có DB |
| `finora-loan` | 8081 | MySQL `finora_loan` |
| `finora-payment` | 8082 | MySQL `finora_payment`, Redis 6379 |
| `finora-blockchain` | 8083 | Fabric channel `finora-channel`, chaincode `finora-ledger`, MSP `Org1MSP` |
| `finora-investment` | 8084 | MongoDB `finora_investment` |
| `finora-user` | 8085 | MySQL `finora_user` |
| `finora-notification` | 8086 | Kafka consumer + SMTP; chưa có DB |
| `finora-ai` | 8000 | FastAPI; chưa có DB |
| Keycloak | 8180 | OIDC |
| Kafka | 9092 | Zookeeper 2181 |
| MySQL | 3306 | MySQL 8 |
| MongoDB | 27017 | MongoDB 7 |
| Redis | 6379 | Redis 7 |

Consumer group hiện có: `payment-group`, `blockchain-group`, `notification-group`.

CURRENT STATE: chưa có Fineract, MinIO, MailHog hoặc Fabric network/chaincode trong repository. Không thêm chúng vào registry như thành phần đang chạy trước khi code/hạ tầng thật được đưa vào.

