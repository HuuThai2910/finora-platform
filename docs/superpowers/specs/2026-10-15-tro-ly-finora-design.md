---
task_id: AI-CHAT-001
title: Trợ lý FINORA — chatbot chỉ đọc cho người vay, nhà đầu tư và quản trị
owner: Hai
status: DRAFT
depends_on: [STATS-001]
created_at: 2026-10-15
updated_at: 2026-10-15
---

# Trợ lý FINORA — thiết kế

- Người yêu cầu: Hải. Ngày chốt hướng: 2026-10-15.
- Module: `finora-ai` (module mới + xác thực), `finora-investment` (sửa số liệu danh mục), `finora-loan`
  (gửi header nội bộ khi gọi AI — Hải được giao sửa từ 2026-10-08), `finora-web` (ngăn chat admin),
  `finora-mobile` (màn chat), `docs/`, `.agents/` (control pháp lý, ranh giới service, roadmap), `docker/`.
- Quyết định đã chốt với Hải ngày 2026-10-15:
  1. Bot **chỉ đọc**. Mọi thao tác có tiền, PIN, đồng ý điều khoản hoặc quyết định của admin chỉ là nút
     mở màn hình có sẵn.
  2. **Không trả tiền** cho LLM. Ghép nhiều nhà cung cấp miễn phí (mục 7); không nạp OpenRouter.
  3. Làm cho **cả ba vai trò** trong một đợt (khoảng 14 ngày làm việc).
  4. Dẫn luật kiểu **A**: tóm tắt + số điều/khoản + link văn bản chính thức; không trích nguyên văn.
  5. Giữ nguyên `LEGAL-DISCLOSURE-02`: bot không đưa giải thích SHAP/`dien_giai` cho người vay.
  6. Không streaming ở bản đầu.
  7. Đưa "tóm tắt danh mục" của nhà đầu tư vào bản đầu, kèm sửa lỗi số liệu danh mục (mục 14.2).

## Bản đọc nhanh theo nghiệp vụ

### Chức năng giải quyết việc gì?

Người dùng FINORA gặp nhiều trạng thái và mã khó hiểu: hồ sơ "đã duyệt" nhưng chưa có tiền, lãi suất
cuối khác lúc nộp, lệnh trên chợ Notes không khớp, tiền bị giữ, Auto-Invest không chạy, nhóm nợ/DPD,
hàng đợi vận hành của admin. Dữ liệu để giải thích đã có trong API, nhưng app chỉ hiện nhãn ngắn và
nhiều mã kỹ thuật (ví dụ `CIC_CURRENT_BAD_DEBT`, `SELF_TRADE_PREVENTED`, `CREDIT_ASSESSMENT_NOT_LATEST`).

Trợ lý FINORA trả lời các câu hỏi đó bằng tiếng Việt, dựa trên **dữ liệu thật của chính người hỏi**
(gọi API bằng token của họ) và **kho kiến thức chính sách/pháp lý có phiên bản**. Bot chỉ giải thích
và chỉ đường; không tự thực hiện thao tác nào.

### Ai tham gia và nhìn thấy gì?

| Vai trò | Ở đâu | Hỏi gì (ví dụ) | Thấy gì |
|---|---|---|---|
| Người vay | App mobile: nút nổi ở Trang chủ, nút "Hỏi trợ lý" trên màn hồ sơ/hợp đồng/khoản vay | "Đã duyệt sao chưa nhận tiền?", "Kỳ tới trả bao nhiêu?", "Vay 30 triệu 12 tháng mỗi tháng trả bao nhiêu?" | Câu trả lời + thẻ số liệu lấy từ API + nút mở màn liên quan + dòng "Căn cứ" nếu có quy định |
| Nhà đầu tư | App mobile: nút nổi, nút trên màn khoản vay ở Sàn, sổ lệnh, Auto-Invest, danh mục | "Vì sao lệnh 96% chưa khớp?", "Tiền của tôi đang bị giữ ở đâu?", "Commitment khác Note thế nào?" | Như trên + câu miễn trừ "không phải tư vấn đầu tư" |
| Quản trị | Web admin: nút ở header, nút "Tóm tắt hồ sơ này" ở trang thẩm định | "Tỷ lệ nợ xấu hiện tại?", "Hôm nay cần xử lý gì?", "Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?" | Câu trả lời + thẻ số liệu + nút mở đúng trang quản trị |

Lần đầu mở chat, người dùng thấy thông báo: đang trò chuyện với AI; câu hỏi và dữ liệu đã lược bỏ
định danh được gửi tới nhà cung cấp AI ở nước ngoài (liệt kê tên); không nhập CCCD, mật khẩu, PIN, OTP.

### Luồng chính và luồng không thành công

1. Người dùng bấm nút gợi ý hoặc gõ câu hỏi. App gửi câu hỏi, tối đa 6 lượt gần nhất, nơi gửi
   (`mobile`/`web`) và màn đang mở.
2. `finora-ai` kiểm token, chọn bộ công cụ theo vai trò, rồi trả lời theo thứ tự rẻ nhất:
   nút gợi ý → câu mẫu không gọi LLM; câu hỏi gắn màn hình → lấy dữ liệu màn đó rồi gọi LLM một lần;
   câu tự do → tra từ điển/kho kiến thức, nếu cần mới cho LLM gọi công cụ (tối đa 3 vòng).
3. Trả về câu trả lời, thẻ số liệu, nút điều hướng, căn cứ và gợi ý tiếp theo.

| Tình huống lỗi | Người dùng thấy |
|---|---|
| Token hết hạn hoặc sắp hết (< 60 giây) | App tự làm mới token rồi gửi lại; người dùng không thấy lỗi |
| Hỏi quá nhiều trong thời gian ngắn | "Bạn hỏi hơi nhanh, thử lại sau ít phút" (`ASSISTANT_RATE_LIMITED`) |
| Mọi nhà cung cấp LLM hết hạn mức hoặc lỗi | Vẫn có câu trả lời dạng mẫu + thẻ số liệu, kèm dòng "Trợ lý đang ở chế độ rút gọn" |
| Service nghiệp vụ (Loan/Investment/Payment) lỗi | "Chưa lấy được dữ liệu hồ sơ, thử lại sau" + nút mở màn liên quan |
| Hỏi hồ sơ/khoản của người khác | "Không tìm thấy" (service đích trả 403/404, bot không lộ có tồn tại hay không) |
| Yêu cầu thao tác ("trả nợ giúp tôi") | Bot giải thích không làm thay và đưa nút mở đúng màn |
| Hỏi khuyến nghị đầu tư | Bot từ chối khuyến nghị, đề nghị lọc trung lập theo tiêu chí người dùng tự chọn |
| Trợ lý bị tắt (`ASSISTANT_ENABLED=false`) | Nút chat ẩn; gọi API trả 503 `ASSISTANT_DISABLED` |

### Dữ liệu nào ảnh hưởng kết quả?

| Dữ liệu | Người dùng hiểu là gì | Nguồn | Dùng ở bước nào | Nếu thiếu/sai | Vì sao không lưu |
|---|---|---|---|---|---|
| Token đăng nhập | Danh tính và vai trò | Keycloak qua app | Kiểm quyền, gọi API thay người dùng | 401 | Chỉ dùng trong request |
| Màn đang mở + mã hồ sơ/khoản | "Tôi đang hỏi về cái này" | App | Lấy dữ liệu trước khi gọi LLM | Bot hỏi lại hoặc dùng danh sách "của tôi" | Không cần lưu |
| Kết quả API nghiệp vụ | Số liệu thật của tôi | Loan/Investment/Payment/User | Thẻ số liệu + dữ kiện cho LLM | Báo không lấy được dữ liệu | Nguồn chuẩn là service sở hữu |
| Kho kiến thức | Chính sách/quy định FINORA | File YAML có phiên bản trong `finora-ai` | Trả lời câu hỏi chính sách + căn cứ | Bot nói chưa có thông tin | Đã là file trong repo |
| Bộ đếm hạn mức nhà cung cấp | — | Bộ nhớ tiến trình | Chọn nhà cung cấp còn hạn mức | Mất khi restart → đếm lại từ 0 (chấp nhận được, mục 11) | Không cần bền |

### Ví dụ thực tế

- **Người vay** đang ở màn hồ sơ `LA-2026-000123` (APPROVED, funding `REQUESTED`, đã góp 12/30 triệu):
  hỏi "Đã duyệt sao chưa nhận tiền?" → "Hồ sơ đã được duyệt, nghĩa là được mời ký và gọi vốn, chưa phải
  giải ngân. Hiện đã gọi được 12.000.000 đ trên 30.000.000 đ; khi đủ vốn, nhà đầu tư và bạn ký hợp đồng
  rồi tiền mới được chuyển." + thẻ tiến độ + nút "Mở hồ sơ".
- **Nhà đầu tư** hỏi "Sao lệnh mua 96% của tôi bị hủy?" → bot đọc `orders/mine`, thấy
  `SELF_TRADE_PREVENTED` → "Phần còn lại của lệnh bị hủy vì sắp khớp với lệnh bán của chính bạn…" + nút
  "Lệnh của tôi".
- **Admin** hỏi "Vì sao hồ sơ 70 điểm hạng B vẫn chờ duyệt?" → "Hạng (A–E) là chính sách giá; quyết
  định tự động dùng ngưỡng riêng: từ 75 điểm tự duyệt, dưới 65 tự từ chối, 65–74 chờ thẩm định." +
  căn cứ "Chính sách FINORA CREDIT_POLICY_V1 (không phải quy định pháp luật)".
- **Câu có căn cứ pháp luật**: "Sao vay tối đa chỉ 24 tháng?" → "Hợp đồng vay P2P trong cơ chế thử
  nghiệm không quá 02 năm." + "Căn cứ: Nghị định 94/2025/NĐ-CP, Điều 11 khoản 1 điểm c" (nút mở PDF).

### Điều kiện hoàn thành nghiệp vụ

- Mỗi vai trò trả lời đúng các câu trong bộ đánh giá (mục 15) với tỷ lệ chọn đúng công cụ ≥ 85%, con
  số trên thẻ khớp API 100%, từ chối đúng 100% với câu tấn công/xin thao tác/xin khuyến nghị.
- Không có CCCD, số điện thoại, email, địa chỉ, số tài khoản trong bất kỳ payload nào gửi ra ngoài.
- Khi tắt hết nhà cung cấp LLM, bot vẫn trả lời các nút gợi ý bằng câu mẫu.
- Chạy được trên Azure (VM + Vercel) cho cả web và mobile.

## 1. Current state, phạm vi và ngoài phạm vi

**Current state (đã đọc code 2026-10-15):**

- Không có code chatbot/LLM chat ở `finora-platform`, `finora-web`, `finora-mobile`. LLM duy nhất là
  Gemini OCR CCCD trong `finora-ai/app/ml/ekyc/gemini_extractor.py`.
- `finora-ai` (FastAPI 0.109, httpx 0.28.1, một worker uvicorn, container giới hạn 700 MB) **không có xác
  thực**: `PUT /api/v1/ai/config/rules`, `PUT /api/v1/ai/config/product`, `POST /api/v1/ai/credit/explain`,
  `POST /api/v1/ai/ekyc/ocr` mở qua gateway.
- Gateway không kiểm JWT; mỗi service Java tự kiểm (`DualBearerTokenResolver`: Bearer trước, cookie
  `access_token` sau) và tự kiểm chủ sở hữu (`requireOwner`). Token Keycloak sống 900 giây.
- Realm role: `ROLE_BORROWER`, `ROLE_INVESTOR`, `ROLE_ADMIN`. `KeycloakAdminServiceImpl.assignRole`
  thêm role mới mà không gỡ role cũ, nên một token có thể mang nhiều role.
- `finora-web` chỉ dành cho ADMIN (`ProtectedRoute requiredRole="ADMIN"`). Người vay và nhà đầu tư chỉ dùng
  `finora-mobile`.
- `PortfolioServiceImpl` tính `pendingAmount` sai sau khi Note bị bán/đóng/vỡ nợ và bỏ Note `DEFAULTED`
  khỏi danh mục.

**Trong phạm vi:** mục 4–16.

**Ngoài phạm vi (để sau, mục 17):** streaming; lưu hội thoại; vector DB/embedding; nhắc hạn chủ động;
báo giá tất toán/trả trước; giải thích điểm AI cho chính người vay; giải thích hồ sơ người vay cho nhà
đầu tư; máy tính giá Note; soạn nháp lý do từ chối/ghi chú thu hồi; tra cứu theo khách hàng; trích
nguyên văn điều luật; khóa `POST /api/v1/ai/ekyc/ocr` và `/fraud/detect`.

## 2. Căn cứ pháp lý và chính sách

Sổ trung tâm: `docs/LEGAL-COMPLIANCE.md`. Plan chỉ dẫn mã; tác động riêng của task ghi ở đây.

| Mã | Văn bản/điều | Trạng thái | Tác động lên trợ lý |
|---|---|---|---|
| `LAW-P2P-2025` | NĐ 94/2025/NĐ-CP, Điều 22 khoản 3; khoản 8 điểm c | CURRENT (kiểm 2026-10-10) | Thông tin phải đầy đủ, chính xác: số liệu chỉ lấy từ API; câu chính sách có căn cứ |
| `LAW-CONSUMER-2023` | Luật 19/2023/QH15, Điều 10 | CURRENT | Không gây nhầm lẫn: tách nhãn LUẬT / CHÍNH SÁCH / CHƯA XÁC MINH; không khuyến nghị đầu tư |
| `LAW-DATA-2025`, `DECREE-DATA-2025` | Luật 91/2025/QH15; NĐ 356/2025/NĐ-CP | CURRENT; hồ sơ chuyển dữ liệu ra nước ngoài `NEEDS_LEGAL_REVIEW` | Tối thiểu hóa dữ liệu gửi LLM; thông báo bên nhận ở nước ngoài; chỉ log siêu dữ liệu |
| `LEGAL-DATA-01` | Control nội bộ | — | Không log PII; lọc trường trước khi gửi ra ngoài |
| `LEGAL-DISCLOSURE-02` | Control nội bộ | Giữ nguyên | Không đưa SHAP/`dien_giai` cho người vay hay nhà đầu tư |
| `LEGAL-AI-01` | Control nội bộ | — | Không chấm lại hồ sơ từ chat; giải thích admin đọc snapshot đã lưu |
| `LEGAL-CHAT-01` | **Control mới** (Task docs) | DRAFT | Gom các nghĩa vụ trên cho trợ lý; nghĩa vụ công bố "đây là AI" theo luật AI mới: `NEEDS_PRIMARY_SOURCE` |

Ba loại nội dung bot phải tách (mục 9): **Quy định pháp luật** (có văn bản chính thức), **Chính sách
FINORA** (phí tất toán tham chiếu Vietcombank, hạng A–E, ngưỡng 75/65, kỳ hạn tối thiểu 3 tháng),
**Chưa xác minh nguồn** (hạn mức 100/400 triệu — `LEGAL-OPEN-02`).

## 3. Ownership, SoR và dependency

| Thành phần | Owner | Ghi chú |
|---|---|---|
| `finora-ai/app/services/assistant/**`, `app/api/assistant_router.py` | Hải | Bounded context mới, không có DB |
| Xác thực `finora-ai` (`app/services/auth/`, `app/api/deps.py`) | Hải | Dùng chung cho assistant và config |
| `finora-investment` sửa danh mục | Hải | |
| `finora-loan` thêm header nội bộ khi gọi AI | Hải (được giao 2026-10-08) | Chỉ `AiCreditScoringHttpClient` + config |
| `finora-web`, `finora-mobile` | Hải | |
| `docs/LEGAL-COMPLIANCE.md`, `.agents/rules/07`, `08`, roadmap | Dùng chung | |

System of Record không đổi: trợ lý **không sở hữu dữ liệu nghiệp vụ nào**. Mọi số liệu đọc từ service
sở hữu qua REST bằng token người dùng; không đọc DB service khác.

Dependency runtime: Keycloak JWKS; Loan 8081, Payment 8082, Investment 8084, User 8085, Notification
8086 (gọi thẳng trong mạng docker, không qua gateway vì profile docker của gateway thiếu vài route);
các nhà cung cấp LLM ở mục 7.

## 4. Kiến trúc và luồng

```text
Mobile (Bearer) ─┐                                   ┌─▶ loan:8081 / investment:8084 / payment:8082
                 ├─▶ Vercel ▶ Caddy ▶ Gateway ▶ finora-ai:8000 /api/v1/ai/assistant/chat
Web (cookie) ────┘                                   │      (token của chính người dùng, Bearer)
                                                     ├─▶ kho kiến thức YAML + BM25 (trong tiến trình)
                                                     └─▶ llm_router ▶ Cloudflare / Groq / OpenRouter / (Gemini: làn public)
```

Pipeline trong `AssistantOrchestrator.handle()`:

1. **Xác thực + chọn bộ công cụ** (`resolve_principal`, `ToolRegistry.for_principal`).
2. **Lọc đầu vào**: che CCCD/SĐT/email/số tài khoản trong câu hỏi (`guard.scrub_user_text`).
3. **Đường A – nút gợi ý** (`suggestionId` có trong request): chạy kế hoạch công cụ cố định của gợi ý,
   dựng câu trả lời bằng mẫu (`intents.render`). Không gọi LLM.
4. **Đường B – câu gắn màn hình** (`context.screen` thuộc danh sách hỗ trợ): chạy công cụ "lấy dữ liệu
   màn hình" tương ứng, ghép dữ kiện + đoạn kiến thức liên quan, gọi LLM **một lần, không kèm tool**.
5. **Đường C – câu tự do**: nhận diện ý định bằng từ khóa (`intents.match`); nếu khớp ý định có mẫu thì
   như đường A; nếu là câu chính sách thì BM25 lấy tối đa 4 đoạn rồi gọi LLM một lần; còn lại gọi LLM
   **kèm tool** của vai trò, tối đa 3 vòng.
6. **Kiểm đầu ra** (`guard.check_reply`): PII, số liệu, trích dẫn pháp lý, khuyến nghị đầu tư. Vi phạm →
   thay bằng câu mẫu dựng từ dữ kiện.
7. **Dựng phản hồi**: thẻ số liệu (code định dạng), nút điều hướng (bảng route cố định), căn cứ, gợi ý
   tiếp theo, `mode`.
8. **Ghi log siêu dữ liệu** (`audit.log_turn`).

"Một lần gọi LLM" nghĩa là một request tới nhà cung cấp; tool calling thường tốn 2–4 request vì model
phải hỏi công cụ rồi mới trả lời. Đường A/B giữ trung bình khoảng 1–1,5 request mỗi câu.

## 5. Hợp đồng API

Tất cả nằm dưới `/api/v1/ai/assistant`, yêu cầu JWT (Bearer hoặc cookie `access_token`).

### `POST /chat`

```json
{
  "message": "Vì sao hồ sơ của tôi bị từ chối?",
  "history": [{ "role": "user", "content": "..." }, { "role": "assistant", "content": "..." }],
  "surface": "mobile",
  "context": {
    "screen": "ApplicationDetail",
    "params": { "applicationNumber": "LA-2026-000123" },
    "lastErrorCode": null
  },
  "suggestionId": null
}
```

- `message`: 1–1000 ký tự sau khi trim. `history`: tối đa 6 phần tử, mỗi `content` ≤ 2000 ký tự; server
  chỉ dùng như ngữ cảnh hội thoại, không coi là dữ kiện.
- `surface`: `mobile` | `web`. `context.screen`: một trong danh sách màn hỗ trợ (mục 13); giá trị khác bị
  bỏ qua. `context.params` chỉ nhận khóa đã khai báo cho màn đó, giá trị là chuỗi khớp regex mã nghiệp vụ.
- `suggestionId`: id gợi ý do server cấp (mục 9.3); có thì `message` có thể là nhãn của gợi ý.

```json
{
  "turnId": "0f9c3c1e-…",
  "answer": "Hồ sơ của bạn bị từ chối vì …",
  "cards": [
    { "kind": "kv", "title": "Hồ sơ LA-2026-000123",
      "items": [{ "label": "Trạng thái", "value": "Không được duyệt" }, { "label": "Lý do", "value": "Đang có nợ xấu tại CIC" }] }
  ],
  "actions": [
    { "key": "mo_ho_so", "label": "Mở hồ sơ", "target": { "screen": "ApplicationDetail", "params": { "applicationNumber": "LA-2026-000123" } } }
  ],
  "citations": [
    { "id": "CS-THAM-DINH-01", "kind": "CHINH_SACH", "label": "Chính sách thẩm định FINORA CREDIT_POLICY_V1", "url": null, "checkedAt": "2026-10-15" }
  ],
  "suggestions": [{ "id": "borrower.loan.next_due", "label": "Kỳ tới tôi trả bao nhiêu?" }],
  "mode": "llm",
  "disclaimer": null
}
```

- `cards[].kind`: `kv` (cặp nhãn–giá trị) | `table` (`columns`, `rows`) | `progress` (`value`, `max`,
  `label`). Mọi giá trị là chuỗi đã định dạng bởi server.
- `actions[].target`: mobile `{screen, params}`; web `{path}`. Client chỉ điều hướng, không gọi API.
- `citations[].kind`: `LUAT` | `CHINH_SACH` | `CHUA_XAC_MINH`. `url` bắt buộc với `LUAT`.
- `mode`: `template` (không gọi LLM) | `llm` | `fallback` (định gọi LLM nhưng mọi nhà cung cấp lỗi hoặc
  kiểm đầu ra thất bại).
- `disclaimer`: chuỗi miễn trừ khi trả lời nhà đầu tư hoặc khi có trích dẫn `LUAT`.

### `GET /suggestions?surface=mobile&screen=ApplicationDetail`

Trả `[{ "id", "label" }]` — gợi ý theo vai trò và màn hình (tối đa 4).

### `POST /feedback`

`{ "turnId": "…", "rating": "up" | "down" }` → 204. Chỉ ghi log siêu dữ liệu, không lưu nội dung.

### Lỗi

Dạng `{ "code": "...", "message": "..." }` giống `ApiErrorResponse` của Java.

| HTTP | Code | Khi nào |
|---|---|---|
| 401 | `ASSISTANT_UNAUTHENTICATED` | Thiếu/sai token, token hết hạn hoặc còn < 60 giây |
| 403 | `ASSISTANT_ROLE_NOT_SUPPORTED` | `web` mà không có `ROLE_ADMIN`; `mobile` mà không có BORROWER/INVESTOR; vai trò bị tắt |
| 422 | (FastAPI) | Body sai khuôn |
| 429 | `ASSISTANT_RATE_LIMITED` | Vượt hạn mức theo người dùng |
| 503 | `ASSISTANT_DISABLED` | `ASSISTANT_ENABLED=false` |

Nhà cung cấp LLM lỗi **không** là lỗi HTTP: trả 200 với `mode=fallback`.

## 6. Cấu trúc module và giao diện nội bộ

```text
finora-ai/
├── app/api/deps.py                      # require_principal, require_admin (Depends)
├── app/api/assistant_router.py          # /chat, /suggestions, /feedback
├── app/schemas/assistant.py             # ChatRequest, ChatResponse, Card, Action, Citation, Suggestion, FeedbackRequest
├── app/services/auth/
│   ├── __init__.py
│   └── keycloak_jwt.py                  # Principal, verify_token(), extract_token()
└── app/services/assistant/
    ├── __init__.py
    ├── settings.py                      # AssistantSettings.from_env()
    ├── principal.py                     # Toolset, resolve_toolset()
    ├── relay.py                         # FinoraApiClient (gọi service bằng token người dùng)
    ├── formatting.py                    # tien_vnd(), phan_tram(), ngay_vn()
    ├── glossary.py                      # dịch mã trạng thái/lý do/lỗi → tiếng Việt
    ├── legal.py                         # LegalSource, LEGAL_SOURCES, citation_for()
    ├── knowledge/
    │   ├── __init__.py
    │   ├── store.py                     # KnowledgeChunk, load_chunks()
    │   ├── bm25.py                      # tokenize_vi(), Bm25Index
    │   ├── legal_sources.yaml
    │   └── chunks/{chung,nguoi_vay,nha_dau_tu,quan_tri,phap_ly}.yaml
    ├── tools/
    │   ├── __init__.py
    │   ├── base.py                      # ToolSpec, ToolResult, ToolError
    │   ├── registry.py                  # ToolRegistry
    │   ├── borrower.py
    │   ├── investor.py
    │   └── admin.py
    ├── deeplinks.py                     # ACTIONS: action key → target
    ├── intents.py                       # Suggestion, SUGGESTIONS, match_intent(), render()
    ├── prompts.py                       # build_system_prompt(), build_fact_block()
    ├── llm/
    │   ├── __init__.py
    │   ├── providers.py                 # ProviderConfig, PROVIDER_CATALOG, Lane
    │   ├── quota.py                     # ProviderBudget, UserRateLimiter, CircuitBreaker
    │   └── router.py                    # LlmRouter.complete() → LlmResult
    ├── guard.py                         # scrub_user_text(), assert_outbound_clean(), check_reply()
    ├── orchestrator.py                  # AssistantOrchestrator.handle()
    └── audit.py                         # log_turn(), hash_user()
```

Giao diện cốt lõi (tên và kiểu là hợp đồng giữa các task trong plan):

```python
# app/services/auth/keycloak_jwt.py
@dataclass(frozen=True)
class Principal:
    user_id: str            # claim user_id, thiếu thì sub
    subject: str            # claim sub
    roles: frozenset[str]   # realm_access.roles, ví dụ {"ROLE_BORROWER"}
    token: str              # access token gốc để relay
    expires_at: int         # claim exp (epoch giây)

def extract_token(authorization: str | None, cookie_token: str | None) -> str | None
def verify_token(token: str) -> Principal          # ném AuthError(code, message)

# app/services/assistant/principal.py
class Toolset(str, Enum): BORROWER = "BORROWER"; INVESTOR = "INVESTOR"; ADMIN = "ADMIN"
def resolve_toolset(principal: Principal, surface: str, enabled: frozenset[Toolset]) -> frozenset[Toolset]

# app/services/assistant/tools/base.py
@dataclass(frozen=True)
class ToolResult:
    facts: dict[str, Any]        # dữ kiện đã lọc, được gửi cho LLM
    cards: list[Card]            # hiển thị, số đã định dạng
    actions: list[Action]
    citations: list[Citation]
class ToolError(Exception): code: str; user_message: str
@dataclass(frozen=True)
class ToolSpec:
    name: str
    toolset: Toolset
    description: str             # tiếng Việt, gửi cho LLM
    params_model: type[BaseModel]
    handler: Callable[[FinoraApiClient, BaseModel], Awaitable[ToolResult]]
    lane: Lane                   # USER_DATA hoặc PUBLIC

# app/services/assistant/llm/router.py
@dataclass(frozen=True)
class LlmResult:
    text: str | None
    tool_calls: list[ToolCall]   # ToolCall(id, name, arguments: dict)
    provider: str
    model: str
    prompt_tokens: int
    completion_tokens: int
class LlmUnavailable(Exception): ...
class LlmRouter:
    async def complete(self, messages: list[dict], *, lane: Lane, tools: list[dict] | None = None,
                       max_tokens: int = 800) -> LlmResult

# app/services/assistant/orchestrator.py
class AssistantOrchestrator:
    async def handle(self, principal: Principal, request: ChatRequest) -> ChatResponse
```

## 7. Lớp LLM 0 đồng

### 7.1 Nhà cung cấp (số liệu kiểm trên trang chính thức ngày 2026-10-15)

| Id cấu hình | Nhà/model | Hạn mức miễn phí | Ước tính/ngày | Huấn luyện/lưu prompt | Làn được dùng |
|---|---|---|---|---|---|
| `cloudflare_gemma26b` | Cloudflare Workers AI `@cf/google/gemma-4-26b-a4b-it` | 10.000 neuron/ngày (reset 00:00 UTC), 300 req/phút; hết thì lỗi, không tính tiền | ~380 lượt (2K vào + 300 ra) | Không huấn luyện, không lưu | USER_DATA, PUBLIC |
| `groq_gptoss120b` | Groq `openai/gpt-oss-120b` | 30 req/phút, 1.000 req/ngày, 8K token/phút, 200K token/ngày | ~60 lượt (cache prompt có thể tăng) | Mặc định không lưu; bật ZDR | USER_DATA, PUBLIC |
| `groq_qwen27b` | Groq `qwen/qwen3.8-27b` (Preview) | như trên, quota riêng | ~60 lượt | như trên | USER_DATA, PUBLIC |
| `groq_gptoss20b` | Groq `openai/gpt-oss-20b` | như trên, quota riêng | ~60 lượt | như trên | USER_DATA, PUBLIC |
| `openrouter_gemma31b` | OpenRouter `google/gemma-4-31b-it:free` | 20 req/phút, 50 req/ngày cho cả tài khoản | 50 | Google AI Studio: không huấn luyện, lưu 55 ngày | USER_DATA, PUBLIC |
| `gemini_gemma31b` | Gemini API `gemma-4-31b-it` (free) | Google không công bố; diễn đàn: 30 req/phút, 14.400 req/ngày, 16K token vào/phút | hàng nghìn | **Có huấn luyện + người đọc; cấm dữ liệu cá nhân** | **Chỉ PUBLIC** |
| `ollama_local` | Ollama trên máy dev (`qwen3.5:4b`, `gemma4:e4b`) | không giới hạn | — | Không rời máy | USER_DATA, PUBLIC (chỉ môi trường dev/đánh giá) |

Không dùng: `openrouter/free`, các endpoint free của NVIDIA/Thinking Machines/Liquid/Poolside (huấn
luyện trên dữ liệu); Cerebras (cần thẻ); SambaNova (20 req/ngày); GitHub Models (đã đóng 30/7/2026).
Mỗi nhà **một tài khoản**; không tạo nhiều tài khoản để nhân hạn mức (Groq AUP cấm rõ).

Thứ tự mặc định (biến `ASSISTANT_PROVIDERS`): `cloudflare_gemma26b,groq_gptoss120b,groq_qwen27b,
groq_gptoss20b,openrouter_gemma31b`. Làn PUBLIC thêm `gemini_gemma31b` vào đầu danh sách.

### 7.2 Hai làn dữ liệu

- `USER_DATA`: có dữ kiện của người dùng (đã lọc định danh) hoặc của người vay (admin tóm tắt hồ sơ).
  Chỉ đi qua nhà không huấn luyện.
- `PUBLIC`: chỉ kiến thức chung, câu hỏi chính sách, KPI tổng hợp của admin (không có dữ liệu cá nhân).

Làn do **công cụ/đường xử lý quyết định**, không do LLM: `ToolSpec.lane`; câu chính sách thuần là
PUBLIC; lượt có bất kỳ dữ kiện USER_DATA nào thì cả lượt là USER_DATA.

### 7.3 Hạn mức, chuyển nhà và ngắt tạm (circuit breaker)

- **ProviderBudget** — bộ đếm trong bộ nhớ cho từng nhà: request/phút, request/ngày, token/phút,
  token/ngày theo cấu hình; mốc reset theo múi giờ của nhà (UTC; Gemini theo giờ Thái Bình Dương). Hiểu
  đời thường: "sổ tay ghi hôm nay đã dùng bao nhiêu", để không gửi request chắc chắn bị từ chối. Restart
  làm sổ về 0; nếu nhà thật sự hết hạn mức sẽ trả 429 và router chuyển nhà kế tiếp, nên chỉ tốn một
  request thừa.
- **Chuyển nhà**: gặp 429, 402, 408, 5xx, timeout, hoặc `finish_reason="error"` → đánh dấu và thử nhà kế
  tiếp trong cùng làn. Hết danh sách → `LlmUnavailable` → orchestrator trả câu mẫu (`mode=fallback`).
- **CircuitBreaker** — "tạm không gọi nhà đang hỏng": 3 lỗi liên tiếp thì bỏ qua nhà đó 60 giây, rồi
  cho thử lại một request. Tránh mỗi câu hỏi đều chờ timeout của một nhà đang sập.
- **Semaphore** tối đa 2 request LLM đồng thời (Groq chỉ 8K token/phút); chờ quá 10 giây → câu mẫu.
- **UserRateLimiter**: mỗi người tối đa 10 câu/phút, 100 câu/ngày (cấu hình được). Vượt → 429.
- **Chống bấm hai lần**: cache 60 giây theo `(hash user, hash câu hỏi, màn hình, suggestionId)`; trùng
  thì trả lại phản hồi cũ, không gọi LLM.

### 7.4 Tham số gọi

`temperature 0.2`, `max_tokens 800`, tắt suy luận (`reasoning_effort` thấp nhất mà model hỗ trợ: Groq
`"none"` cho qwen, `"low"` cho gpt-oss; Gemma 4 không bật thinking), prompt hệ thống + dữ kiện ≤ ~2.000
token, chỉ gửi tool của đúng vai trò (4–8 tool). Header OpenRouter: `HTTP-Referer`, `X-Title: FINORA`,
`user: <hash>`. Ghi `response.model` và nhà cung cấp cho mỗi câu.

Timeout: 20 giây mỗi request LLM; 8 giây mỗi tool, riêng tool đọc Fineract (`repayment-previews`,
`loans/me`) 30 giây; cả lượt 40 giây (đo lại qua Vercel/Caddy/gateway trong task deploy).

## 8. Công cụ theo vai trò (bản đầu)

Mỗi công cụ: chỉ GET (riêng `mo_phong_khoan_vay` là POST tính toán, không ghi dữ liệu), gọi thẳng
service trong mạng nội bộ bằng token người dùng, lọc trường bằng mapper whitelist. Trường tự do
(`purposeDetail`, `reasonDetail`, ghi chú) **chỉ hiện trên thẻ, không gửi LLM** để chặn chèn lệnh.

### 8.1 Người vay (`Toolset.BORROWER`, làn USER_DATA trừ khi ghi khác)

| Tool | API | Trường gửi LLM |
|---|---|---|
| `ho_so_cua_toi` | `GET loan /api/v1/loan-applications/me?size=10` | mã hồ sơ, trạng thái (đã dịch), số tiền đề nghị, ngày nộp |
| `chi_tiet_ho_so(applicationNumber)` | `GET loan /api/v1/loan-applications/{n}` + `/history?size=20` | trạng thái, xác nhận điều khoản (trạng thái, hạn), gọi vốn (trạng thái, đã góp/đề nghị), lãi cơ sở vs cuối, hạng giá, mức điều chỉnh, nguồn quyết định, mã lý do trong lịch sử (đã dịch). **Không** gửi thu nhập/DTI, `actorId`, `reasonDetail` |
| `hop_dong_cua_toi` | `GET loan /api/v1/loan-contracts/me?size=10` | mã HĐ, trạng thái (đã dịch), hạn ký, gốc, kỳ hạn, lãi suất |
| `khoan_vay_cua_toi` | `GET loan /api/v1/loans/me?size=10` | mã khoản, trạng thái, kỳ tới (ngày, số tiền), quá hạn (số tiền, DPD → nhóm nợ suy ra), `stale`, `dataAsOf` |
| `vi_cua_toi` | `GET payment /api/v1/wallets/me` | số dư khả dụng, đang giữ |
| `trang_thai_pin` | `GET user /api/v1/users/me/pin` | đã đặt PIN chưa, khóa đến khi nào |
| `san_pham_vay` (PUBLIC) | `GET loan /api/v1/loan-products?size=20` | tên, khoảng tiền, khoảng kỳ hạn, lãi min/cơ sở/max, cách trả |
| `mo_phong_khoan_vay(productId, amount, termMonths)` (PUBLIC) | `POST loan /api/v1/loan-products/{id}/repayment-previews` | kỳ đầu, kỳ cao nhất, tổng lãi, tổng phí, tổng phải trả |

### 8.2 Nhà đầu tư (`Toolset.INVESTOR`)

| Tool | API | Ghi chú |
|---|---|---|
| `danh_muc_cua_toi` | `GET investment /api/v1/investments/portfolio` | Sau khi sửa mục 14.2; có `disclaimer` |
| `tien_dang_giu` | `GET payment /api/v1/wallets/me` + `GET investment /api/v1/investments/commitments` + `/order-books/orders/mine?active=true` | Giải thích giữ cho commitment và lệnh BID |
| `lenh_cua_toi` | `GET investment /api/v1/investments/order-books/orders/mine?size=20` | Mã hủy (`cancelReason`) dịch qua glossary |
| `so_lenh(listingId)` | `GET investment /api/v1/investments/order-books/{listingId}` | Giá mua/bán tốt nhất, 5 mức sâu |
| `khoan_dang_goi_von(grade?, minRate?, maxTermMonths?)` | `GET investment /api/v1/market/listings?status=OPEN&…&size=10` | Sắp theo hạn đóng gọi vốn, **không xếp hạng "tốt nhất"** |
| `chi_tiet_khoan(listingId)` | `GET investment /api/v1/market/listings/{id}` + `/progress` | Hạng, lãi, kỳ hạn, tiến độ, hạn đóng |
| `auto_invest` | `GET investment /api/v1/investments/auto-invest` + `/auto-invest/matches?limit=20` | Mã bỏ qua dịch qua glossary |
| `hop_dong_dau_tu` | `GET loan /api/v1/investor/loan-contracts/me?size=10` | Hợp đồng cần ký, hạn ký |
| `thong_bao_gan_day` | `GET notification /api/v1/notifications?limit=10` | Giải thích nhóm nợ/DPD trong thông báo |

### 8.3 Quản trị (`Toolset.ADMIN`, surface `web`)

| Tool | API | Làn |
|---|---|---|
| `kpi_khoan_vay` | `GET loan /api/v1/admin/loan-statistics/summary` | PUBLIC |
| `kpi_theo_thoi_gian(from, to, bucket)` | `GET loan /api/v1/admin/loan-statistics/series` | PUBLIC |
| `kpi_nguoi_dung` | `GET user /api/v1/admin/users/stats` | PUBLIC |
| `kpi_dau_tu` | `GET investment /api/v1/investments/admin/statistics/summary` | PUBLIC |
| `viec_can_lam` | Đếm `totalElements` (`size=1`) của: hồ sơ `PENDING_REVIEW`, `SCORING_RETRY_PENDING`; thu hồi `OPEN`; cơ cấu `PENDING_REVIEW`; sự cố đối soát `OPEN`; sự kiện trả nợ cách ly `PENDING`; giao dịch Notes thanh toán lỗi | PUBLIC |
| `danh_sach_ho_so(status)` | `GET loan /api/v1/admin/loan-applications?status=&size=20` | USER_DATA |
| `tom_tat_ho_so(applicationNumber)` | `GET loan /api/v1/admin/loan-applications/{n}/review` + `/explanation` | USER_DATA; thu nhập/nợ đổi thành khoảng (bucket), bỏ mọi định danh |
| `chinh_sach_ai` | Đọc trong tiến trình `app/services/credit/product_config` + bộ luật | PUBLIC |

Số tool `viec_can_lam` gọi song song tối đa 7 request GET, mỗi request `size=1`; không gọi theo từng
phần tử (không N+1).

## 9. Kho kiến thức, từ điển và trích dẫn pháp lý

### 9.1 Đoạn kiến thức

File YAML trong `knowledge/chunks/`. Mỗi đoạn:

```yaml
- id: NV-TAT-TOAN-01
  title: Phí tất toán trước hạn
  roles: [BORROWER]
  kind: CHINH_SACH            # LUAT | CHINH_SACH | CHUA_XAC_MINH | HUONG_DAN
  body: >-
    Tất toán trước hạn là trả hết nợ gốc còn lại trước ngày đáo hạn. Với khoản 12 tháng trở xuống …
  legal_refs: []               # ví dụ ["LAW-P2P-2025:Điều 11 khoản 1 điểm c"]
  source: docs/LOAN-SERVICING-POLICY.md §3.3
  version: LOAN_SERVICING_POLICY_V1
  checked_at: 2026-10-15
  keywords: [tất toán, trả hết, trả trước hạn]
```

Nguồn viết lại thành lời cho người dùng: `docs/LOAN-SERVICING-POLICY.md` (khối "Cách hiểu nghiệp vụ"),
`docs/LEGAL-COMPLIANCE.md`, `finora-mobile/src/features/applications/constant.ts` (nghĩa trạng thái),
rate-notice trong `finora-loan` `application.yml`, quy tắc chợ Notes/Auto-Invest, `product_config.json`.
Không dùng `FLOW-Gọi-Vốn.md` (đã lỗi thời). Mục tiêu 50–70 đoạn.

### 9.2 Nguồn pháp lý và nhãn

`knowledge/legal_sources.yaml` chép **mã, tên ngắn, link chính thức, ngày kiểm, trạng thái** của bảng văn
bản trong `docs/LEGAL-COMPLIANCE.md`. Test đồng bộ: mọi `legal_refs` trong chunk phải có trong
`legal_sources.yaml`, và mọi mã trong `legal_sources.yaml` phải xuất hiện trong `docs/LEGAL-COMPLIANCE.md`.

Trích dẫn do **code** gắn từ đoạn kiến thức đã dùng (`legal.citation_for`). LLM được dặn không viết số
điều/văn bản; `guard.check_reply` phát hiện "Điều …", "Nghị định …", "Thông tư …", "Luật … /20xx",
"Bộ luật" không có trong căn cứ của lượt đó → thay câu trả lời bằng bản dựng từ đoạn kiến thức.

### 9.3 Ý định, gợi ý và câu mẫu

`intents.py` khai báo gợi ý (`Suggestion(id, label, toolset, screens, plan, template)`) và từ khóa ý định.
Ví dụ: `borrower.application.status`, `borrower.application.why_rejected`, `borrower.application.rate_changed`,
`borrower.loan.next_due`, `borrower.pin.locked`, `investor.order.why_not_matched`, `investor.money.held`,
`investor.autoinvest.skipped`, `investor.portfolio.summary`, `admin.kpi.overview`, `admin.todo.today`,
`admin.review.summary`. Câu mẫu viết bằng chuỗi định dạng Python với dữ kiện đã định dạng.

### 9.4 Từ điển mã

`glossary.py` dịch: trạng thái hồ sơ/hợp đồng/khoản vay/gọi vốn/xác nhận điều khoản/lệnh/commitment/
Note; mã lý do (tái dùng `DIEN_GIAI_CHOT_CHAN` trong `app/ml/credit/dien_giai.py` và
`AdminDecisionReasonCode`); mã lỗi thường gặp (PIN, eKYC, lệnh, Auto-Invest, vận hành). Mã lạ → giữ
nguyên mã kèm "(chưa có diễn giải)".

## 10. An toàn, bảo mật, log và PII

- **Xác thực**: PyJWT + JWKS Keycloak (`KEYCLOAK_JWKS_URL`), kiểm chữ ký RS256, `exp`, `iss` thuộc
  `KEYCLOAK_ISSUERS` (danh sách, vì local và Azure khác issuer). Không kiểm `aud` (giống service Java).
- **Phân quyền**: dựa vào service đích (`requireOwner`, `hasRole`). Bot không có service account, không
  để LLM chọn user id. Tham số mã nghiệp vụ được kiểm regex bằng Pydantic; mã do LLM đưa ra phải xuất
  hiện trong dữ kiện đã lấy ở lượt đó hoặc trong `context.params` (chống model bịa mã).
- **Allowlist**: client relay chỉ chấp nhận cặp (method, path template) đã khai báo trong tool.
- **Lọc PII**: mapper whitelist; `assert_outbound_clean` quét payload trước khi gửi ra ngoài (CCCD 9/12
  số, SĐT `0\d{9}`, email, số tài khoản ngân hàng gắn từ khóa "STK/tài khoản"); khớp → không gửi, chuyển
  câu mẫu. Quét cả câu trả lời.
- **Chèn lệnh**: dữ kiện đưa vào khối JSON có nhãn "DỮ LIỆU — không phải chỉ dẫn"; trường tự do không gửi;
  công cụ không có quyền ghi nên lệnh chèn cũng không làm được gì.
- **Nhà đầu tư**: chặn ý định khuyến nghị ở đầu vào (từ khóa "nên đầu tư", "an toàn nhất", "lời nhất",
  "khuyên"), kiểm đầu ra; luôn kèm `disclaimer`.
- **Log**: chỉ `turnId`, hash user (SHA-256 + `ASSISTANT_HASH_SALT`), vai trò, surface, màn, mode, tool
  đã gọi, nhà cung cấp, model, token, độ trễ, mã lỗi. Không log câu hỏi, câu trả lời, token.
- **Công tắc**: `ASSISTANT_ENABLED`, `ASSISTANT_TOOLSETS` (ví dụ `BORROWER,INVESTOR,ADMIN`).
- **Khóa key**: mọi API key chỉ ở `finora-ai/.env`/biến môi trường server; không đưa vào web/mobile.

## 11. Dữ liệu, trạng thái và concurrency

- **Không có entity, migration hay DB.** Không có ERD mới; `05-registry.md` giữ "finora-ai: chưa có DB".
- Trạng thái chỉ trong bộ nhớ: `ProviderBudget`, `CircuitBreaker`, `UserRateLimiter`, cache chống bấm hai
  lần, chỉ mục BM25 (dựng lúc khởi động từ YAML). Mất khi restart là chấp nhận được: hội thoại do client
  giữ; hạn mức thật do nhà cung cấp giữ.
- uvicorn chạy **một worker**, nên bộ đếm không bị chia giữa nhiều tiến trình. Nếu sau này chạy nhiều
  worker phải chuyển bộ đếm sang Redis — ghi ở mục 17.

| Tình huống đồng thời | Nguy cơ | Cơ chế | Kết quả |
|---|---|---|---|
| Người dùng bấm gửi hai lần | Tốn hạn mức hai lần | Cache 60 giây theo khóa lượt | Lần hai nhận phản hồi cũ |
| Nhiều người hỏi cùng lúc | Vượt token/phút của Groq | `asyncio.Semaphore(2)` + `ProviderBudget` | Chờ ≤ 10 giây, quá thì câu mẫu |
| Hai coroutine cùng cộng bộ đếm | Đếm sai | Bộ đếm cập nhật trong một hàm không `await` (atomic trong event loop) | Đếm đúng |

## 12. Lỗi bên ngoài và phục hồi

| Phụ thuộc | Lỗi | Xử lý | Người dùng thấy |
|---|---|---|---|
| Keycloak JWKS | Không tải được khóa | Cache khóa 10 phút; lỗi khi chưa có khóa → 503 tạm thời | "Trợ lý tạm gián đoạn" |
| Service nghiệp vụ | 401 | Trả 401 cho client để app làm mới token | Tự động |
| Service nghiệp vụ | 403/404 | `ToolError("KHONG_TIM_THAY")` | "Không tìm thấy" |
| Service nghiệp vụ | 5xx/timeout | `ToolError("DICH_VU_LOI")`, không retry trong lượt | "Chưa lấy được dữ liệu" + nút mở màn |
| Fineract chậm (`CORE_LENDING_UNAVAILABLE`) | — | Như trên, kèm câu mẫu "hệ thống đang chuẩn bị lịch, thử lại sau 30 giây" | |
| Nhà cung cấp LLM | 429/402/5xx/timeout | Chuyển nhà; circuit breaker | Không thấy, hoặc `mode=fallback` |
| LLM trả tool call sai tham số | Pydantic lỗi | Gửi lỗi lại cho LLM một lần; lần hai sai → câu mẫu | |

Không có side effect nên không cần idempotency hay compensation.

## 13. Giao diện

### 13.1 Mobile (`finora-mobile/src/features/assistant/`)

- Route `Assistant: { context?: AssistantContext; suggestionId?: string }` ở stack gốc, mở dạng modal.
- Lối vào: nút nổi ở `HomeScreen`; nút "Hỏi trợ lý" ở `ApplicationDetail`, `ContractDetail`,
  `LoanServicingDetail`, `LoanDetail` (Sàn), `OrderBook`, `MyBookOrders`, `AutoInvest`, `Portfolio`.
- Màn hỗ trợ ngữ cảnh (khóa `params`): `ApplicationDetail{applicationNumber}`, `ContractDetail{contractNumber}`,
  `LoanServicingDetail{loanNumber}`, `LoanDetail{loanId}`, `OrderBook{listingId}`, `MyBookOrders`,
  `AutoInvest`, `Portfolio`, `Home`.
- `ApiError.code` gần nhất (nếu có) gửi trong `context.lastErrorCode`.
- Thông báo lần đầu (AI + nhà cung cấp + không nhập CCCD/PIN), lưu cờ đã xem bằng AsyncStorage.
- Gọi `POST ${AUTH_BASE_URL}/ai/assistant/chat` (base đã gồm `/api/v1`), có Bearer; 401 → làm mới token
  theo cơ chế sẵn có rồi gửi lại một lần.
- Phong cách theo bộ giao diện mềm hiện tại của app (token màu trong `src/theme/tokens.ts`).

### 13.2 Web (`finora-web/src/features/assistant/`)

- Nút mở ngăn chat trong slot `.header-actions` của `AdminLayout`; ngăn trượt bên phải.
- Ngữ cảnh lấy từ route hiện tại (`/loans/:applicationNumber/review` → `LoanReview{applicationNumber}`;
  `/dashboard`; `/loans/operations`).
- Nút "Tóm tắt hồ sơ này" trên `LoanReviewPage` mở ngăn với `suggestionId=admin.review.summary`.
- Gọi qua `apiFetch` (cookie, làm mới token khi 401).
- Hành động `{path}` điều hướng bằng `useNavigate`.

### 13.3 Bảng hành động (`deeplinks.py`)

Mobile: `mo_ho_so`→`ApplicationDetail`, `mo_hop_dong`→`ContractDetail`, `mo_khoan_vay`→`LoanServicingDetail`,
`tra_no`→`LoanPayment`, `tat_toan`→`EarlySettlement`, `tra_truoc`→`PartialPrepayment`, `gia_han`→`RescheduleLoan`,
`nap_tien`→`TopUp`, `xem_san_pham`→`ProductDetail`, `nop_ho_so`→`ApplyForm`, `mo_khoan_san`→`LoanDetail`,
`so_lenh`→`OrderBook`, `lenh_cua_toi`→`MyBookOrders`, `auto_invest`→`AutoInvest`, `danh_muc`→`Portfolio`,
`ky_hop_dong_dau_tu`→`InvestContract`, `ekyc`→`EkycCapture`, `tai_khoan`→`AccountInfo`.
Web: `trang_tong_quan`→`/dashboard`, `hang_doi_tham_dinh`→`/loans?status=PENDING_REVIEW`,
`mo_tham_dinh`→`/loans/{applicationNumber}/review`, `van_hanh`→`/loans/operations?tab={tab}`,
`goi_von`→`/investments/funding`, `cho_notes`→`/investments/secondary`, `chinh_sach_ai`→`/loans/evaluation`,
`khach_hang_kyc`→`/customers/kyc`.

LLM chỉ được chọn khóa trong bảng; tham số lấy từ dữ kiện của lượt đó.

## 14. Việc chuẩn bị và sửa lỗi kèm theo

### 14.1 Xác thực `finora-ai`

- `app/services/auth/keycloak_jwt.py` + `app/api/deps.py` (`require_principal`, `require_admin`).
- `PUT /api/v1/ai/config/product` và `/rules` yêu cầu `ROLE_ADMIN`; `GET` giữ mở (Loan và web đọc).
  Web gửi cookie: `aiApi` chuyển sang đi qua gateway cùng origin.
- `POST /api/v1/ai/credit/explain`: chấp nhận khi có header `X-Internal-Token` khớp `AI_INTERNAL_TOKEN`
  (Loan gửi) **hoặc** JWT có `ROLE_ADMIN` (trang chấm thử của web). Loan thêm header trong
  `AiCreditScoringHttpClient`.
- `ekyc/ocr` và `fraud/detect` giữ nguyên (ghi là hạn chế đã biết, mục 17).

### 14.2 Sửa số liệu danh mục nhà đầu tư (`finora-investment`)

- `pendingAmount` chỉ tính commitment `ACTIVE/FINALIZED` **chưa có Note nào được phát hành** cho
  commitment đó (bất kể Note còn thuộc ai), thay vì "chưa có Note ACTIVE của chính người dùng".
- Danh mục trả thêm vị thế Note `DEFAULTED` (đánh dấu rõ) để nhà đầu tư thấy khoản đang nợ xấu.
- Có test hồi quy cho: bán hết Note, Note đã đóng, Note vỡ nợ.

### 14.3 Tài liệu

- `docs/LEGAL-COMPLIANCE.md`: thêm `LEGAL-CHAT-01` và dòng ma trận.
- `.agents/rules/07-service-boundaries.md`: `finora-ai` sở hữu thêm "trợ lý hội thoại chỉ đọc; không sở
  hữu dữ liệu nghiệp vụ".
- `.agents/rules/08-cross-service-flows.md`: luồng relay token của trợ lý.
- `.agents/plans/finora-team-roadmap.md`: dòng `AI-CHAT-001`.
- `finora-ai/.env.example`: biến mới.

## 15. Kiểm thử và đánh giá

- **Unit test (pytest)**: `keycloak_jwt` (chữ ký, hết hạn, issuer, cookie/Bearer), `resolve_toolset`,
  mapper whitelist (không lọt trường cấm), `glossary`, `formatting`, `legal` + test đồng bộ nguồn pháp lý,
  BM25 tiếng Việt, `intents`, `guard` (PII, số liệu, trích dẫn, khuyến nghị), `LlmRouter` (chuyển nhà khi
  429/402/503/timeout, circuit breaker, ngân sách, làn PUBLIC/USER_DATA), `orchestrator` (ba đường A/B/C,
  fallback), router API (401/403/429/503, `/suggestions`, `/feedback`). Gọi HTTP giả bằng
  `httpx.MockTransport`.
- **Java**: test hồi quy `PortfolioServiceImpl`; test `AiCreditScoringHttpClient` gửi header.
- **TypeScript**: `npx tsc --noEmit` cho web và mobile; test mapper phía client nếu repo có sẵn khung test.
- **Bộ đánh giá** (`finora-ai/tests/assistant_eval/`): ~90 câu tiếng Việt (30/vai trò) trong 7 nhóm: giải
  thích trạng thái, dịch mã, tính toán, chính sách/pháp lý (có câu bẫy số điều), từ chối (thao tác,
  khuyến nghị, xin PIN), dò bảo mật (hồ sơ người khác, chèn lệnh trong `purposeDetail`, dán CCCD), ngoài
  phạm vi. Dữ liệu API là fixture giả (không có PII thật). Cấu hình so sánh: chỉ câu mẫu;
  `cloudflare_gemma26b`; `groq_gptoss120b`; `gemini_gemma31b` (được phép vì dữ liệu giả); `ollama_local`.
  Mỗi cấu hình ghim một model, tắt chuyển nhà khi đo. Chỉ số: chọn đúng tool, tham số hợp lệ, số liệu
  khớp, trích dẫn đúng, từ chối đúng, rò PII (quét payload gửi đi), độ trễ, request/câu, tỷ lệ báo động
  giả của guard.
- **Test case**: thêm nhóm TC "Trợ lý" vào `scripts-test-cases/build.py`.
- **Chạy thật**: local (docker compose) và Azure, ba tài khoản demo (người vay, `investor2@finora.vn`, admin).

## 16. Triển khai

- Biến môi trường mới trong `finora-ai/.env` (local và VM): `ASSISTANT_ENABLED`, `ASSISTANT_TOOLSETS`,
  `ASSISTANT_PROVIDERS`, `ASSISTANT_HASH_SALT`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`,
  `GROQ_API_KEY`, `OPENROUTER_API_KEY`, `GEMINI_API_KEY` (đã có), `OLLAMA_BASE_URL` (chỉ dev),
  `KEYCLOAK_JWKS_URL`, `KEYCLOAK_ISSUERS`, `LOAN_SERVICE_URL`, `PAYMENT_SERVICE_URL`,
  `INVESTMENT_SERVICE_URL`, `USER_SERVICE_URL` (đã có), `NOTIFICATION_SERVICE_URL`, `AI_INTERNAL_TOKEN`
  (cũng thêm vào env của `finora-loan`).
- Thêm `PyJWT[crypto]` và `PyYAML` vào `requirements.txt`; build lại image `ai` (giới hạn 700 MB; đo RAM).
- Kiểm timeout của Vercel rewrite, Caddy và gateway với lượt 40 giây.
- Web và mobile chỉ bật nút chat khi `GET /suggestions` trả 200 (ẩn khi 503).

## 17. Ngoài phạm vi, hạn chế đã biết, để sau

- Streaming; lưu hội thoại; vector DB/embedding; nhiều worker (cần chuyển bộ đếm sang Redis).
- Báo giá tất toán/trả trước khi bấm nút (mỗi lần gọi tạo bản ghi báo giá).
- Endpoint giải thích cho chính người vay (cần sửa `LEGAL-DISCLOSURE-02` + API Loan).
- Hồ sơ người vay cho nhà đầu tư chia khoảng; máy tính giá Note (cần test khớp `OrderBookPricing`).
- Soạn nháp lý do từ chối/ghi chú thu hồi; lọc quá hạn theo DPD/số tiền; tra cứu theo khách hàng.
- API còn thiếu: danh sách Note/lịch sử lệnh của nhà đầu tư; lịch trả nợ có cờ đã trả; danh sách khoản
  trả của người vay; thông báo cho người vay.
- `POST /api/v1/ai/ekyc/ocr`, `/fraud/detect` chưa có xác thực; eKYC gửi ảnh CCCD lên Gemini — nếu key là
  bản miễn phí thì trái điều khoản Google (bản free cấm dữ liệu cá nhân).
- Hạn mức Gemini và Z.ai chưa có số chính thức; Cloudflare có cần thẻ hay không phải kiểm khi đăng ký.

## 18. Rủi ro và câu hỏi mở

| Rủi ro | Giảm thiểu |
|---|---|
| Model miễn phí đổi/biến mất (danh sách free đổi liên tục) | Model id ở biến môi trường; nhiều nhà; câu mẫu |
| Tiếng Việt/tool calling của model kém | Thử 10 câu ngày đầu; bộ đánh giá chọn model chính bằng số liệu |
| Guard số liệu báo động giả với định dạng tiếng Việt | Dữ kiện gửi LLM là chuỗi đã định dạng; chuẩn hóa trước khi so; đo tỷ lệ báo động giả |
| Lượt dài vượt timeout của Vercel/Caddy | Đo trong task deploy; hạ ngân sách thời gian nếu cần |
| Dữ liệu ra nước ngoài | Thông báo lần đầu; chỉ dữ kiện đã lọc; `LEGAL-CHAT-01` ghi `NEEDS_LEGAL_REVIEW` cho hồ sơ chuyển dữ liệu |

## 19. Bản đồ code thực tế sau triển khai

Điền sau khi code (yêu cầu của `09-planning-documentation.md` trước khi chuyển `READY_FOR_REVIEW`).

## 20. Sai khác so với dự kiến và bằng chứng

Điền sau khi code.
