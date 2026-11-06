# REFACTOR-001 — Chuẩn hoá cấu trúc code các service Java theo finora-user

> Trạng thái: READY_FOR_REVIEW (04/11/2026). Người yêu cầu: Hải. Không commit, không push.

## 1. Mục tiêu

Các service Java đang lệch nhau về cây thư mục, cách tách interface/triển khai, cách đặt tên và cách viết.
finora-user (sau commit `d139a72 change folder structure`) và finora-investment đã theo một mẫu; mục tiêu là
đưa finora-loan, finora-payment, finora-notification, finora-blockchain, finora-gateway về đúng mẫu đó và sửa
các chỗ còn lệch ngay trong finora-user/finora-investment.

Đây là refactor **giữ nguyên hành vi**: không đổi API, JSON, Kafka, DB, cấu hình hay logic nghiệp vụ.

## 2. Chuẩn

### 2.1. Cây package

```text
com.finora.<service>/
├── config/                 # @Configuration, *Properties, cấu hình Feign/RestClient/Kafka
├── controller/             # *Controller
├── service/                # interface: hợp đồng use case, worker, store, publisher... (+ record/enum đi kèm hợp đồng)
│   ├── impl/               # *Impl: class Spring thật (@Service/@Component)
│   └── <nhóm>/             # khi layer lớn: interface ở <nhóm>/, triển khai ở <nhóm>/impl/
├── domain/                 # entity, enum, value object (chia <nhóm>/ khi lớn)
├── repository/             # Spring Data repository, row projection
├── dto/
│   ├── request/            # *Request
│   └── response/           # *Response (service lớn: dto/<nhóm>/request|response)
├── mapper/                 # chuyển entity/domain <-> DTO
├── integration/<hệ thống>/
│   ├── client/             # Feign/RestClient/SDK adapter gọi hệ thống ngoài (+ client/impl/ khi có nhiều bản)
│   ├── contract/           # payload trao đổi với hệ thống đó
│   └── mapper/             # mapping riêng của integration (nếu có)
├── messaging/
│   ├── consumer/           # @KafkaListener
│   ├── producer/           # publisher/outbox relay đẩy Kafka
│   └── event/              # payload, envelope sự kiện
├── exception/              # exception nghiệp vụ, exception handler riêng service
└── util/                   # tiện ích riêng service: static helper, bean hạ tầng nhỏ (AuthCookieManager, CryptoConverter)
```

- Tiện ích dùng chung nhiều service nằm ở finora-common, gói `com.finora.common.util`.
- **Không dùng**: `client/` ở gốc service, `support/`, `messaging/` để phẳng, `dto/common/`, class Spring nằm thẳng
  trong `service/` mà không có interface.
- Exception gắn với một integration được nằm trong `integration/<hệ thống>/` (như `EkycStorageException`);
  exception nghiệp vụ dùng nhiều nơi nằm ở `exception/`.
- Class phụ trợ chỉ phục vụ các Impl (không phải bean) nằm trong `impl/` cạnh Impl dùng nó (như `ActivityLogKql`,
  `OrderBookMapper`).
- Cấu hình Feign dùng riêng cho vài client mà cố ý không gắn `@Configuration` (như `ServiceAccountFeignConfig`)
  nằm ở `config/` và vẫn không gắn `@Configuration`.

### 2.2. Mẫu interface + Impl (bắt buộc cho mọi bean `@Service`/`@Component` trong `service/**`)

Interface ở `service/` (hoặc `service/<nhóm>/`), tên giữ nguyên tên class cũ để nơi gọi không phải đổi:

```java
package com.finora.user.service;

/**
 * Worker định kỳ đăng ký ánh xạ người dùng đã xác minh sang CIC (bật bằng {@code finora.cic.mapping.enabled}).
 */
public interface CicMappingWorker {

    /** Một vòng: xử lý lần lượt các task đến hạn, task lỗi không chặn task sau. */
    void run();
}
```

Triển khai ở `service/impl/` (hoặc `service/<nhóm>/impl/`), tên = tên interface + `Impl`:

```java
package com.finora.user.service.impl;

/**
 * Worker định kỳ đăng ký ánh xạ người dùng đã xác minh sang CIC (bật bằng {@code finora.cic.mapping.enabled}).
 * <p>
 * (Chi tiết cách làm: chỉ ghi ở Impl.)
 */
@Component
@ConditionalOnProperty(prefix = "finora.cic.mapping", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CicMappingWorkerImpl implements CicMappingWorker {

    private final CicMappingTaskRepository cicMappingTaskRepository;

    @Override
    @Scheduled(fixedDelayString = "${finora.cic.mapping.worker-delay:5000}")
    public void run() {
        ...
    }
}
```

Quy tắc:

- Interface khai báo mọi method public của class cũ mà class khác hoặc framework gọi (kể cả method `@Scheduled`,
  `@EventListener`, `@TransactionalEventListener`); Javadoc của method chuyển lên interface.
- Javadoc class: interface ghi trách nhiệm (một đoạn tiếng Việt); Impl lặp lại câu tóm tắt và ghi thêm chi tiết
  cách làm. Thêm Javadoc tiếng Việt cho interface mới nếu class cũ chưa có.
- Record/enum/hằng số mà nơi gọi dùng qua tên class (`X.Result`, `X.SOME_CONSTANT`) chuyển lên interface
  (nested type hoặc hằng của interface) để nơi gọi giữ nguyên `X.Result`. Static helper mà nơi gọi dùng
  (`StaffRoleService.permissionsOf(...)`) để thành static method của interface.
- Annotation Spring (`@Service`/`@Component`, `@ConditionalOnProperty`, `@Transactional`, `@Scheduled`,
  `@KafkaListener`...) giữ nguyên trên Impl, đúng vị trí cũ (class hay method). Impl không được `final`.
- Mỗi interface đúng một Impl, trừ khi đã có sẵn nhiều bản (Mock/Stub/Http...). Interface có sẵn nhiều bản
  thì chỉ chuyển các bản triển khai vào `impl/`.
- Class đã tên `*Impl` hoặc đã có interface thì chỉ kiểm tra đúng vị trí.
- Controller, consumer Kafka, producer, config, mapper, repository, integration client: **không** tách interface.

### 2.3. Cách viết

- Inject qua constructor bằng `@RequiredArgsConstructor` + `private final`; không `@Autowired` trên field. Cần
  `@Qualifier`/`@Value` trên tham số thì viết constructor tay.
- Log bằng `@Slf4j`, không `LoggerFactory.getLogger`.
- Javadoc và comment tiếng Việt như finora-user; không xoá comment đang có, sửa nếu nó tả sai sau khi đổi tên.
- Không đổi ngữ nghĩa `@Transactional` (không thêm/bớt `readOnly`, không chuyển từ method lên class).
- Thứ tự import theo finora-user (nhóm còn lại → `javax.*`/`java.*` → `static`): **không cần sửa tay**, script
  chuẩn hoá chạy cuối cùng.
- DTO dạng `record` hay class Lombok, mapper MapStruct hay viết tay: finora-user dùng cả hai, **giữ nguyên**.

### 2.4. Đặt tên

- Field inject đặt theo kiểu, viết camelCase: `private final FinoraLoanRepository finoraLoanRepository;`
  (không `loans`, `repository`, `repo`). Hai field cùng kiểu thì thêm tiền tố nói rõ vai trò.
- Method là động từ + đối tượng, nói đúng việc làm (`listStaleProjections`, `cancelExpiredHolds`); boolean bắt
  đầu `is/has/can/should`; hằng `UPPER_SNAKE_CASE`.
- Biến cục bộ không viết tắt khó hiểu (`res`, `tmp`, `obj`, `val`, `cnt`, `req`, `resp`, `dto`, `x`, `a`, `list`,
  `map`, `data`...). Tham số lambda một dòng được ngắn nếu rõ nghĩa; `e` trong `catch` giữ như finora-user.
- Chỉ đổi tên khi tên hiện tại mơ hồ, viết tắt hoặc tả sai; tên đã rõ thì giữ, không đổi cho có.
- Class theo hậu tố của layer: `*Controller`, `*Service`/`*ServiceImpl`, `*Worker`/`*WorkerImpl`, `*Repository`,
  `*Mapper`, `*Request`, `*Response`, `*Properties`, `*Exception`, `*Client`, `*Consumer`, `*Publisher`.

### 2.5. Test

- Test phản chiếu package production: test của `XImpl` nằm ở `.../impl/XImplTest.java`, class `XImplTest`.
- `@InjectMocks X`, `new X(...)` của class đã tách → đổi sang `XImpl`; `@Mock`/`@MockBean X` giữ kiểu interface.
- Không xoá, không tắt test; không đổi tên method test.

### 2.6. Không được đổi (hợp đồng)

- Đường dẫn REST, HTTP method, status code, mã lỗi, nội dung thông báo lỗi.
- Tên field của DTO, record sự kiện, payload integration (đổi là đổi JSON).
- Tên và field của entity (đổi là đổi bảng/cột); migration Flyway.
- Tên method Spring Data repository (derived query), tên method `@Bean` được `@Qualifier` gọi, key cấu hình,
  topic Kafka, cron/delay key.
- Tên bean được tham chiếu bằng chuỗi (SpEL `@bean.method`, `@Qualifier("...")`, `@ConditionalOnBean(name=...)`):
  tách interface làm tên bean mặc định đổi từ `xService` sang `xServiceImpl`, phải grep và giữ đúng.
- Không sửa finora-common, pom gốc, `docker/`, `.agents/` (trừ plan này), module khác module được giao.
- Không dùng lệnh git đổi index/HEAD (`git add/mv/rm/commit/stash/checkout/reset/clean`); di chuyển file bằng
  thao tác file thường.

### 2.7. Kiểm chứng

- finora-common đã `mvn install` vào `~/.m2`; mỗi module build độc lập: `mvn -o -B -pl finora-<service> test`
  (bỏ `-o` nếu thiếu dependency). Không dùng `-am`, không `clean` module khác.
- Trước khi sửa: chạy một lần để lấy số test gốc. Sau khi sửa: build xanh, số test ≥ số gốc.
- Grep không còn package cũ (`.support.`, `.client.` ở gốc, `messaging.<Class>` phẳng...).
- Cuối cùng chạy cả reactor `mvn -B test` sau khi gộp và chuẩn hoá import.

## 3. Việc theo module

| Module | Hiện trạng | Việc |
|---|---|---|
| finora-user | Mẫu chuẩn; còn `client/` ở gốc | `client/AiEkycClient`, `AiLivenessClient` → `integration/ai/client/`; `client/ServiceAccountFeignConfig` → `config/`; rà tên |
| finora-investment | Đã tách Impl; còn `client/` + `client/impl/` ở gốc | → `integration/payment/…`, `integration/user/…` (client/contract/impl); rà tên, vị trí exception |
| finora-loan | 52 bean trong `service/**` chưa tách; `support/`; `dto/common/`; integration lẫn | Tách interface + Impl; `support/` → `util/`; `dto/common/` → `dto/response/`; integration `cic/`, `signature/`, `profile/` theo client/contract; đổi tên field/biến; `LoggerFactory` → `@Slf4j` |
| finora-payment | 22 bean chưa tách; `messaging/` phẳng; integration chưa chia client/contract; không có `exception/` | Tách interface + Impl; `messaging/{consumer,producer,event}`; integration theo client/contract; gom exception; đổi tên |
| finora-notification | 25 bean chưa tách; `support/`; một số đã có `service/impl/` | Tách interface + Impl; `support/` → `util/`; `integration/push/` theo client/contract; đổi tên |
| finora-blockchain | 7 bean chưa tách; `messaging/` phẳng | Tách interface + Impl; `messaging/{consumer,event}` (+ producer nếu có); đổi tên |
| finora-gateway | `apilog/` gom mọi thứ | Filter → `filter/`, cấu hình/properties → `config/`, helper → `util/`; đổi tên |

finora-loan chia hai agent trên hai git worktree (A: application, collection, contract, core, decision,
disbursement, funding; B: phần còn lại của `service/**` + mọi layer ngoài `service/`), gộp lại sau.

### Bước sau khi gộp (làm tuần tự, không giao agent song song)

- Gửi/nhận Kafka thống nhất (Hải muốn mọi service gửi một kiểu, nhận một kiểu; 03/11). Định dạng trên Kafka đã
  thống nhất (key = aggregateId, value = envelope JSON, header `finora-event-id|type|version`) nhưng code bị chép:
  3 publisher (loan, investment gần như y hệt; payment viết dồn dòng), ~22 consumer tự parse envelope và kiểm
  header, 5 cấu hình error handler. Đưa lên finora-common gói `com.finora.common.messaging`:
  - `KafkaEventEnvelope` (thay 6 bản ở loan ×2, payment, investment, notification `KafkaEnvelope`) và hằng header;
  - bên gửi: một class dựng `ProducerRecord` + header + timeout + phân loại lỗi; outbox relay từng service gọi nó;
  - bên nhận: một class đọc payload + header → sự kiện có `eventId`, `occurredAt`, `version`, `type`, `data` đã đổi
    sang record của service; consumer chỉ còn nghe topic → đọc → gọi handler;
  - hàm dựng error handler có DLT; backoff vẫn do từng service truyền như hiện tại.
  Giữ nguyên định dạng trên Kafka, key, header, retry, DLT và cách phân loại lỗi retry/không retry.
- Payload sự kiện, outbox, `processed_events`, consumer, producer vẫn ở từng service (rule 07: common không giữ
  domain model, entity, repository; mỗi bên nhận chỉ khai trường nó cần).
- Redis giữ nguyên ở finora-user (chỉ user dùng). Hải chốt 03/11.

### Bộ Kafka dùng chung — cách dùng

> 04/11: bộ công cụ đã có ở finora-common gói `com.finora.common.messaging` (`mvn -o -B -pl finora-common clean
> install` xanh, 115 test, 33 test mới). Chưa service nào chuyển. Chuyển từng service một, mỗi service xong chạy
> `mvn -o -B -pl finora-<service> test`. Không đổi topic, key, header, groupId, containerFactory, record payload.

**Bộ công cụ**

| Class | API công khai | Thay cho |
|---|---|---|
| `KafkaEventEnvelope` | `record (UUID eventId, String occurredAt, int version, JsonNode data)` | `KafkaEventEnvelope` ở loan, payment, investment, record lồng `KafkaEnvelope` trong publisher investment, `KafkaEnvelope` ở notification (JSON giống từng byte) |
| `FinoraKafkaHeaders` | `EVENT_ID`, `EVENT_TYPE`, `EVENT_VERSION`, `TRACE_ID` | hằng `*_HEADER` và chuỗi `"finora-event-*"` |
| `KafkaOutgoingEvent` | `record (String topic, String key, UUID eventId, String eventType, int eventVersion, Instant occurredAt, String payloadJson, String traceId)`; `traceId == null` thì không gắn header trace | |
| `KafkaEventPublisher` | `KafkaEventPublisher(KafkaOperations<String, String>, ObjectMapper)`; `void publish(KafkaOutgoingEvent, Duration ackTimeout)`; `ProducerRecord<String, String> toProducerRecord(KafkaOutgoingEvent)` | thân 3 publisher |
| `KafkaPublishException` | `failure()`, `code()`, `getMessage()`, `retryable()`, `getCause()` | |
| `KafkaPublishFailure` | `PAYLOAD_NOT_OBJECT`, `PAYLOAD_UNREADABLE` (cùng mã `OUTBOX_PAYLOAD_INVALID`), `INTERRUPTED`, `ACK_TIMEOUT`, `REJECTED`, `TEMPORARILY_UNAVAILABLE`, `FAILED`; mỗi giá trị có `code()`, `message()`, `retryable()` đúng chữ của Loan | |
| `KafkaEventReader` | `KafkaEventReader(ObjectMapper)`; `<T> KafkaEvent<T> read(ConsumerRecord<String, String>, KafkaEventContract, Class<T>)`; `KafkaEvent<JsonNode> readRaw(ConsumerRecord<String, String>, KafkaEventContract)` | parse + kiểm envelope/header ở 18 consumer (20 listener) của Loan/Investment/Payment, phần kiểm của `InboundEventReader` (Notification) và `MilestoneProofRegistrar` (Blockchain) |
| `KafkaEvent<T>` | `record (UUID eventId, Instant occurredAt, int version, String eventType, String traceId, T data, KafkaEventEnvelope envelope)` | |
| `KafkaEventContract` | `of(String eventType, int version, int... otherVersions)`, `anyVersion(String eventType)`, `withHeaderRule(HeaderRule)`, `withEventIdRequired(boolean)`, `withOccurredAtRule(OccurredAtRule)`, `withDataRule(DataRule)`, `acceptsVersion(int)` | |
| `KafkaErrorHandlers` | `deadLetterErrorHandler(KafkaOperations<?, ?>, BackOff, KafkaDeadLetterTopic, Class<? extends Exception>...)`; bản thêm `BiConsumer<ConsumerRecord<?, ?>, Exception> beforeDeadLetter` trước danh sách exception; `deadLetterRecoverer(...)`; `rootCauseSimpleName(Throwable)` | thân 6 bean `DefaultErrorHandler`, 2 hàm `rootType` |
| `KafkaDeadLetterTopic` | `suffixKeepPartition(String)`, `suffixAnyPartition(String)`, `fixedKeepPartition(String)` | 3 kiểu lambda đặt tên DLT |

Mức kiểm của `KafkaEventContract` (mọi vi phạm ném `IllegalArgumentException`; enum lồng import từ
`com.finora.common.messaging.KafkaEventContract.HeaderRule|OccurredAtRule|DataRule`):

| Phần | `of(...)` / `anyVersion(...)` | Biến thể |
|---|---|---|
| Header | `IF_PRESENT`: không bắt buộc; có thì id là UUID bằng `eventId`, type bằng `eventType`, version là số nguyên bằng `version` của envelope; giá trị được `trim()` | `REQUIRED` (3 header phải có); `TYPE_ONLY` (chỉ header type, kiểm trước khi đọc JSON) |
| `eventId` | bắt buộc | `withEventIdRequired(false)` |
| `version` | `of`: đúng các version liệt kê; `anyVersion`: từ 1 trở lên | |
| `occurredAt` | `REQUIRED`: có, đọc được bằng `Instant.parse`, không được thì `OffsetDateTime.parse` | `OPTIONAL` (thiếu/null thì `occurredAt()` null); `IGNORED` (không đọc, luôn null) |
| `data` | `REQUIRED`: có và khác JSON `null` | `OBJECT` (phải là JSON object); `OPTIONAL` (thiếu/null thì `data()` null) |

**Quy tắc chung**

1. finora-common không khai bean (user, gateway quét `com.finora.common` nhưng không có spring-kafka). Service tự khai
   trong class cấu hình Kafka sẵn có:
   ```java
   @Bean
   KafkaEventReader kafkaEventReader(ObjectMapper objectMapper) {
       return new KafkaEventReader(objectMapper);
   }

   @Bean // chỉ service gửi event: loan (trong KafkaOutboxConfiguration), investment, payment
   KafkaEventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
       return new KafkaEventPublisher(kafkaTemplate, objectMapper);
   }
   ```
2. Listener nhận đúng một tham số `ConsumerRecord<String, String> record` (bỏ `@Payload`/`@Header`; Spring đọc header
   UTF-8, header trùng tên lấy cái cuối, giống `lastHeader`). Giữ nguyên mọi thuộc tính `@KafkaListener` (`topics`, `id`,
   `groupId`, `containerFactory`, `autoStartup`) và `@ConditionalOnProperty`. Hợp đồng là hằng
   `private static final KafkaEventContract CONTRACT`; consumer bỏ `ObjectMapper` nếu chỉ dùng để parse.
3. Event sai hợp đồng ném đúng `IllegalArgumentException`, không dùng lớp con: cả 6 error handler đánh dấu IAE không
   retry, và `PaymentKafkaDeadLetterIT` kiểm header DLT `kafka_dlt-exception-cause-fqcn` là
   `java.lang.IllegalArgumentException`. Message dạng `<eventType> trên topic <topic>: <lý do>`, không chứa payload,
   không gắn exception Jackson làm cause (message Jackson 2.15 có trích JSON gốc).
4. `KafkaEventPublisher` không log; service giữ dòng log của mình. Allowlist topic và lỗi `KAFKA_EVENT_ROUTE_MISSING`
   vẫn ở service, kiểm trước khi gọi `publish`.
5. Test của service dựng record bằng helper (thay cho `consume(payload, id, type, version)`):
   ```java
   private static ConsumerRecord<String, String> record(String payload, String id, String type, String version) {
       ConsumerRecord<String, String> record = new ConsumerRecord<>("finora.test", 0, 0L, null, payload);
       addHeader(record, FinoraKafkaHeaders.EVENT_ID, id);
       addHeader(record, FinoraKafkaHeaders.EVENT_TYPE, type);
       addHeader(record, FinoraKafkaHeaders.EVENT_VERSION, version);
       return record;
   }

   private static void addHeader(ConsumerRecord<String, String> record, String name, String value) {
       if (value != null) {
           record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
       }
   }
   ```

**Mẫu consumer** (loan `DisbursementCompletedConsumer`). Trước:

```java
@KafkaListener(topics = "${finora.loan.kafka.disbursement-completed-topic}")
public void consume(@Payload String payload,
        @Header(name = "finora-event-id", required = false) String id,
        @Header(name = "finora-event-type", required = false) String type,
        @Header(name = "finora-event-version", required = false) String version) {
    try {
        KafkaEventEnvelope envelope = objectMapper.readValue(payload, KafkaEventEnvelope.class);
        requireValidEnvelope(envelope, id, type, version); // eventId/data/occurredAt khác null, version 1, header khớp
        disbursementSagaService.handleCompleted(envelope.eventId(), Instant.parse(envelope.occurredAt()),
                objectMapper.treeToValue(envelope.data(), DisbursementCompletedEventData.class));
    } catch (JsonProcessingException exception) {
        throw new IllegalArgumentException("DisbursementCompleted không đúng JSON contract", exception);
    }
}
```

Sau:

```java
@Component
@RequiredArgsConstructor
public class DisbursementCompletedConsumer {

    private static final KafkaEventContract CONTRACT = KafkaEventContract.of("DisbursementCompleted", 1);

    private final KafkaEventReader kafkaEventReader;
    private final DisbursementSagaService disbursementSagaService;

    @KafkaListener(topics = "${finora.loan.kafka.disbursement-completed-topic}")
    public void consume(ConsumerRecord<String, String> record) {
        KafkaEvent<DisbursementCompletedEventData> event =
                kafkaEventReader.read(record, CONTRACT, DisbursementCompletedEventData.class);
        disbursementSagaService.handleCompleted(event.eventId(), event.occurredAt(), event.data());
    }
}
```

**finora-loan**

- Xoá `messaging/event/KafkaEventEnvelope.java`; `integration/cic/client/CicDelinquencyRelayHttpClient` và test của nó
  import `com.finora.common.messaging.KafkaEventEnvelope` (CIC nhận đúng JSON cũ). Xoá 4 hằng `*_HEADER` của
  `KafkaOutboxPublisher`; `KafkaOutboxPublisherTest` dùng `FinoraKafkaHeaders.*` và
  `new KafkaOutboxPublisher(new KafkaEventPublisher(template, objectMapper), properties)`.
- `KafkaOutboxPublisher` giữ `OutboxPublishException` vì `OutboxRelayServiceImpl` ghi `errorCode()`, `getMessage()`,
  `retryable()` vào `last_error_code`/`last_error_detail`:
  ```java
  @RequiredArgsConstructor
  @Slf4j
  public class KafkaOutboxPublisher implements OutboxPublisher {

      private final KafkaEventPublisher kafkaEventPublisher;
      private final KafkaPublisherProperties kafkaPublisherProperties;

      @Override
      public void publish(OutboxMessage message) {
          String topic = kafkaPublisherProperties.topicFor(message.eventType(), message.eventVersion());
          if (topic == null) {
              throw new OutboxPublishException("KAFKA_EVENT_ROUTE_MISSING",
                      "Event/version chưa có Kafka route được duyệt", false, null);
          }
          try {
              kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic, message.aggregateId(), message.eventId(),
                      message.eventType(), message.eventVersion(), message.occurredAt(), message.payloadJson(),
                      message.traceId()), kafkaPublisherProperties.publishTimeout());
          } catch (KafkaPublishException exception) {
              throw new OutboxPublishException(exception.code(), exception.getMessage(), exception.retryable(),
                      exception.getCause());
          }
          log.info("Đã publish Kafka event: eventId={}, eventType={}, version={}, topic={}",
                  message.eventId(), message.eventType(), message.eventVersion(), topic);
      }
  }
  ```
  `KafkaOutboxConfiguration` (giữ `@ConditionalOnProperty(name = "finora.loan.outbox.transport", havingValue = "kafka")`):
  thêm bean `KafkaEventPublisher`; `OutboxPublisher kafkaOutboxPublisher(KafkaEventPublisher kafkaEventPublisher,
  KafkaPublisherProperties properties)` trả `new KafkaOutboxPublisher(kafkaEventPublisher, properties)`.
- `LoanKafkaConsumerConfiguration` (+ bean `KafkaEventReader`):
  ```java
  @Bean
  DefaultErrorHandler loanKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
      return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, new FixedBackOff(1_000L, 2L),
              KafkaDeadLetterTopic.suffixKeepPartition(".DLT"),
              IllegalArgumentException.class, LoanBusinessException.class);
  }
  ```
- `CicDelinquencyRelayConfiguration.cicDelinquencyKafkaErrorHandler`: dựng `ExponentialBackOffWithMaxRetries` y như cũ
  rồi `return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, backOff,
  KafkaDeadLetterTopic.fixedKeepPartition(properties.dltTopic()), IllegalArgumentException.class,
  CicDelinquencyRelayException.Permanent.class);`. Giữ tên bean `cicDelinquencyKafkaErrorHandler` (`@Qualifier` gọi).
- Consumer:

| Consumer | Hợp đồng | Ghi chú |
|---|---|---|
| `DisbursementCompletedConsumer` | `of("DisbursementCompleted", 1)` | `handleCompleted(event.eventId(), event.occurredAt(), event.data())` |
| `DisbursementFailedConsumer` | `of("DisbursementFailed", 1)` | `handleFailed(...)` |
| `LoanFullyFundedConsumer` | `of("LoanFullyFunded", 2)` | giữ `@ConditionalOnProperty(finora.loan.kafka.consumer-enabled)` |
| `LoanFundingExpiredConsumer` | `of("LoanFundingExpired", 1)` | như trên |
| `LoanRescheduleConsentCompletedConsumer` | `of("LoanRescheduleConsentCompleted", 1)` | như trên |
| `RepaymentDistributedConsumer` | `of("RepaymentDistributed", 1)` | |
| `CicDelinquencyRelayConsumer` | `of("LoanDelinquencyChanged", 1)` | `read(..., LoanDelinquencyChangedEventData.class)`, giữ `validateData(event.data())`, rồi `relay(event.envelope())`: gửi envelope đúng như đã đọc, không dựng lại vì `occurredAt` phải giữ nguyên chuỗi; giữ `groupId`, `containerFactory` |

**finora-investment**

- Xoá `messaging/event/KafkaEventEnvelope.java`, record lồng `KafkaEnvelope` và 4 hằng header của
  `InvestmentKafkaOutboxPublisher`.
- `InvestmentKafkaOutboxPublisher(KafkaEventPublisher kafkaEventPublisher, InvestmentKafkaProperties
  investmentKafkaProperties)`: giữ allowlist 6 cặp type/version (ném `KAFKA_EVENT_ROUTE_MISSING`) và `switch` chọn
  topic, rồi gọi bộ chung; ba thông báo Investment viết khác Loan nên đổi lại cho đúng chữ cũ trong `last_error_detail`:
  ```java
  try {
      kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic, message.aggregateId(), message.eventId(),
              message.eventType(), message.eventVersion(), message.occurredAt(), message.payloadJson(),
              message.traceId()), investmentKafkaProperties.publishTimeout());
  } catch (KafkaPublishException exception) {
      throw new InvestmentOutboxPublishException(exception.code(), legacyMessage(exception),
              exception.retryable(), exception.getCause());
  }

  private static String legacyMessage(KafkaPublishException exception) {
      return switch (exception.failure()) {
          case ACK_TIMEOUT -> "Kafka chưa xác nhận trong thời gian cho phép";
          case REJECTED -> "Kafka từ chối event do cấu hình/dữ liệu";
          case PAYLOAD_UNREADABLE -> "Không thể tạo Kafka envelope";
          default -> exception.getMessage();
      };
  }
  ```
  Không log khi gửi xong (như cũ). `InvestmentKafkaOutboxConfiguration`: thêm bean `KafkaEventPublisher`, trả
  `new InvestmentKafkaOutboxPublisher(kafkaEventPublisher, properties)`.
- `InvestmentKafkaConsumerConfiguration`: như Loan với `IllegalArgumentException.class, InvestmentDomainException.class`;
  + bean `KafkaEventReader`.
- Consumer, tất cả `of(<type>, 1)` và handler nhận `(event.eventId(), event.occurredAt(), event.data())`:
  `InvestorSignatureRequestedConsumer` (`"InvestorSignatureRequested"`, `handleSignatureRequested`),
  `LoanContractActivatedConsumer` (`"LoanContractActivated"`, `handleActivated`), `LoanDisbursedConsumer`,
  `LoanFundingCancelledConsumer`, `LoanFundingRequestedConsumer`, `LoanRescheduleConsentRequestedConsumer`,
  `RepaymentDistributedConsumer`, và `LoanServicingLifecycleConsumer` với ba hằng cho `settled` (`"LoanSettled"`),
  `rescheduled` (`"LoanRescheduled"`), `delinquencyChanged` (`"LoanDelinquencyChanged"`), bỏ hàm `envelope(...)`.
  `ProducerFieldAdditionsCompatibilityTest` dựng consumer bằng `new KafkaEventReader(mapper)`.

**finora-payment**

- Xoá `messaging/event/KafkaEventEnvelope.java` và hàm `rootType` của `PaymentKafkaConsumerConfiguration`.
- `PaymentOutboxPublisher`: thay `KafkaTemplate` + `ObjectMapper` bằng `KafkaEventPublisher`; giữ `topic(...)`
  (allowlist, ném `IllegalArgumentException`), `@Value publishTimeout`, `retryLater` và comment cũ:
  ```java
  public void publishOne(Long id) {
      PaymentOutboxEvent event = paymentOutboxEventRepository.findById(id).orElseThrow();
      try {
          String topic = topic(event.getEventType(), event.getEventVersion());
          kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic, event.getAggregateId(), event.getEventId(),
                  event.getEventType(), event.getEventVersion(), event.getCreatedAt(), event.getPayloadJson(), null),
                  publishTimeout);
          event.published(clock.instant());
          paymentOutboxEventRepository.save(event);
      } catch (Exception ex) {
          retryLater(event, ex);
      }
  }
  ```
  `traceId = null` vì Payment không lưu trace (không có header `finora-trace-id`, như cũ); `occurredAt = createdAt` như
  cũ. Bỏ nhánh `catch (InterruptedException)`: bộ chung đã đặt lại cờ interrupt rồi ném `KafkaPublishException`.
  Payment bỏ qua `code()/retryable()`: mọi lỗi vẫn `failed("Kafka publish failed", now + 10 s)`, `DEAD_LETTER` sau 10
  lần. Để log `retryLater` giữ tên lỗi cũ, ghi `error=` theo `ex.getCause()` khi `ex` là `KafkaPublishException`.
  Bean `KafkaEventPublisher` khai ở `PaymentKafkaConsumerConfiguration`. Test:
  `new PaymentOutboxPublisher(repository, new KafkaEventPublisher(kafka, new ObjectMapper()), clock, environment)`.
- `PaymentKafkaConsumerConfiguration.paymentKafkaErrorHandler` giữ 4 tham số `@Value` (+ bean `KafkaEventReader`):
  ```java
  ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), 2.0);
  backOff.setMaxInterval(maxInterval.toMillis());
  backOff.setMaxElapsedTime(maxElapsed.toMillis());
  return KafkaErrorHandlers.deadLetterErrorHandler(kafkaTemplate, backOff,
          KafkaDeadLetterTopic.suffixAnyPartition(deadLetterSuffix),
          // Không log payload: chỉ vị trí record và loại lỗi gốc.
          (record, exception) -> log.error("Event Payment vào DLT: topic={}, partition={}, offset={}, exception={}",
                  record.topic(), record.partition(), record.offset(),
                  KafkaErrorHandlers.rootCauseSimpleName(exception)),
          IllegalArgumentException.class, BusinessException.class);
  ```
- Consumer:

| Consumer | Hợp đồng | Ghi chú |
|---|---|---|
| `DisbursementRequestedConsumer` | `of("DisbursementRequested", 2)` | `request(event.eventId(), event.data())` |
| `LoanDisbursedConsumer` | `of("LoanDisbursed", 1).withHeaderRule(HeaderRule.TYPE_ONLY).withEventIdRequired(false).withOccurredAtRule(OccurredAtRule.IGNORED).withDataRule(DataRule.OPTIONAL)` | `activateLoan(event.eventId(), event.data())`; giữ `groupId` |
| `NoteOwnershipChangedConsumer` | như trên với `"NoteOwnershipChanged"` | `updateOwnership(...)`; giữ `groupId` |

  Hai consumer servicing trước chỉ kiểm header type (trước khi parse JSON) và `version == 1`; bỏ qua header id/version,
  không bắt buộc eventId/occurredAt/data (`data` thiếu hoặc `null` thì handler nhận `null`). Hợp đồng `TYPE_ONLY` ở trên
  giữ đúng như vậy.

**finora-notification**

- Xoá `messaging/event/KafkaEnvelope.java` và hàm `rootType` của `NotificationKafkaConfig`.
- Giữ `InboundEventReader` (để giữ `InboundEventReaderTest`), chỉ còn dựng `InboundEvent`:
  ```java
  @Component
  @RequiredArgsConstructor
  public class InboundEventReader {

      private final KafkaEventReader kafkaEventReader;

      public InboundEvent read(ConsumerRecord<String, String> record, String expectedType) {
          KafkaEvent<JsonNode> event = kafkaEventReader.readRaw(record,
                  KafkaEventContract.anyVersion(expectedType).withDataRule(DataRule.OBJECT));
          return new InboundEvent(event.eventId(), event.eventType(), event.version(), event.occurredAt(),
                  record.topic(), event.data());
      }
  }
  ```
  `anyVersion` + `OBJECT` đúng kiểm cũ: version từ 1, header version bằng version envelope, `data` là object,
  `occurredAt` không rỗng và đọc được bằng `Instant`/`OffsetDateTime`, header được trim.
- `KafkaEventProcessor.process(String group, ConsumerRecord<String, String> record)`:
  `notificationTopicRoutes.expectedType(record.topic())` (vẫn ném IAE với topic lạ) → `inboundEventReader.read(record,
  expectedType)` → `notificationIntakeService.accept(event)` → giữ nguyên dòng `log.info("Consumer Notification:
  topic={}, partition={}, offset={}, group={}, eventId={}, type={}, result={}", ...)` lấy topic/partition/offset từ record.
- `DomainEventConsumer`, `HeldInstallmentConsumer`, `InvestorServicingConsumer`: `consume(ConsumerRecord<String, String>
  record)` → `kafkaEventProcessor.process("<group như cũ>", record)`; giữ `id`, `groupId`, `autoStartup`, `topics`.
- `NotificationKafkaConfig.notificationKafkaErrorHandler`: `ExponentialBackOff(1_000L, 2.0)`, max 30 s, tổng 180 s như
  cũ; `KafkaDeadLetterTopic.suffixAnyPartition(properties.kafka().deadLetterSuffix())`; hook `log.error("Event
  Notification vào DLT: topic={}, partition={}, offset={}, exception={}", ..., KafkaErrorHandlers.rootCauseSimpleName(
  exception))`; chỉ `IllegalArgumentException.class`. + bean `KafkaEventReader`.
- Test: `new InboundEventReader(new KafkaEventReader(new ObjectMapper()))` và `read(record(...), "LoanDisbursed")`;
  `NotificationFlowIT.send(...)` dựng `ConsumerRecord` rồi gọi `processor.process("test", record)`.

**finora-blockchain**

- Không có envelope riêng. Bỏ `parse`, `parseEventId`, `occurredAt` của `MilestoneProofRegistrar` và 3 hằng header của
  `MilestoneProofConsumer`.
- `MilestoneProofRegistrar` giữ `ObjectMapper` (cho `ProofPayloadHasher`), thêm `KafkaEventReader`:
  ```java
  public ProofSubmissionView register(ProofMilestone milestone, ConsumerRecord<String, String> record) {
      KafkaEvent<JsonNode> event = kafkaEventReader.readRaw(record,
              KafkaEventContract.of(milestone.eventType(), milestone.eventVersion())
                      .withDataRule(DataRule.OBJECT).withOccurredAtRule(OccurredAtRule.OPTIONAL));
      JsonNode data = event.data();
      ProofMilestone.Aggregate aggregate = milestone.aggregate(data);
      String hash = ProofPayloadHasher.hash(data, ProofHashScheme.CURRENT, objectMapper);
      return proofSubmissionService.register(new ProofRegistrationCommand(
              milestone.sourceService(), event.eventId(), milestone.eventType(), milestone.eventVersion(),
              event.occurredAt(), milestone.proofType(), aggregate.type(), aggregate.id(),
              loanApplicationId(data), hash, ProofHashScheme.CURRENT.version()));
  }
  ```
  `readRaw` trả đúng `JsonNode` Jackson đọc được (cùng deserializer với `readTree`) nên hash không đổi; `OPTIONAL` giữ
  việc event thiếu `occurredAt` vẫn đăng ký proof với `sourceOccurredAt = null`.
- 5 listener của `MilestoneProofConsumer`: `xxx(ConsumerRecord<String, String> record)` →
  `milestoneProofRegistrar.register(ProofMilestone.X, record)`.
- `BlockchainKafkaConsumerConfiguration.blockchainKafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate)`:
  `ExponentialBackOffWithMaxRetries(5)`, 1 s nhân 2, max 16 s như cũ; `KafkaDeadLetterTopic.suffixKeepPartition(".DLT")`;
  `IllegalArgumentException.class, BusinessException.class`; `BlockchainKafkaConsumerConfigurationTest` không đổi.
  + bean `KafkaEventReader`.
- Test: `new MilestoneProofRegistrar(new ObjectMapper(), new KafkaEventReader(new ObjectMapper()), proofs)` và
  `register(milestone, record(envelope, id, type, version))`.

**Khác biệt còn lại sau khi chuyển** (chỉ với event hỏng mà producer FINORA không phát; event hợp lệ cho kết quả y hệt)

1. Message exception (log, header DLT `kafka_dlt-exception-message`/`-stacktrace`) theo mẫu chung, không còn cause
   Jackson; header `kafka_dlt-exception-cause-fqcn` vẫn là `java.lang.IllegalArgumentException`.
2. Vào DLT ngay thay vì retry trước rồi mới vào DLT:
   - `occurredAt` sai định dạng ở 16 listener Loan (trừ relay CIC) và Investment: trước `DateTimeParseException` →
     retry 2 lần cách 1 s → DLT.
   - Investment `LoanFundingCancelled`/`LoanFundingRequested` thiếu `occurredAt`: trước NPE ở `Instant.parse` → retry → DLT.
   - `"data": null` ở consumer Loan/Investment/Payment `DisbursementRequested` (Jackson đọc thành `NullNode` nên lọt
     kiểm `data() == null`): trước handler nhận `data = null`, thường NPE → retry → DLT.
   - Payment servicing nhận payload JSON `null`: trước NPE → retry khoảng 3 phút → DLT.
   - Record value `null`: trước Spring không resolve được `@Payload` (không retry, vào DLT), nay
     `IllegalArgumentException`; chỉ khác FQCN trong header DLT.
3. Payment `DisbursementRequested` có `occurredAt` khác null nhưng sai định dạng: trước vẫn xử lý (không đọc trường
   này), nay vào DLT.
4. Nới về khoảng trắng/định dạng: header có khoảng trắng hai đầu, `occurredAt` có khoảng trắng hoặc offset không có
   giây (`2026-10-17T10:00+07:00`) nay được nhận ở Loan/Investment/Payment/Blockchain (trước chỉ Notification). Blockchain
   nay nhận header version `"01"`/`"+1"`, `version` envelope dạng chuỗi `"1"` hoặc số thực `1.5` (Jackson ép kiểu như mọi
   consumer khác), nhưng từ chối `eventId` UUID không chuẩn kiểu `"1-1-1-1-1"` (trước `UUID.fromString` nhận).
5. Payment publisher nay từ chối payload không phải JSON object (`OUTBOX_PAYLOAD_INVALID`, retry rồi `DEAD_LETTER`); mọi
   payload Payment là record serialize nên không xảy ra. Log `retryLater` với lỗi bất đồng bộ ghi lớp bên trong
   (`KafkaProducerException`) thay cho `ExecutionException`.

**Ghi chú khi đọc code (giữ nguyên, không sửa ở bước này)**

- Phân loại lỗi gửi của Loan/Investment, giữ y nguyên trong `KafkaEventPublisher`, hầu như không ra
  `KAFKA_PUBLISH_REJECTED`/`KAFKA_TEMPORARILY_UNAVAILABLE` với broker thật: spring-kafka 3.1 bọc lỗi callback trong
  `KafkaProducerException`, lỗi đồng bộ đã qua `FutureFailure` (xác thực, phân quyền, record quá lớn, topic sai, timeout
  metadata) trong `KafkaException("Send failed")`; code không bóc lớp nên các lỗi này thành `KAFKA_PUBLISH_FAILED` (retry
  tới `maxAttempts` rồi `DEAD_LETTER`). Chỉ exception `producer.send` ném thẳng (như `SerializationException`) hoặc mock
  trong test mới khớp. Sửa là đổi hành vi, để việc khác.

### Đợt 2: "mỗi việc một cách" (Hải đồng ý 03/11, chạy sau bước Kafka)

1. Gọi HTTP chỉ dùng `RestClient` (5/6 service đã dùng). finora-user chuyển Feign (`NotificationClient`,
   `CicMappingClient`, client AI) và chỗ dùng `WebClient`/`RestTemplate` sang `RestClient`; token service account
   vẫn chỉ gắn cho client AI và Notification, không gửi tới CIC. resilience4j đặt cùng một cách (cấu hình như
   finora-loan) cho mọi client gọi ra ngoài.
2. Controller trả `ResponseEntity<DTO>` như finora-user; JSON không đổi, status code giữ đúng như cũ
   (`@ResponseStatus` → `ResponseEntity.status(...)`). Giữ nguyên 9 endpoint bọc `BaseResponse` (7 user, 1 payment,
   1 blockchain) vì mobile đang đọc lớp bọc; endpoint stream/tải file giữ nguyên.
3. Bản giả lập thống nhất tên `Mock*`: `StubPaymentClient`, `StubUserIdentityClient`, `LocalMockBorrowerProfileProvider`
   → `Mock*`. `Disabled*` (tắt tính năng) và `Simulated*` (chế độ mô phỏng) là khái niệm khác, giữ. Key cấu hình giữ.
4. Ghi các quy ước trên vào rule 03/04 (thay hai dòng cấm cặp `XService`/`XServiceImpl`).
5. finora-user đổi nốt khoảng 78 field inject chưa đặt theo kiểu (`EkycAttemptRepository attemptRepository`,
   `*Properties properties`, `storage`, `logAnalytics`...) để khớp mục 2.4 như các service khác (investment đã đổi 162).

### Tách domain theo loại (Hải yêu cầu 03/11)

- `domain/entity/`: class `@Entity`, `@Embeddable`, `@MappedSuperclass`.
- `domain/enums/`: enum (`enum` là từ khoá Java nên không đặt tên gói `enum`; finora-common cũng dùng `enums`).
- `domain/model/`: value object/record không phải bảng, không phải enum (user 5: `EkycDraftField`, `EkycIdentityDraft`,
  `LivenessStep`, `PendingRegistration`, `StaffRoleDefinition`; loan 6; blockchain 4).
- Service đã chia nhóm nghiệp vụ trong domain (loan, payment, investment, blockchain): tách loại bên trong từng nhóm,
  `domain/<nhóm>/entity|enums|model/`, giống `service/<nhóm>/impl/`; `package-info.java` của nhóm giữ ở `domain/<nhóm>/`.
- Đổi cả FQN nằm trong chuỗi JPQL (loan có `com.finora.loan.domain.servicing.ReconciliationIncidentStatus.OPEN` trong
  `@Query`); thêm import cho class trước đây cùng gói; member package-private bị gọi chéo gói thì mở `public`.
- Làm bằng script sau khi gộp loan, trước bước Kafka.

### Thứ tự làm sau khi 6 agent xong

1. Gộp loan-a + loan-b vào cây chính, chạy lại script chuyển gói của loan-b.
2. Tách domain theo loại (script), rồi bước Kafka (bộ gửi/nhận chung trong common).
3. Đợt 2 mục 1–3.
4. Chuẩn hoá import toàn bộ module (script `normalize_imports.py`).
5. Build + test cả reactor.
6. Cập nhật rule 03/04, điền mục Kết quả.

## 4. Ngoài phạm vi

- finora-common: thư viện chung, chia theo chủ đề (security, logging...); không đổi tên API công khai.
- finora-ai: Python/FastAPI, cấu trúc riêng ở rule 03.
- Gộp code trùng giữa các service (`EmailMasker`, HMAC/AES ở payment, SHA-256 viết tay): đổi hành vi, để việc khác.

## 5. Kết quả

04/11/2026: xong toàn bộ, chưa commit. `mvn -o -B -fae clean install -DskipITs` cả reactor: BUILD SUCCESS.

| Module | Test trước | Test sau |
|---|---|---|
| finora-common | 82 | 115 (bộ Kafka chung) |
| finora-gateway | 71 | 71 |
| finora-loan | 475 | 481 |
| finora-payment | 114 | 129 |
| finora-blockchain | 56 | 58 |
| finora-investment | 113 | 126 |
| finora-user | 464 | 503 |
| finora-notification | 139 | 149 |

Chưa chạy: các `*IT` cần Docker/Testcontainers (đều compile được); chưa chạy thật cụm service.

Thay đổi hành vi có chủ đích: circuit breaker + time limiter ở mọi client gọi ra ngoài (trừ Fabric và job cuối ngày
Fineract); sự kiện Kafka sai định dạng vào DLT ngay thay vì thử lại, câu lỗi DLT đổi chữ; mã lỗi mới `EXPO_CIRCUIT_OPEN`;
đăng nhập Keycloak có trần 30s. Sự kiện hợp lệ, JSON API, status code, bảng DB giữ nguyên.

Việc còn lại để sau: circuit breaker cho Fabric (cần lọc theo mã lỗi); cấu hình circuit `ai-credit` khác `fineract-product`;
AI/Fineract client của loan chưa khôi phục cờ interrupt; Keycloak 4xx khi lấy token ở investment bị hiểu là Payment từ chối;
gộp code trùng (`EmailMasker`, HMAC/AES payment, `ServiceTokenProvider` investment).
