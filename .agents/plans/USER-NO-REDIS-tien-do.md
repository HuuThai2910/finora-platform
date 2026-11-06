# USER-NO-REDIS — bỏ Redis khỏi finora-user (tiến độ)

Mục tiêu: chuyển mọi trạng thái tạm của finora-user (OTP, giới hạn tốc độ, đăng ký tạm, bản nháp/thử thách/bộ đếm
eKYC) từ Redis sang PostgreSQL riêng của User qua hai bảng `user_temporary_entries`, `user_rate_counters`.
Chỉ sửa `finora-user/`; không đụng docker, deploy, module khác. Không commit.

| # | Bước | Kết quả | Test |
|---|------|---------|------|
| 0 | Đọc chuẩn REFACTOR-001 §2, rules 03/04; rà 6 nơi dùng Redis, RedisConfig, yml, ITs | Xong. Phát hiện AuthServiceImpl `@Transactional` cấp class và nhiều method `readOnly = true` gọi store, EkycSessionCleanup gọi sau commit → TemporaryStoreImpl phải chạy `REQUIRES_NEW`. Docker Desktop không chạy. | — |
| 1 | Chạy test gốc (`clean test`) | BUILD SUCCESS (lần đầu lỗi "bad class file" do VS Code tự build cùng lúc, chạy lại thì xanh) | 503 test, 0 lỗi |
| 2 | Migration `V9__create_temporary_store.sql` | Tạo `user_temporary_entries`, `user_rate_counters` + index `expires_at` | — |
| 3 | `TemporaryStore` + `TemporaryStoreImpl` (JDBC, Clock, `REQUIRES_NEW`) + `TemporaryStoreCleanupWorker(Impl)` (`@Scheduled`, mặc định 5 phút) | Xong | — |
| 4 | Chuyển 6 nơi (RateLimitServiceImpl, PendingRegistrationStoreImpl, EkycDraftStoreImpl, EkycChallengeStoreImpl, EkycCounterStoreImpl, EkycRateLimitServiceImpl) sang `TemporaryStore`, giữ key/TTL/giá trị; sửa Javadoc/comment nhắc Redis | Xong | — |
| 5 | Bỏ `spring-boot-starter-data-redis`, xoá `RedisConfig`, bỏ `spring.data.redis.*` và `redis` khỏi readiness; thêm `finora.temporary-store.*` | Xong | — |
| 6 | Test: viết lại 3 test store theo mock `TemporaryStore`, thêm RateLimitServiceImplTest, PendingRegistrationStoreImplTest, EkycChallengeStoreImplTest, TemporaryStoreCleanupWorkerImplTest; sửa UserHealthProbesIT; thêm TemporaryStoreImplIT | `clean test` BUILD SUCCESS | 525 test, 0 lỗi (gốc 503) |
| 7 | Kiểm SQL thật: Docker Desktop không chạy nên IT chưa chạy; dùng cụm PostgreSQL 18 tạm (initdb trong scratch, cổng 55439, xoá sau khi xong) + chương trình kiểm tra ngoài repo: Flyway V1..V9, TTL, cửa sổ, tăng đồng thời 2 luồng, putIfAbsent đồng thời, dọn, context Spring không Redis, REQUIRES_NEW trong transaction readOnly rollback, worker @Scheduled | 30/30 PASS | — |
| 8 | Sửa comment test còn nhắc Redis; chạy lại `clean test` lần cuối | BUILD SUCCESS. IT (`TemporaryStoreImplIT`, `UserHealthProbesIT`) đã biên dịch, chờ chạy `verify` khi bật Docker | 525 test, 0 lỗi |
