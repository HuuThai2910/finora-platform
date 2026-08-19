# Registry cố định

Thay đổi giá trị trong bảng MUST cập nhật cấu hình, tài liệu liên quan và file này trong cùng change. Không tự đổi giá trị chỉ để tránh conflict local.

| Thành phần                    |                                 Port | Storage/định danh                                                                                                                                             |
| ----------------------------- | -----------------------------------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `finora-gateway`              |                                 8080 | Không có DB                                                                                                                                                   |
| `finora-loan`                 |                                 8081 | PostgreSQL/Neon Project `finora-loan`, DB `neondb`, default role của project                                                                                  |
| `finora-payment`              |                                 8082 | PostgreSQL/Neon Project `finora-payment`, DB `neondb`, default role của project; Redis nội bộ 6379                                                            |
| `finora-blockchain`           |                                 8083 | PostgreSQL/Neon Project `finora-blockchain`, DB `neondb`, default role của project; Fabric channel `finora-channel`, chaincode `finora-ledger`, MSP `Org1MSP` |
| `finora-investment`           |                                 8084 | PostgreSQL/Neon Project `finora-investment`, DB `neondb`, default role của project                                                                            |
| `finora-user`                 |                                 8085 | PostgreSQL/Neon Project `finora-user`, DB `neondb`, default role của project                                                                                  |
| `finora-notification`         |                                 8086 | Kafka consumer + SMTP; chưa có DB                                                                                                                             |
| `finora-ai`                   |                                 8000 | FastAPI; chưa có DB                                                                                                                                           |
| Keycloak                      |                                 8180 | OIDC; PostgreSQL 17 database/credential/volume riêng, không publish DB port                                                                                   |
| Kafka                         | 9092 host local; 29092 nội bộ Docker | Zookeeper 2181                                                                                                                                                |
| Loan PostgreSQL offline       |        15433 host local; 5432 nội bộ | PostgreSQL 17, `loan-postgres`, volume/user riêng                                                                                                             |
| Payment PostgreSQL offline    |        15434 host local; 5432 nội bộ | PostgreSQL 17, `payment-postgres`, volume/user riêng                                                                                                          |
| Blockchain PostgreSQL offline |        15435 host local; 5432 nội bộ | PostgreSQL 17, `blockchain-postgres`, volume/user riêng                                                                                                       |
| User PostgreSQL offline       |        15436 host local; 5432 nội bộ | PostgreSQL 17, `user-postgres`, volume/user riêng                                                                                                             |
| Investment PostgreSQL offline |        15437 host local; 5432 nội bộ | PostgreSQL 17, `investment-postgres`, volume/user riêng                                                                                                       |
| Payment Redis                 |         6380 host local; 6379 nội bộ | Redis 7, password/volume riêng của Payment                                                                                                                    |
| Apache Fineract 1.15.0        |   18443 host local; 8443 nội bộ HTTP | Tenant `default`; API prefix `/fineract-provider/api/v1`                                                                                                      |
| Fineract PostgreSQL           |        15432 host local; 5432 nội bộ | PostgreSQL 18.3 riêng; `fineract_tenants`, `fineract_default`                                                                                                 |

Consumer group hiện có: `payment-group`, `blockchain-group`, `notification-group`.

CURRENT STATE: Fineract 1.15.0/PostgreSQL 18.3 local đã healthy và tenant authentication
smoke pass ngày 2026-08-03; live Product/schedule contract còn chờ kiểm thử. Chưa có
MinIO, MailHog hoặc Fabric network/chaincode.

## Phân bổ dự kiến — chưa phải thành phần đang chạy

| Thành phần                |              Port dự kiến | Storage/định danh             | Trạng thái                               |
| ------------------------- | ------------------------: | ----------------------------- | ---------------------------------------- |
| Fineract managed database | Endpoint qua secret store | Neon/PostgreSQL Project riêng | `PLANNED`; local fixture phải pass trước |

Fineract 1.15 dùng PostgreSQL; fixture release 1.15.0 được pin PostgreSQL 18.3. Fineract và Loan MUST dùng Project/database/credential/lifecycle riêng dù cùng dùng PostgreSQL.

Port host local có thể override bằng environment để tránh xung đột máy cá nhân; port nội bộ/service contract trong Docker không đổi. Mọi override dùng chung phải được ghi trong `docker/.env` và không commit file này.

Mỗi service MUST chỉ dùng endpoint/database/credential thuộc storage của mình (`loan-postgres`, `payment-postgres`, `blockchain-postgres`, `user-postgres`, `investment-postgres`, `payment-redis`). Không dùng chung Neon Project hoặc lấy connection string project khác. Trong môi trường Neon khóa luận một owner, mỗi service MAY dùng default role do đúng project đó cấp để giảm cấu hình; trước production thật MUST tạo runtime role giới hạn quyền. Docker fallback và Keycloak PostgreSQL vẫn MUST dùng runtime role không có superuser.
