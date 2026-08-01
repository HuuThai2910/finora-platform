# Registry cố định

Thay đổi giá trị trong bảng MUST cập nhật cấu hình, tài liệu liên quan và file này trong cùng change. Không tự đổi giá trị chỉ để tránh conflict local.

| Thành phần | Port | Storage/định danh |
|---|---:|---|
| `finora-gateway` | 8080 | Không có DB |
| `finora-loan` | 8081 | MySQL `finora_loan` |
| `finora-payment` | 8082 | MySQL `finora_payment`, Redis nội bộ 6379 |
| `finora-blockchain` | 8083 | Fabric channel `finora-channel`, chaincode `finora-ledger`, MSP `Org1MSP` |
| `finora-investment` | 8084 | MongoDB `finora_investment` |
| `finora-user` | 8085 | MySQL `finora_user` |
| `finora-notification` | 8086 | Kafka consumer + SMTP; chưa có DB |
| `finora-ai` | 8000 | FastAPI; chưa có DB |
| Keycloak | 8180 | OIDC; MySQL `keycloak` riêng, không publish DB port |
| Kafka | 9092 host local; 29092 nội bộ Docker | Zookeeper 2181 |
| Loan MySQL | 13306 host local; 3306 nội bộ | MySQL 8.4, container/volume/user riêng của Loan |
| Payment MySQL | 13307 host local; 3306 nội bộ | MySQL 8.4, container/volume/user riêng của Payment |
| User MySQL | 13308 host local; 3306 nội bộ | MySQL 8.4, container/volume/user riêng của User |
| Investment MongoDB | 27018 host local; 27017 nội bộ | MongoDB 7, container/volume/user riêng của Investment |
| Payment Redis | 6380 host local; 6379 nội bộ | Redis 7, password/volume riêng của Payment |

Consumer group hiện có: `payment-group`, `blockchain-group`, `notification-group`.

CURRENT STATE: chưa có Fineract, MinIO, MailHog hoặc Fabric network/chaincode trong repository. Không thêm chúng vào registry như thành phần đang chạy trước khi code/hạ tầng thật được đưa vào.

Port host local có thể override bằng environment để tránh xung đột máy cá nhân; port nội bộ/service contract trong Docker không đổi. Mọi override dùng chung phải được ghi trong `docker/.env` và không commit file này.

Mỗi service MUST chỉ dùng hostname/database/user thuộc storage của mình (`loan-mysql`, `payment-mysql`, `user-mysql`, `investment-mongo`, `payment-redis`). Không dùng một MySQL instance chung hoặc credential root làm runtime contract.
