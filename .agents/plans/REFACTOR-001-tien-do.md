# REFACTOR-001 — tiến độ (chạy tuần tự, mỗi lúc một agent)

Hải 04/11: chỉ một agent chạy tại một thời điểm để không hết session. Mỗi agent ghi thêm một dòng khi xong một bước.

## Đã xong
- Đợt 1 cả 7 module + gộp loan + tách domain (entity/enums/model): build clean xanh.
- Bộ Kafka dùng chung trong finora-common (115 test, đã install ~/.m2).
- Đợt 2 notification + blockchain (147 + 56 test).
- Đợt 2 loan (480 test).

## Hàng đợi (dở dang khi hết session 04/11 ~01:00)
1. finora-user đợt 2 (RestClient, resilience4j, ResponseEntity, đổi tên field) — đang chạy test sau bước 4.
2. finora-payment đợt 2 + chuyển sang bộ Kafka.
3. finora-investment đợt 2 + chuyển sang bộ Kafka.
4. finora-loan chuyển sang bộ Kafka (đang ở CicDelinquencyRelayConsumerTest).
5. finora-notification + finora-blockchain chuyển sang bộ Kafka.
6. Chuẩn hoá import (normalize_imports.py), build cả reactor `clean test`, sửa rule 03/04, điền Kết quả.

## Nhật ký
- 04/11/2026 — finora-user Đợt 2: Feign/RestTemplate → RestClient (token service account chỉ AI + Notification qua bean serviceAccountBearerInterceptor), resilience4j 5 instance (ai, notification, cic, log-analytics, keycloak-admin) + time limiter trên read-timeout, TransactionPinController trả ResponseEntity, đổi tên 78 field inject; `mvn -o -B -pl finora-user clean test` 503 test xanh (gốc 464).
- 04/11/2026 — finora-payment Đợt 2 + Kafka: 35 method controller trả ResponseEntity (giữ health BaseResponse; callback ZaloPay/mock-payout cùng body/status); resilience4j 4 instance (fineract-repayment-quote/-posting/-reconciliation, zalopay-topup) + time limiter 35s/20s, circuit mở/timeout/ngắt báo như mất kết nối (POST repayment vẫn OUTCOME_UNKNOWN, không hoàn tiền); Kafka chuyển sang com.finora.common.messaging (xoá KafkaEventEnvelope, publisher mỏng, 3 consumer dùng KafkaEventReader, error handler qua KafkaErrorHandlers giữ .payment.DLT); `mvn -o -B -pl finora-payment clean test` 129 test xanh (gốc 114).
- 04/11/2026 — finora-investment Đợt 2 + Kafka: 35 method controller trả ResponseEntity (bỏ qua SSE stream, tải PDF và retry vốn đã là ResponseEntity; 2 lệnh 201 giữ 201); StubPaymentClient/StubUserIdentityClient → Mock* (mode=stub giữ); resilience4j payment + user-identity (giá trị như loan, bỏ qua 4xx) + time limiter 35s, JWT đọc trên thread request, mạch mở/hết giờ/bị ngắt → "không kết nối được" (thử lại cùng khoá idempotency, không bao giờ thành từ chối chắc chắn); test domain về đúng gói entity; Kafka chuyển sang com.finora.common.messaging (xoá KafkaEventEnvelope, publisher mỏng giữ 3 chữ lỗi cũ, 8 consumer/10 listener dùng KafkaEventReader of(type,1), error handler qua KafkaErrorHandlers giữ .DLT); `mvn -o -B -pl finora-investment clean test` 126 test xanh (gốc 113).
- 04/11/2026 — finora-loan Đợt 2 + Kafka: 58 method controller trả ResponseEntity (bỏ qua 6 tải PDF + 3 method đã là ResponseEntity 201/202); LocalMockBorrowerProfileProvider → MockBorrowerProfileProvider; resilience4j thêm user-profile + vnpt-smartca (giá trị như fineract-product, time limiter 6s/17s, circuit mở/timeout/ngắt → USER_UNREACHABLE/SMARTCA_UNAVAILABLE); FineractIntegrationException/AiCreditIntegrationException về integration/<hệ thống>/, 2 bản CoreLoanBookingGateway vào client/impl/; Kafka chuyển sang com.finora.common.messaging (xoá KafkaEventEnvelope, publisher mỏng giữ mã/thông báo OutboxPublishException, 7 consumer dùng KafkaEventReader, relay CIC gửi nguyên envelope, 2 error handler qua KafkaErrorHandlers giữ .DLT/dlt-topic); `mvn -o -B -pl finora-loan clean test` 481 test xanh (gốc 476).
- 04/11/2026 — finora-notification + finora-blockchain Đợt 2 + Kafka: controller trả ResponseEntity (notification 9, blockchain 4; giữ health BaseResponse); resilience4j expo-push cho ExpoPushSender (+ time limiter 15s, circuit mở → EXPO_CIRCUIT_OPEN thử lại như Expo không với tới được), Fabric không gắn circuit (lý do trong báo cáo); Kafka chuyển sang com.finora.common.messaging (notification xoá KafkaEnvelope, InboundEventReader dùng readRaw anyVersion + data OBJECT, 3 listener nhận ConsumerRecord, error handler qua KafkaErrorHandlers giữ .notification.DLT + log; blockchain registrar readRaw of(type,1) + data OBJECT + occurredAt OPTIONAL, hash không đổi, error handler giữ .DLT); `clean test` notification 149 (gốc 139), blockchain 58 (gốc 56) xanh.
- 04/11/2026 — Bước cuối: normalize_imports.py sửa 857 file; rule 03/04 cập nhật theo chuẩn; `mvn -o -B -fae clean install -DskipITs` cả reactor xanh (common 115, gateway 71, loan 481, payment 129, blockchain 58, investment 126, user 503, notification 149). REFACTOR-001 READY_FOR_REVIEW.
