# Plan Chi Tiết: Tích hợp Keycloak vào `finora-user` & Đọc CCCD qua NFC trên `finora-mobile`

Tài liệu này định hướng kế hoạch triển khai toàn diện từng bước cho các nhóm tính năng chính trong hệ thống **FINORA Platform** (loại trừ eKYC/Face Matching):
1. **Tích hợp Keycloak IAM vào `finora-user`** (Xác thực OAuth2/OIDC, Đồng bộ tài khoản, Quản lý Profile, Keycloak Admin REST Client, Email Thông báo & OTP, Phân quyền Theo Chức Năng, Bảo mật Token qua HTTP-Only Cookie với Refresh Token, Chống Dò Mật Khẩu, PII Log Masking, **Hash & Mã Hóa Số CCCD & Số Điện Thoại**).
2. **Đọc dữ liệu CCCD qua chip NFC trên `finora-mobile`** (React Native đọc chip ICAO 9303 trực tiếp trên điện thoại, gửi dữ liệu có cấu trúc lên `finora-user`, auto-fill profile — không cần xác minh chữ ký số Bộ Công An).
3. **Giao diện Người dùng (UI):** `finora-web` (React + Vite) cho web và `finora-mobile` (React Native) cho mobile; **KHÔNG** sử dụng LocalStorage cho Token và **KHÔNG** dùng Thymeleaf. Quét NFC CCCD chỉ khả dụng trên mobile.

---

## 📖 Bản Đọc Nhanh Theo Nghiệp Vụ

### Chức năng giải quyết việc gì?

FINORA là nền tảng cho vay ngang hàng (P2P Lending). Trước khi một người vay có thể gửi hồ sơ vay hoặc một nhà đầu tư có thể đặt lệnh, họ phải:

1. **Có tài khoản** — đăng ký, đăng nhập, đăng xuất, đổi mật khẩu.
2. **Có hồ sơ cá nhân đầy đủ** — họ tên, ngày sinh, địa chỉ, số CCCD, số điện thoại.
3. **Được phân đúng vai trò** — người vay, nhà đầu tư, hoặc quản trị viên — để hệ thống biết cho phép truy cập chức năng nào.

Plan này xây dựng toàn bộ nền tảng trên: xác thực an toàn qua Keycloak, quản lý hồ sơ cá nhân, đọc CCCD qua chip NFC trên điện thoại để giảm nhập liệu thủ công, gửi email thông báo, và bảo vệ dữ liệu nhạy cảm. Đây là tiền đề bắt buộc trước khi triển khai luồng KYC, tạo hồ sơ vay, hoặc bất kỳ nghiệp vụ tài chính nào.

### Ai tham gia và nhìn thấy gì?

| Actor | Vai trò | Họ nhìn thấy / tương tác gì |
|---|---|---|
| **Người vay (Borrower)** | Muốn vay tiền | Đăng ký → đăng nhập → chạm NFC đọc CCCD → xác nhận thông tin → hoàn thiện hồ sơ → sẵn sàng gửi hồ sơ vay |
| **Nhà đầu tư (Investor)** | Muốn đầu tư | Đăng ký → đăng nhập → chạm NFC đọc CCCD → xác nhận thông tin → hoàn thiện hồ sơ → sẵn sàng đặt lệnh đầu tư |
| **Quản trị viên (Admin)** | Quản lý hệ thống | Xem danh sách tất cả người dùng → khóa/mở khóa tài khoản → gán/đổi vai trò |
| **Hệ thống (tự động)** | Bảo vệ nền tảng | Phát hiện đăng nhập sai liên tiếp → tạm khóa → gửi email cảnh báo; tự làm mới token khi hết hạn |

### Luồng chính và luồng không thành công

#### Luồng 1: Đăng ký tài khoản mới

```
Người dùng mở app → Điền email, mật khẩu, họ tên
  → finora-web gửi tới finora-user
    → finora-user tạo tài khoản trên Keycloak (credential)
    → finora-user tạo bản ghi user_profiles trong DB (hồ sơ FINORA)
    → finora-user phát Kafka event "UserRegisteredEvent"
    → finora-notification nhận event → gửi email HTML chào mừng
  → finora-web nhận phản hồi thành công → chuyển sang trang đăng nhập
```

**Không thành công:**
- Email đã tồn tại trên Keycloak → trả lỗi "Email đã được sử dụng", người dùng thấy thông báo rõ ràng, không tiết lộ email thuộc ai.
- Keycloak không phản hồi (timeout) → `finora-user` trả lỗi "Hệ thống đang bận, vui lòng thử lại sau", không tạo bản ghi profile mồ côi trong DB.
- Kafka mất event → email chào mừng không gửi được; tài khoản vẫn dùng được bình thường, email gửi lại khi consumer retry thành công hoặc qua DLT (dead-letter topic).

#### Luồng 2: Đăng nhập và quản lý phiên

**Trên `finora-web` (Browser — Cookie mode):**
```
Người dùng nhập email + mật khẩu trên finora-web
  → finora-user gửi credential tới Keycloak Token Endpoint
  → Keycloak xác thực → trả Access Token (JWT, sống 5 phút) + Refresh Token (sống 7 ngày)
  → finora-user ghi cả hai vào HTTP-Only Cookie trong response (body không chứa token)
  → finora-web tự động gửi Cookie trong mọi request tiếp theo (withCredentials: true)
  → Khi Access Token hết hạn (5 phút) → Axios interceptor bắt lỗi 401
    → Tự động gọi /api/v1/auth/refresh (gửi Cookie refresh_token)
    → finora-user đổi Refresh Token lấy cặp token mới từ Keycloak (Refresh Token Rotation)
    → Ghi Cookie mới → retry request ban đầu (người dùng không biết gì)
```

**Trên `finora-mobile` (React Native — Secure Storage mode):**
```
Người dùng nhập email + mật khẩu trên finora-mobile
  → App gửi credential tới finora-user kèm header X-Client-Type: mobile
  → finora-user gửi credential tới Keycloak Token Endpoint
  → Keycloak xác thực → trả Access Token + Refresh Token
  → finora-user trả JSON body: { "accessToken": "...", "refreshToken": "..." }
    (KHÔNG set Cookie — mobile không dùng Cookie)
  → App lưu cả hai vào Secure Storage (iOS Keychain / Android Keystore)
    qua thư viện react-native-keychain
  → Mọi request API tiếp theo: app đọc accessToken từ Secure Storage
    → gắn header Authorization: Bearer <accessToken>
  → Khi Access Token hết hạn (5 phút) → Axios interceptor bắt lỗi 401
    → Đọc refreshToken từ Secure Storage
    → Gọi POST /api/v1/auth/refresh body: { "refreshToken": "..." }
    → finora-user đổi lấy cặp token mới từ Keycloak (Refresh Token Rotation)
    → App lưu cặp token mới vào Secure Storage → retry request ban đầu
```

**Xử lý concurrent refresh (cả web và mobile):**
- Khi nhiều request cùng nhận 401 gần như đồng thời (ví dụ 3 API call đang chờ), Axios interceptor dùng **mutex pattern**: request đầu tiên gọi `/refresh`, các request sau đợi kết quả của request đầu thay vì gọi `/refresh` song song. Cơ chế: biến `isRefreshing` + mảng `failedQueue` chờ Promise resolve.
- Nếu refresh thất bại → tất cả request trong queue đều reject → chuyển về trang đăng nhập.

**Không thành công:**
- Sai mật khẩu → Keycloak trả lỗi, `finora-user` đếm lần sai trong Redis. Sau **5 lần sai trong 5 phút** → tạm khóa 15 phút + phát event cảnh báo → `finora-notification` gửi email "Phát hiện hoạt động đăng nhập bất thường từ IP x.x.x.x".
- Refresh Token hết hạn (sau 7 ngày không dùng app) → refresh thất bại → app chuyển về trang đăng nhập, người dùng đăng nhập lại.
- Refresh Token bị replay (token cũ sau khi đã rotation) → Keycloak phát hiện reuse → **revoke toàn bộ session** → người dùng phải đăng nhập lại trên tất cả thiết bị. Đây là cơ chế bảo vệ khi refresh token bị đánh cắp.
- Keycloak server lỗi → đăng nhập không được, trả lỗi tạm thời; phiên đang hoạt động vẫn dùng được cho đến khi Access Token hiện tại hết hạn.

#### Luồng 3: Đọc CCCD qua NFC

```
Người dùng mở finora-mobile → vào "Cập nhật hồ sơ" → bấm "Quét CCCD"
  → App hướng dẫn: "Chạm mặt sau CCCD vào điện thoại"
  → Điện thoại đọc chip NFC trên CCCD (giao thức ICAO 9303):
    - Xác thực truy cập chip bằng CAN (6 số in trên thẻ) hoặc MRZ
    - Đọc Data Group 1 (MRZ) + Data Group 13 (dữ liệu tiếng Việt)
    - KHÔNG xác minh chữ ký số Bộ Công An (Passive Authentication)
    - Trích xuất: số CCCD, họ tên, ngày sinh, giới tính, quê quán, địa chỉ
  → finora-mobile hiển thị form đã điền sẵn từ dữ liệu chip
  → Người dùng xem, sửa nếu cần, bấm "Xác nhận"
  → finora-mobile gửi dữ liệu có cấu trúc lên finora-user
    (POST /api/v1/users/profile/cccd-nfc — JSON, không phải file ảnh)
  → finora-user validate + lưu vào DB: số CCCD được hash (HMAC-SHA256)
    + mã hóa (AES-256-GCM), không bao giờ lưu dạng thô
```

**Không thành công:**
- Điện thoại không có NFC → app thông báo "Thiết bị không hỗ trợ NFC", hướng dẫn nhập tay thông tin CCCD.
- Chạm quá nhanh hoặc mất kết nối giữa chừng → đọc chip thất bại → app báo "Không đọc được, vui lòng giữ yên thẻ và thử lại". Không mất dữ liệu — chỉ cần chạm lại.
- CAN nhập sai (6 số trên thẻ không khớp) → chip từ chối xác thực → app báo "Mã truy cập không đúng, kiểm tra lại 6 số in trên thẻ CCCD".
- Số CCCD đã tồn tại (trùng hash trong DB) → `finora-user` từ chối lưu, thông báo "Số CCCD này đã được đăng ký trong hệ thống". Không tiết lộ tài khoản nào đang dùng số này.
- Người dùng trên `finora-web` (máy tính) → không có NFC → phải nhập tay toàn bộ thông tin CCCD qua form thông thường.

#### Luồng 4: Quên mật khẩu

```
Người dùng bấm "Quên mật khẩu" trên finora-web → Nhập email
  → finora-user tạo mã OTP 6 chữ số ngẫu nhiên
  → Lưu vào Redis (key = reset_otp:{userId}, TTL = 5 phút)
  → Phát Kafka event "PasswordResetOtpRequestedEvent"
  → finora-notification gửi email chứa mã OTP
  → Người dùng nhập OTP + mật khẩu mới trên finora-web
  → finora-user kiểm tra OTP khớp trong Redis → gọi Keycloak Admin API đổi mật khẩu
  → Xóa OTP khỏi Redis → Thông báo thành công
```

**Không thành công:**
- Email không tồn tại → hệ thống vẫn trả phản hồi trung tính "Nếu email tồn tại, mã OTP đã được gửi" (chống dò tài khoản, không xác nhận email có hay không).
- OTP hết hạn (> 5 phút) hoặc sai → thông báo "Mã không hợp lệ hoặc đã hết hạn".
- Kafka event mất → email OTP không gửi được → người dùng chờ, không nhận được → bấm "Gửi lại", hệ thống tạo OTP mới và phát event lại.

#### Luồng 5: Admin quản lý người dùng

```
Admin đăng nhập (có vai trò ROLE_ADMIN)
  → Xem danh sách người dùng (GET /api/v1/admin/users) — có phân trang
  → Khóa tài khoản nghi ngờ (POST /api/v1/admin/users/{id}/lock)
    → finora-user gọi Keycloak Admin API disable user
    → Phát event → finora-notification gửi email thông báo khóa tài khoản cho người dùng bị khóa
  → Gán vai trò (POST /api/v1/admin/users/{id}/roles)
    → finora-user gọi Keycloak Admin API gán Realm/Client Role
```

**Không thành công:**
- Người dùng không có quyền Admin gọi API admin → Spring Security `@PreAuthorize` trả `403 Forbidden`.
- Admin khóa chính mình → hệ thống từ chối thực hiện.

### Dữ liệu nào ảnh hưởng kết quả?

| Dữ liệu | Người dùng hiểu là gì | Ai / nguồn cung cấp | Dùng ở bước nào | Nếu thiếu / sai | Vì sao phải lưu |
|---|---|---|---|---|---|
| `keycloak_user_id` | (Ẩn) Mã liên kết với tài khoản đăng nhập | Keycloak tạo khi đăng ký | Mọi bước xác thực và lấy thông tin user | Profile mồ côi, không đăng nhập được | Là cầu nối duy nhất giữa credential (Keycloak) và hồ sơ (FINORA DB) |
| Email | Địa chỉ nhận thông báo, dùng để đăng nhập | Người dùng nhập khi đăng ký | Đăng nhập, nhận OTP, nhận cảnh báo, welcome email | Không nhận email thông báo, không lấy lại mật khẩu | Kênh liên lạc chính; Keycloak yêu cầu email duy nhất |
| Mật khẩu | Mật khẩu đăng nhập | Người dùng tạo khi đăng ký | Đăng nhập, đổi mật khẩu | Không đăng nhập được | Keycloak quản lý (hash bcrypt), `finora-user` không lưu và không nhìn thấy |
| Số CCCD (12 chữ số) | Số căn cước công dân | Chip NFC trên CCCD hoặc người dùng nhập tay | Xác nhận hồ sơ, kiểm tra trùng, phục vụ KYC sau này | Hồ sơ chưa đầy đủ, không thể nộp hồ sơ vay/đầu tư | Là dữ liệu định danh bắt buộc; lưu dạng hash (tìm kiếm) + mã hóa (bảo vệ PII) |
| Số điện thoại | Số liên hệ cá nhân | Người dùng nhập tay | Hồ sơ liên lạc, phục vụ KYC | Thiếu kênh liên hệ dự phòng | Lưu dạng hash + mã hóa tương tự CCCD |
| Họ tên, ngày sinh, giới tính, quê quán, địa chỉ | Thông tin cá nhân | Chip NFC trên CCCD hoặc người dùng nhập tay | Hiển thị hồ sơ, đối chiếu KYC | Hồ sơ không đầy đủ | Phục vụ quy trình xác minh danh tính và hợp đồng vay |
| Vai trò (`BORROWER`, `INVESTOR`, `ADMIN`) | "Tôi là người vay" / "Tôi là nhà đầu tư" | Chọn khi đăng ký, Admin có thể đổi | Mọi lần gọi API — quyết định được truy cập chức năng nào | Không truy cập được tính năng tương ứng | Phân quyền: người vay không thấy sàn đầu tư, nhà đầu tư không tạo hồ sơ vay, Admin quản lý hệ thống |
| CAN (Card Access Number) | 6 số in trên mặt trước CCCD, dùng để mở khóa chip NFC | Người dùng nhập hoặc app đọc MRZ | Xác thực truy cập chip NFC trước khi đọc dữ liệu | Không mở khóa được chip → phải nhập tay | Không lưu — chỉ dùng một lần tại thời điểm đọc chip |

### Ví dụ thực tế

**Ví dụ 1 — Người vay đăng ký và hoàn thiện hồ sơ:**

Minh (người muốn vay tiền) mở app FINORA trên điện thoại lần đầu:
1. Minh chọn "Đăng ký" → nhập email `minh.nguyen@gmail.com`, mật khẩu, chọn vai trò "Người vay".
2. Hệ thống tạo tài khoản → Minh nhận email chào mừng với hướng dẫn bước tiếp theo.
3. Minh đăng nhập → vào trang "Hồ sơ cá nhân" → bấm "Quét CCCD".
4. App hiển thị: "Nhập 6 số CAN in trên CCCD" → Minh nhập `123456` → "Chạm mặt sau CCCD vào điện thoại".
5. Minh úp mặt sau CCCD lên lưng điện thoại → giữ yên 2–3 giây → chip NFC trả dữ liệu → app tự động điền: Số CCCD `079203012345`, Họ tên `NGUYỄN VĂN MINH`, Ngày sinh `15/03/1995`, Quê quán `Hồ Chí Minh`.
6. Minh kiểm tra thông tin → mọi thứ đúng → bấm "Xác nhận".
7. Hệ thống lưu: `id_number_hash = HMAC-SHA256("079203012345")`, `id_number_encrypted = AES-GCM("079203012345")`. Trong DB không có chuỗi `079203012345` thô.
8. Minh giờ đã sẵn sàng nộp hồ sơ vay (luồng P1-B02/F02, ngoài plan này).

**Ví dụ 2 — Bị khóa vì đăng nhập sai:**

Ai đó (hoặc bot) nhập sai mật khẩu của tài khoản Minh 5 lần liên tiếp trong 3 phút:
1. Lần 1–4: hệ thống trả "Sai mật khẩu", đếm trong Redis.
2. Lần 5: Keycloak tạm khóa 15 phút + Redis rate limiter chặn thêm → trả "Tài khoản tạm khóa, thử lại sau 15 phút".
3. Minh nhận email cảnh báo: "Phát hiện 5 lần đăng nhập thất bại từ IP 103.15.xx.xx lúc 14:32. Nếu không phải bạn, hãy đổi mật khẩu ngay."
4. Console log của `finora-user` ghi: `Login failed for user mi***@gmail.com from IP 103.15.xx.xx` — không hiển thị email đầy đủ.

**Ví dụ 3 — CCCD đã trùng:**

Một người cố đăng ký tài khoản thứ hai với cùng số CCCD `079203012345`:
1. Quét NFC CCCD → chip trả dữ liệu thành công → app điền form.
2. Bấm "Xác nhận" → `finora-user` tính HMAC-SHA256 và phát hiện hash đã tồn tại trong DB.
3. Hệ thống trả lỗi: "Số CCCD này đã được đăng ký". Không nói tài khoản nào đang dùng.

**Ví dụ 4 — Admin gán vai trò:**

Admin Lan muốn thêm quyền Investor cho tài khoản Minh (hiện chỉ là Borrower):
1. Lan vào trang "Quản lý người dùng" → tìm Minh → bấm "Gán vai trò" → chọn thêm `INVESTOR`.
2. Hệ thống gọi Keycloak Admin API thêm Composite Role `ROLE_INVESTOR`.
3. Lần đăng nhập tiếp theo, JWT của Minh chứa cả `ROLE_BORROWER` và `ROLE_INVESTOR` → Minh truy cập được cả chức năng vay lẫn đầu tư.

### Điều kiện hoàn thành nghiệp vụ

| # | Điều kiện | Cách kiểm chứng |
|---|---|---|
| 1 | Người dùng mới đăng ký được tài khoản, đăng nhập thành công và nhận email chào mừng | Chạy luồng đăng ký → kiểm tra Keycloak có user, DB có profile, hộp thư có email |
| 2 | **Web:** Token lưu trong HttpOnly Cookie, `localStorage` trống, response body không chứa token. **Mobile:** Token lưu trong Secure Storage (Keychain/Keystore), không có Cookie, app gửi qua header `Authorization: Bearer` | Web: DevTools → Cookies vs LocalStorage. Mobile: login response body chứa token, không có Set-Cookie header |
| 3 | Access Token hết hạn sau 5 phút → silent refresh tự động đổi token mới (cả web và mobile), người dùng không bị gián đoạn. Nhiều request cùng bị 401 → chỉ gọi refresh 1 lần (mutex pattern) | Đợi 5 phút, thao tác tiếp → request thành công, không chuyển trang login. Kiểm tra server log chỉ có 1 lần refresh |
| 4 | Đăng nhập sai 5 lần → tạm khóa 15 phút, email cảnh báo gửi ngay | Nhập sai 5 lần → lần 6 bị chặn → kiểm tra hộp thư có email cảnh báo |
| 5 | Chạm NFC CCCD trên điện thoại → app tự động trích xuất ít nhất: số CCCD, họ tên, ngày sinh; điền sẵn vào form | Quét CCCD thật bằng NFC → kiểm tra form có giá trị đúng |
| 6 | Chạm NFC thất bại (mất kết nối, CAN sai) → app báo lỗi có hướng dẫn, không crash | Rút thẻ giữa chừng → nhận thông báo "Thử lại" rõ ràng |
| 7 | Thiết bị không có NFC → app hướng dẫn nhập tay, không chặn hoàn toàn | Mở app trên điện thoại cũ không có NFC → hiển thị form nhập tay |
| 8 | Số CCCD và SĐT trong DB là hash + mã hóa, không có bản thô | Query trực tiếp DB → cột `id_number_hash` là chuỗi 64 ký tự, `id_number_encrypted` là chuỗi mã hóa |
| 9 | Số CCCD trùng → từ chối, không tiết lộ tài khoản đang dùng | Đăng ký CCCD đã tồn tại → lỗi chung chung |
| 10 | Console log không hiển thị email, CCCD, SĐT, OTP dạng thô | Grep log sau khi chạy luồng → không tìm thấy PII rõ ràng |
| 11 | Người dùng Borrower gọi API admin → nhận `403 Forbidden` | Đăng nhập Borrower → gọi GET /api/v1/admin/users → 403 |
| 12 | Quên mật khẩu → nhận OTP qua email → đổi mật khẩu thành công | Chạy luồng → kiểm tra email có OTP → đổi mật khẩu → đăng nhập thành công |
| 13 | Người dùng trên `finora-web` (máy tính) → nhập tay CCCD qua form → lưu thành công | Đăng nhập web → nhập thông tin CCCD → xác nhận → kiểm tra DB |

---

## 📌 Tổng Quan Trách Nhiệm & Kiến Trúc (Architecture & SoR)

```
[finora-mobile (React Native)]              [finora-web (React + Vite)]
       │                                           │
       ├── (1) NFC đọc chip CCCD trực tiếp         │
       │     (ICAO 9303, không qua server)          │
       │                                           │
       ├── (2a) Login → nhận JSON body ────────────┤── (2b) Login → nhận HttpOnly Cookie
       │   {accessToken, refreshToken}             │   Set-Cookie: access_token, refresh_token
       │   Lưu vào Secure Storage                  │
       │   (Keychain iOS / Keystore Android)       │
       │                                           │
       ├── (3a) Request API ───────────────────────┼──► [finora-gateway] ─► [finora-user (Port 8085)]
       │   Header: Authorization: Bearer <AT>      │       (Auto-send Cookie)  │ (DualBearerTokenResolver)
       │                                           │                         │ (HMAC-SHA256 & AES-256-GCM)
       └── (4) POST /cccd-nfc (dữ liệu chip) ─────┘                         │ (PiiMasker & Redis Rate Limit)
                                                                              │
                                                       [Keycloak (Port 8180)]─┘ (Brute-Force / SMTP)
                                                                              │
                                                                              ├── (Kafka Event) ──► [finora-notification (Port 8086)]
                                                                              │                           │ (JavaMailSender HTML)
                                                                              │                           ▼
                                                                              │                    [User Email Inbox]
                                                                              │
                                                                         [finora-ai (Port 8000)]
                                                                         (Credit Scoring — không tham gia luồng CCCD)
```

- **Quản lý Token — Dual-Mode (Web: Cookie, Mobile: Secure Storage):**

  | Thuộc tính | `finora-web` (Browser) | `finora-mobile` (React Native) |
  |---|---|---|
  | **Access Token (5 phút)** | `HttpOnly; Secure; SameSite=Lax` Cookie `access_token` | Lưu trong **iOS Keychain / Android Keystore** via `react-native-keychain` |
  | **Refresh Token (7 ngày)** | `HttpOnly; Secure; SameSite=Lax` Cookie `refresh_token` (Path=`/api/v1/auth`) | Lưu trong **iOS Keychain / Android Keystore** via `react-native-keychain` |
  | **Gửi token đi** | Tự động qua Cookie (`withCredentials: true`) | Header `Authorization: Bearer <access_token>` do app tự gắn |
  | **Chống XSS** | `HttpOnly` Cookie → JS không truy cập được | Secure Storage nằm ngoài JS runtime, được OS mã hóa |
  | **Silent Refresh** | Axios interceptor bắt 401 → gọi `/refresh` (gửi Cookie) | Axios interceptor bắt 401 → gọi `/refresh` (gửi refresh token qua body/header) |

  - **Chống tấn công XSS trên web:** Tuyệt đối **KHÔNG** lưu Token vào `localStorage` hay `sessionStorage` ở `finora-web`.
  - **Vì sao mobile không dùng Cookie:** React Native không chạy trong browser — không có cookie jar tự động; `HttpOnly` cookie không hoạt động đáng tin cậy trên native app. iOS Keychain và Android Keystore được OS mã hóa bằng hardware (Secure Enclave / TEE), bảo mật tương đương hoặc tốt hơn `HttpOnly` cookie.

- **Backend Dual-Mode — `DualBearerTokenResolver`:**
  - Backend resolve JWT theo thứ tự: **(1)** đọc header `Authorization: Bearer <token>` → **(2)** nếu không có header, đọc Cookie `access_token`. Cùng một `SecurityConfig`, cùng logic xác thực — chỉ khác nguồn lấy token.
  - Endpoint `/api/v1/auth/login` trả kết quả khác nhau theo header `X-Client-Type`:
    - `X-Client-Type: web` (hoặc không có header) → Set `HttpOnly` Cookie, response body không chứa token.
    - `X-Client-Type: mobile` → Response body JSON `{ "accessToken": "...", "refreshToken": "..." }`, **không** set Cookie.
  - Endpoint `/api/v1/auth/refresh`:
    - Web → đọc refresh token từ Cookie, trả lại Cookie mới.
    - Mobile → đọc refresh token từ request body `{ "refreshToken": "..." }`, trả JSON `{ "accessToken": "...", "refreshToken": "..." }`.

- **Bảo vệ Dữ liệu Nhạy cảm (CCCD & SĐT):**
  - **Mã hóa & Deterministic Hash:** Toàn bộ số CCCD và Số điện thoại được lưu ở DB `user_profiles` dưới dạng **HMAC-SHA256 Hash** (để kiểm tra trùng lặp và truy vấn nhanh) và **AES-256-GCM Encrypted** (dữ liệu mã hóa tĩnh).
- **`finora-mobile` (React Native):** Đọc chip NFC CCCD trực tiếp trên điện thoại (ICAO 9303); gửi dữ liệu có cấu trúc lên `finora-user`; xác thực bằng `Authorization: Bearer` header với token lưu trong Secure Storage (Keychain/Keystore).
- **`finora-web` (React + Vite):** UI trên web; không hỗ trợ NFC — người dùng web nhập tay CCCD qua form; xác thực bằng `HttpOnly` Cookie tự động gửi qua `withCredentials: true`.
- **Keycloak (Port 8180):** Quản lý Credentials, Session, Token (JWT), Realm Roles, Client Roles / Composite Roles, Brute-Force Detection (tạm khóa sau 5 lần nhập sai). **Bật Refresh Token Rotation** (`Revoke Refresh Token = ON`, `Refresh Token Max Reuse = 0`) — mỗi lần refresh cấp refresh token mới, token cũ bị vô hiệu; nếu phát hiện token cũ bị replay → revoke toàn bộ session.
- **`finora-user` (Port 8085):** Cấu hình `DualBearerTokenResolver` đọc JWT từ Header (mobile) hoặc Cookie (web); Bảo vệ API bằng **Method Security (`@PreAuthorize`)**; Che dấu dữ liệu PII (`PiiMasker`) khi ghi log; JPA `CryptoConverter`; Redis Rate Limiter; Phát Kafka Events; Nhận dữ liệu CCCD từ mobile (JSON) hoặc form nhập tay từ web.
- **`finora-notification` (Port 8086):** Consume Kafka Events, dùng HTML Template String gửi Email thông báo/OTP qua `JavaMailSender`.
- **`finora-ai` (Port 8000):** Credit Scoring, eKYC (face/liveness — plan riêng). **Không tham gia luồng đọc CCCD** — chip NFC trả dữ liệu có cấu trúc, không cần OCR.

---

## 🎯 GIAI ĐOẠN 1: Tích Hợp Keycloak vào `finora-user`, Phân Quyền Chức Năng, HTTP-Only Cookie & Email

### Bước 1.1: Cấu hình Spring Security OAuth2 Resource Server & Dual Bearer Token Resolver

#### 📝 Nhiệm vụ Kỹ thuật:
- Thêm dependency `spring-boot-starter-oauth2-resource-server` vào `finora-user/pom.xml`.
- Viết `DualBearerTokenResolver.java` kế thừa `BearerTokenResolver` của Spring Security, resolve JWT theo thứ tự ưu tiên:
  1. **Header `Authorization: Bearer <token>`** — dùng cho `finora-mobile` (React Native gửi token lấy từ Secure Storage).
  2. **Cookie `access_token`** — dùng cho `finora-web` (browser tự động gửi `HttpOnly` cookie).
  - Nếu cả hai đều có, ưu tiên Header (mobile luôn gửi header, web không bao giờ gửi header).
- Tạo `SecurityConfig.java` cấu hình Spring Security 6 làm OAuth2 Resource Server sử dụng `DualBearerTokenResolver`.
- Viết Custom `JwtAuthenticationConverter` trích xuất `realm_access.roles` và `resource_access` từ JWT claim của Keycloak thành `GrantedAuthority`.
- Cấu hình `application.yml` định vị issuer & JWK Set URI từ Keycloak Server (`http://localhost:8180/realms/finora`).

#### 📚 Kiến thức cần học & nghiên cứu:
1. **XSS vs. CSRF Security in Web Apps:** Tại sao lưu JWT ở `HttpOnly` Cookie bảo vệ chống XSS tốt hơn `localStorage`, và cách dùng `SameSite=Lax` / `CSRF Token` để chống CSRF.
2. **Mobile Token Storage — Keychain/Keystore:** React Native không có cookie jar như browser. iOS Keychain và Android Keystore được OS mã hóa bằng hardware (Secure Enclave / TEE), bảo mật tương đương hoặc tốt hơn `HttpOnly` cookie. Dùng thư viện `react-native-keychain` để lưu/đọc token.
3. **Spring Security `BearerTokenResolver`:** Cách tùy biến nơi trích xuất Token — hỗ trợ dual-mode: kiểm tra Header trước (mobile), fallback Cookie (web).
4. **Keycloak Realm Token Lifespan & Refresh Token Rotation:** Cấu hình Access Token Lifespan (5 phút), Refresh Token Lifespan (7 ngày), bật `Revoke Refresh Token = ON` và `Refresh Token Max Reuse = 0` để mỗi lần refresh cấp token mới, phát hiện replay attack.

#### 🔗 Nguồn tài liệu Research:
- 📖 [OWASP HTML5 Security Cheat Sheet - Token Storage in Cookies](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html)
- 📖 [Baeldung - Read Spring Security OAuth2 Token from Cookie](https://www.baeldung.com/spring-security-oauth2-token-cookie)
- 📖 [Keycloak Docs - Realm Settings Session & Token Lifespans](https://www.keycloak.org/docs/latest/server_admin/#_timeouts)
- 📖 [react-native-keychain GitHub](https://github.com/oblador/react-native-keychain)
- 📖 [OWASP Mobile Security - Secure Data Storage](https://mas.owasp.org/MASTG/tests/android/MASVS-STORAGE/)

---

### Bước 1.2: Triển khai Auth Controller (Dual-Mode), Silent Refresh & Keycloak Admin Client

#### 📝 Nhiệm vụ Kỹ thuật:
- Cấu hình Keycloak Admin REST Client (`keycloak-admin-client`) trong `KeycloakAdminService.java`.
- Tạo `AuthController.java` trong `finora-user` (hoặc `finora-gateway`):

  - **Endpoint `POST /api/v1/auth/login`:** Nhận `username` & `password`, gọi Keycloak cấp Token. Response khác nhau theo header `X-Client-Type`:
    - **Web** (`X-Client-Type: web` hoặc không có header):
      ```http
      Set-Cookie: access_token=eyJhbGci...; HttpOnly; Path=/; Max-Age=300; SameSite=Lax; Secure
      Set-Cookie: refresh_token=eyJhbGci...; HttpOnly; Path=/api/v1/auth; Max-Age=604800; SameSite=Lax; Secure
      Body: { "userId": "...", "email": "...", "roles": [...] }  ← KHÔNG chứa token
      ```
    - **Mobile** (`X-Client-Type: mobile`):
      ```json
      {
        "userId": "...", "email": "...", "roles": [...],
        "accessToken": "eyJhbGci...",
        "refreshToken": "eyJhbGci..."
      }
      ```
      Không set Cookie. App lưu `accessToken` và `refreshToken` vào Secure Storage.

  - **Endpoint `POST /api/v1/auth/refresh` (Silent Refresh Flow):** Hỗ trợ dual-mode:
    - **Web:** Đọc Cookie `refresh_token` $\rightarrow$ Gửi tới Keycloak Token Endpoint $\rightarrow$ Set lại Cookie `access_token` + `refresh_token` mới (Refresh Token Rotation).
    - **Mobile:** Đọc `refreshToken` từ request body `{ "refreshToken": "..." }` $\rightarrow$ Gửi tới Keycloak $\rightarrow$ Trả JSON `{ "accessToken": "...", "refreshToken": "..." }`.
    - Cả hai mode đều nhận cặp token mới (Keycloak bật Refresh Token Rotation) — token cũ bị vô hiệu.

  - **Endpoint `POST /api/v1/auth/logout`:**
    - **Web:** Revoke Refresh Token trên Keycloak + xóa Cookies (set `Max-Age=0`).
    - **Mobile:** Nhận `{ "refreshToken": "..." }` từ body → Revoke trên Keycloak. App xóa token khỏi Secure Storage phía client.

  - **Endpoint `POST /api/v1/auth/register`:** Nhận request từ `finora-web` hoặc `finora-mobile` $\rightarrow$ Tạo user trên Keycloak $\rightarrow$ Tạo bản ghi Profile trong DB `finora-user`. Response tương tự login (dual-mode) để người dùng tự động đăng nhập sau đăng ký.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **HTTP Cookie Attributes:** `HttpOnly` (chống JS truy cập), `Secure` (chỉ truyền qua HTTPS), `SameSite` (Lax/Strict chống CSRF), `Max-Age` / `Expires`.
2. **Axios `withCredentials: true` (Web) vs. Axios interceptor + Secure Storage (Mobile):** Web dùng cookie tự động; Mobile đọc token từ Keychain/Keystore rồi gắn header `Authorization: Bearer`.
3. **Silent Token Refresh với Mutex Pattern:** Kỹ thuật interceptor trên Axios bắt lỗi `401 Unauthorized` — dùng biến `isRefreshing` + `failedQueue` đảm bảo chỉ gọi `/refresh` một lần dù nhiều request cùng bị 401. Ngăn thundering herd gọi refresh đồng thời.
4. **Refresh Token Rotation & Reuse Detection:** Keycloak cấp refresh token mới mỗi lần refresh; nếu phát hiện token cũ bị dùng lại (bị đánh cắp) → revoke toàn bộ session.

#### 🔗 Nguồn tài liệu Research:
- 📖 [MDN Web Docs - Using HTTP cookies](https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies)
- 📖 [Axios Documentation - Handling Credentials & Cookies](https://axios-http.com/docs/req_config)
- 📖 [react-native-keychain — Secure credential storage](https://github.com/oblador/react-native-keychain)
- 📖 [Keycloak Docs - Revoke Refresh Token](https://www.keycloak.org/docs/latest/server_admin/#_timeouts)

---

### Bước 1.3: Quản lý Domain Profile & Utility `@CurrentUser`

#### 📝 Nhiệm vụ Kỹ thuật:
- Cập nhật Flyway Migration (`V1__init_user_schema.sql`) tạo bảng `user_profiles` chứa `keycloak_user_id` (UUID - Unique Index).
- Tạo `SecurityUtils.java` hoặc Annotation `@CurrentUser` để lấy tiện lợi thông tin User đang đăng nhập (`userId`, `keycloakUserId`, `email`, `roles`) từ `SecurityContextHolder`.
- Viết API `GET /api/v1/users/me` và `PUT /api/v1/users/me` cho `finora-web` gọi lấy và cập nhật hồ sơ cá nhân.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **Spring Security Context & `AuthenticationPrincipal`:** Cách lưu trữ và truy cập thông tin `SecurityContext` trong Thread Local.
2. **Domain-Driven Design (DDD) & System of Record Boundary:** Tách biệt thông tin xác thực (Keycloak) và thông tin hồ sơ (UserProfile).

#### 🔗 Nguồn tài liệu Research:
- 📖 [Baeldung - Get Current Logged-in User in Spring Security](https://www.baeldung.com/get-user-in-spring-security)

---

### Bước 1.4: Triển khai Hệ Thống Email Thông Báo (Chào mừng, OTP Đổi mật khẩu, Cảnh báo nghi ngờ)

#### 📝 Nhiệm vụ Kỹ thuật:

##### Case A: Email Đăng ký tài khoản thành công (Welcome Email)
1. **Qua Keycloak Email Verification:** Cấu hình Realm SMTP Server trong Keycloak Admin Console $\rightarrow$ Khi tạo User, Keycloak gửi Email xác thực địa chỉ mail (`VERIFY_EMAIL`).
2. **Qua `finora-notification` (Kafka Event Driven):** Khi `finora-user` tạo tài khoản thành công $\rightarrow$ Phát event `UserRegisteredEvent` (chứa `userId`, `email`, `fullName`) lên Kafka topic `user-events`.
3. `finora-notification` consume event $\rightarrow$ Gửi mail HTML chào mừng qua `JavaMailSender` (dùng HTML Template String định dạng sạch đẹp).

##### Case B: Email OTP khi Đổi / Quên Mật Khẩu (Password Reset OTP)
1. **Keycloak Native Execution:** `finora-user` gọi Keycloak Admin API kích hoạt Action `UPDATE_PASSWORD` $\rightarrow$ Keycloak tự tạo OTP / Secure Action Token gửi tới Email của user.
2. **Custom OTP Code via Redis & Notification:** 
   - `finora-user` phát sinh mã OTP 6 chữ số ngẫu nhiên, lưu vào Redis (`key = reset_otp:{userId}`, TTL = 5 phút).
   - `finora-user` phát event `PasswordResetOtpRequestedEvent` sang Kafka.
   - `finora-notification` nhận event $\rightarrow$ Gửi email chứa OTP 6 số tới hộp thư người dùng để nhập trên form `finora-web`.

##### Case C: Cảnh báo Hoạt động Nghi ngờ (Suspicious Activity Alert)
1. **Keycloak Brute Force & Login Events:** Cấu hình Keycloak Event Listener (hoặc Custom SPI) bắt các sự kiện đăng nhập thất bại liên tiếp (Brute force) hoặc Đăng nhập từ IP/Thiết bị lạ.
2. **Kafka Event Security Alert:** Keycloak hoặc `finora-user` / Gateway phát event `SuspiciousActivityDetectedEvent` (chứa `ipAddress`, `userAgent`, `timestamp`, `reason`) lên Kafka topic `security-events`.
3. `finora-notification` consume event $\rightarrow$ Gửi email cảnh báo ngay lập tức ("Phát hiện đăng nhập nghi ngờ từ IP x.x.x.x").

#### 📚 Kiến thức cần học & nghiên cứu:
1. **Cấu hình Keycloak SMTP & Theme Email:** Cách thiết lập Mail Trap / Gmail SMTP Server trên Admin Console của Keycloak.
2. **Spring Boot Email Support (`spring-boot-starter-mail`):** Cấu hình `JavaMailSender` gửi MimeMessage HTML trong `finora-notification`.
3. **Kafka Event-Driven Architecture:** Producer (`finora-user` / Keycloak SPI) $\rightarrow$ Topic (`user-events`, `security-events`) $\rightarrow$ Consumer (`finora-notification`).

#### 🔗 Nguồn tài liệu Research:
- 📖 [Keycloak Docs - Configuring Email / SMTP Settings](https://www.keycloak.org/docs/latest/server_admin/#_email)
- 📖 [Baeldung - Guide to Spring Email (JavaMailSender)](https://www.baeldung.com/spring-email)
- 📖 [Spring for Apache Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)

---

### Bước 1.5: Triển khai Phân Quyền Theo Chức Năng (Feature-based Authorization & Fine-grained RBAC)

#### 📝 Nhiệm vụ Kỹ thuật:

1. **Thiết kế Ma Trận Quyền Chức Năng (Feature Authorization Matrix):**
   - **Nhóm Quyền Hồ Sơ (`user:profile`):**
     - `user:profile:read`: Xem thông tin hồ sơ cá nhân (`BORROWER`, `INVESTOR`, `ADMIN`).
     - `user:profile:write`: Cập nhật thông tin cá nhân (`BORROWER`, `INVESTOR`).
     - `user:cccd:scan`: Quét NFC hoặc nhập tay CCCD (`BORROWER`, `INVESTOR`).
   - **Nhóm Quyền Quản Trị Hệ Thống (`user:admin`):**
     - `user:admin:read_all`: Xem danh sách tất cả người dùng trong hệ thống (`ADMIN`).
     - `user:admin:lock`: Khóa / mở khóa tài khoản người dùng (`ADMIN`).
     - `user:admin:assign_role`: Thay đổi hoặc gán vai trò người dùng (`ADMIN`).

2. **Cấu hình Keycloak Composite Roles:**
   - Định nghĩa các **Client Roles** tương ứng với các Fine-grained Permissions (`user:profile:read`, `user:cccd:scan`...).
   - Gom các Client Roles này vào các **Realm Roles (Composite Roles)** (`ROLE_BORROWER`, `ROLE_INVESTOR`, `ROLE_ADMIN`).

3. **Áp dụng Method Security bằng Annotation trong Spring Boot (`finora-user`):**
   - Bật `@EnableMethodSecurity(prePostEnabled = true)` tại `SecurityConfig.java`.
   - Bảo vệ từng endpoint Controller bằng `@PreAuthorize("hasAuthority('user:cccd:scan')")` hoặc `@PreAuthorize("hasAuthority('user:admin:lock')")`.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **Role-Based Access Control (RBAC) vs Fine-Grained Authorization:** Kiểm tra theo Role vs Permission.
2. **Spring Security `@EnableMethodSecurity` & SpEL:** Cách viết biểu thức `@PreAuthorize`.
3. **Keycloak Composite Roles Architecture:** Gom nhóm Client Roles vào Realm Roles.

#### 🔗 Nguồn tài liệu Research:
- 📖 [Spring Security Method Security Reference](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- 📖 [Keycloak Documentation - Composite Roles](https://www.keycloak.org/docs/latest/server_admin/#_composite_roles)

---

### Bước 1.6: Bảo Vệ Chống Dò Mật Khẩu (Brute-Force Protection) & Masking Dữ Liệu PII Trong Console Log

#### 📝 Nhiệm vụ Kỹ thuật:

1. **Chống Tấn công Dò Mật Khẩu (Brute-Force Protection):**
   - **Keycloak Brute Force Detector Config:** Bật thuộc tính Brute Force Detection trong Keycloak Realm Settings:
     - `Max Login Failures`: 5 lần sai.
     - `Wait Increment`: Tăng thời gian chờ cho mỗi lần nhập sai tiếp theo.
     - `Quick Login Check Milliseconds`: 1000ms (phát hiện bot/script tự động dò mật khẩu).
     - `Minimum Quick Login Wait`: 1 phút.
   - **Redis Rate Limiting trong `finora-user` / Gateway:**
     - Đếm số lần đăng nhập sai theo IP/Username trong Redis (`key = fail_count:{ip_or_user}`). Nếu vượt quá 5 lần/5 phút $\rightarrow$ Tự động tạm khóa IP/User trong 15 phút.
     - Phát Kafka event `SuspiciousActivityDetectedEvent` sang `finora-notification` để gửi Email cảnh báo dò mật khẩu đến người dùng.

2. **Quy tắc Che Dấu Thông Tin Cá Nhân (PII Masking Rule) Trong Log:**
   - Tạo Utility `PiiMasker.java` (trong `finora-common` hoặc `finora-user`):
     - **Email:** `th***@gmail.com` (chỉ hiển thị 1-2 ký tự đầu + domain).
     - **Số CCCD:** `079*******12` (chỉ hiển thị 3 số đầu và 2 số cuối, che 7 số giữa bằng `*`).
     - **Số điện thoại:** `09****5678` (chỉ hiển thị 2 số đầu và 4 số cuối).
     - **Mã OTP:** `******` (tuyệt đối che kín toàn bộ bằng `*`).
     - **Họ và tên:** `Ng***** V** A` (chỉ hiển thị 1-2 ký tự đầu của mỗi từ).
   - Áp dụng `PiiMasker` cho toàn bộ các lệnh `log.info(...)`, `log.debug(...)` hoặc gán Logback Custom Pattern Layout để tự động mask PII trước khi in ra Console Log.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **Keycloak Brute Force Detection Architecture:** Cách Keycloak tracking IP, User Failures và Temporary Lockout.
2. **Redis Bucket / Sliding Window Rate Limiter Pattern:** Cách xây dựng đếm rate limit bằng Redis `INCR` và `EXPIRE`.
3. **Data Masking / Anonymization Techniques:** Kỹ thuật Regex Masking PII trong Spring Boot.

#### 🔗 Nguồn tài liệu Research:
- 📖 [Keycloak Security Guide - Protecting Against Brute Force Attacks](https://www.keycloak.org/docs/latest/server_admin/#_brute_force)
- 📖 [Baeldung - Logback Custom Converter for Sensitive Data Masking](https://www.baeldung.com/logback-mask-sensitive-data)

---

### Bước 1.7: Hashing & Mã Hóa Dữ Liệu Nhạy Cảm (Số CCCD & Số Điện Thoại)

#### 📝 Nhiệm vụ Kỹ thuật:

1. **Chiến lược Mã Hóa & Hash Dữ Liệu (Encryption & Deterministic Hash Strategy):**
   - **Bản ghi Database `user_profiles` trong `finora-user`:**
     - `id_number_hash` (CHAR(64)): Deterministic Hash bằng **HMAC-SHA256** với Secret Salt hệ thống. Dùng tạo Unique Index, tìm kiếm nhanh và kiểm tra trùng lặp CCCD mà không bao giờ lưu plain-text.
     - `id_number_encrypted` (VARCHAR): Mã hóa tĩnh **AES-256-GCM** (chỉ giải mã khi xuất thông tin cho chính chủ hoặc admin).
     - `phone_hash` (CHAR(64)) & `phone_encrypted` (VARCHAR): Áp dụng tương tự cho số điện thoại.
2. **Truyền nhận Event qua Kafka Inter-Service:**
   - Các Kafka Events chỉ truyền `userId`, `id_number_hash`, `phone_hash`. Tuyệt đối không truyền chuỗi số CCCD hoặc SĐT thô trên Kafka Message Payload.
3. **Mã hóa/Giải mã tự động qua JPA AttributeConverter:**
   - Viết `CryptoConverter.java` triển khai `AttributeConverter<String, String>` trong Spring Data JPA để tự động encrypt/decrypt các entity fields khi ghi/đọc DB.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **HMAC-SHA256 (Hash-based Message Authentication Code):** Kỹ thuật Hash bảo mật có Secret Salt chống tấn công bảng Rainbow Table.
2. **Mã hóa AES-256-GCM (Galois/Counter Mode):** Chuẩn mã hóa đối xứng bảo vệ dữ liệu PII tĩnh (Data at Rest).
3. **JPA `AttributeConverter`:** Tự động mã hóa/giải mã entity attributes trong Spring Boot Data JPA.

#### 🔗 Nguồn tài liệu Research:
- 📖 [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
- 📖 [Baeldung - Entity Encryption with JPA Attribute Converter](https://www.baeldung.com/jpa-attribute-converters)

---

## 🎯 GIAI ĐOẠN 2: Đọc CCCD qua NFC trên `finora-mobile` (React Native)

### Bước 2.1: Xây dựng NFC Reader đọc chip CCCD trên React Native (`finora-mobile`)

#### 📝 Nhiệm vụ Kỹ thuật:
- Khởi tạo project `finora-mobile` bằng React Native CLI.
- Thêm thư viện NFC: `react-native-nfc-manager` (đọc/ghi NFC tags, hỗ trợ ISO 14443 trên Android & iOS).
- Viết module `src/services/cccd-nfc-reader.ts`:
  - **Kiểm tra NFC khả dụng:** Kiểm tra thiết bị có NFC không, NFC đã bật chưa → hướng dẫn bật nếu tắt hoặc fallback nhập tay nếu không có.
  - **Xác thực truy cập chip (BAC/PACE):** Người dùng nhập CAN (Card Access Number — 6 chữ số in trên mặt trước CCCD) → app dùng CAN để xác thực với chip qua giao thức ICAO 9303.
  - **Đọc Data Groups:** Đọc DG1 (MRZ — dữ liệu máy đọc được) và DG13 (dữ liệu tiếng Việt: họ tên, ngày sinh, giới tính, quê quán, địa chỉ, số CCCD).
  - **KHÔNG xác minh chữ ký số Bộ Công An** (Passive Authentication) — dữ liệu từ chip được tin tưởng trực tiếp, người dùng xác nhận trước khi lưu.
  - **Parse và chuẩn hóa:** Chuyển dữ liệu thô từ chip thành JSON có cấu trúc (`{ idNumber, fullName, dateOfBirth, gender, placeOfOrigin, address }`).
- Viết màn hình `CccdNfcScanScreen`:
  - Bước 1: Form nhập CAN (6 số).
  - Bước 2: Hướng dẫn + animation "Chạm mặt sau CCCD vào điện thoại".
  - Bước 3: Hiển thị kết quả dạng form chỉnh sửa được → nút "Xác nhận".

#### 📚 Kiến thức cần học & nghiên cứu:
1. **ICAO 9303 (Machine Readable Travel Documents):** Chuẩn quốc tế cho thẻ căn cước/hộ chiếu có chip — giải thích đơn giản: chip trên CCCD hoạt động giống hộ chiếu điện tử, lưu dữ liệu cá nhân trong các "Data Group" (DG) theo chuẩn quốc tế.
2. **BAC (Basic Access Control) & CAN:** Cơ chế mở khóa chip — cần nhập 6 số CAN (in trên thẻ) để chip cho phép đọc dữ liệu, ngăn người lạ quét lén thẻ. Giải thích: giống mã PIN để mở khóa thẻ.
3. **React Native NFC Manager:** Thư viện `react-native-nfc-manager` — API đọc/ghi NFC tag trên Android (NfcA/IsoDep) và iOS (CoreNFC/NFCTagReaderSession).
4. **ISO 14443 Type A/B:** Giao thức truyền thông vật lý giữa điện thoại và chip CCCD (tầm đọc ~4cm, tốc độ 106–848 kbps).

#### 🔗 Nguồn tài liệu Research:
- 📖 [react-native-nfc-manager GitHub](https://github.com/revtel/react-native-nfc-manager)
- 📖 [ICAO Doc 9303 Part 10 — Logical Data Structure](https://www.icao.int/publications/Documents/9303_p10_cons_en.pdf)
- 📖 [Android NFC Developer Guide](https://developer.android.com/develop/connectivity/nfc)
- 📖 [Apple CoreNFC Documentation](https://developer.apple.com/documentation/corenfc)

---

### Bước 2.2: Tích hợp API nhận dữ liệu CCCD NFC từ `finora-mobile` vào `finora-user`

#### 📝 Nhiệm vụ Kỹ thuật:
- Viết API endpoint trong `finora-user`: **`POST /api/v1/users/profile/cccd-nfc`** nhận JSON có cấu trúc từ `finora-mobile` (không phải file ảnh):
  ```json
  {
    "idNumber": "079203012345",
    "fullName": "NGUYỄN VĂN MINH",
    "dateOfBirth": "1995-03-15",
    "gender": "NAM",
    "placeOfOrigin": "Hồ Chí Minh",
    "address": "123 Nguyễn Huệ, Q.1, TP.HCM"
  }
  ```
- Validate dữ liệu: số CCCD đúng 12 chữ số, ngày sinh hợp lệ, tên không trống.
- Kiểm tra trùng CCCD: tính HMAC-SHA256 hash → so sánh `id_number_hash` trong DB.
- Nếu không trùng → lưu hash + mã hóa vào `user_profiles` (dùng `CryptoConverter` từ Bước 1.7).
- Viết API cho `finora-web` (nhập tay): **`PUT /api/v1/users/profile/cccd-manual`** — cùng payload JSON, cùng logic validate/hash/encrypt — dành cho người dùng web không có NFC.

#### 📚 Kiến thức cần học & nghiên cứu:
1. **React Native ↔ Spring Boot REST:** Cấu hình CORS, cookie handling trên mobile (khác browser về cookie domain/path).
2. **Validation & Duplicate Detection:** Server-side validation vẫn bắt buộc dù dữ liệu từ chip — client có thể bị giả mạo.

#### 🔗 Nguồn tài liệu Research:
- 📖 [React Native Networking (Fetch/Axios)](https://reactnative.dev/docs/network)
- 📖 [Baeldung - Spring Boot CORS Configuration](https://www.baeldung.com/spring-cors)

---

## 📂 Danh Sách File Dự Kiến Tạo Mới / Sửa Đổi

### 1. Component: `finora-user`
#### [MODIFY] [pom.xml](file:///c:/Users/PC/Desktop/Data/%C4%90%E1%BB%93%20%C3%81n/finora-platform/finora-user/pom.xml) (Bổ sung OAuth2 Resource Server, Keycloak Admin Client, Spring Kafka, Redis)
#### [NEW] `src/main/java/com/finora/user/config/SecurityConfig.java` (Thêm DualBearerTokenResolver & @EnableMethodSecurity)
#### [NEW] `src/main/java/com/finora/user/security/DualBearerTokenResolver.java` (Đọc Token từ Header Authorization trước, fallback Cookie — hỗ trợ mobile + web)
#### [NEW] `src/main/java/com/finora/user/security/KeycloakJwtAuthenticationConverter.java` (Convert Client Roles/Permissions)
#### [NEW] `src/main/java/com/finora/user/util/PiiMasker.java` (Utility che dấu Email, CCCD, OTP, Full Name khi ghi Log)
#### [NEW] `src/main/java/com/finora/user/util/CryptoConverter.java` (JPA Converter mã hóa AES-256-GCM & HMAC-SHA256 Hash)
#### [NEW] `src/main/java/com/finora/user/controller/AuthController.java` (Login/Refresh/Logout/Register — dual-mode: Cookie cho web, JSON body cho mobile)
#### [NEW] `src/main/java/com/finora/user/service/KeycloakAdminService.java`
#### [NEW] `src/main/java/com/finora/user/service/UserEventProducer.java` (Phát Kafka events gửi Mail)
#### [NEW] `src/main/java/com/finora/user/controller/UserProfileController.java` (Nhận dữ liệu CCCD NFC + form nhập tay)
#### [NEW] `src/main/resources/db/migration/V1__init_user_schema.sql`

### 2. Component: `finora-notification`
#### [MODIFY] [pom.xml](file:///c:/Users/PC/Desktop/Data/%C4%90%E1%BB%93%20%C3%81n/finora-platform/finora-notification/pom.xml) (Bổ sung Spring Mail)
#### [NEW] `src/main/java/com/finora/notification/listener/UserEventListener.java`
#### [NEW] `src/main/java/com/finora/notification/service/EmailService.java`

### 3. Component: `finora-mobile` (MỚI — React Native)
#### [NEW] `finora-mobile/` (Khởi tạo project React Native CLI)
#### [NEW] `src/services/cccd-nfc-reader.ts` (Đọc chip CCCD qua NFC: BAC/PACE auth, parse DG1 + DG13)
#### [NEW] `src/screens/CccdNfcScanScreen.tsx` (Màn hình quét: nhập CAN → hướng dẫn chạm → form xác nhận)
#### [NEW] `src/services/auth-storage.ts` (Lưu/đọc/xóa accessToken & refreshToken trong Secure Storage via `react-native-keychain`)
#### [NEW] `src/services/api-client.ts` (Axios client + interceptor: tự gắn `Authorization: Bearer` header từ Secure Storage, mutex refresh khi 401)
#### [NEW] `package.json` (Dependencies: `react-native-nfc-manager`, `react-native-keychain`, `axios`, ...)

### ~~3. Component: `finora-ai`~~ (Không thay đổi cho luồng CCCD)
> `finora-ai` không tham gia luồng đọc CCCD NFC. Các file OCR (`ocr_service.py`, `cccd_parser.py`, `ocr_router.py`) và dependency (`paddleocr`, `paddlepaddle`) **không cần tạo**.

---

## 🧪 Verification Plan

### Kiểm thử Tự động (Automated Tests)
- Run Unit & Integration Test trong `finora-user`:
  ```powershell
  mvn -pl finora-user clean test
  ```
- Run Jest/Detox test trong `finora-mobile`:
  ```powershell
  cd finora-mobile
  npx jest
  ```

### Kiểm thử Thủ công (Manual Verification)
1. **Kiểm thử Mã hóa & Hashing DB:**
   - Đăng ký hoặc cập nhật hồ sơ với Số CCCD và SĐT $\rightarrow$ Kiểm tra trong PostgreSQL của `finora-user`: Cột `id_number_hash` và `phone_hash` phải là chuỗi HMAC-SHA256 (64 ký tự), cột `id_number_encrypted` phải là chuỗi AES-256 mã hóa. Không được có chuỗi thô.
2. **Kiểm thử Brute-Force & Log Masking:**
   - Thử nhập sai mật khẩu 5 lần liên tiếp $\rightarrow$ Keycloak / Redis khóa 15 phút.
   - Log Console hiển thị Email `th***@gmail.com`, CCCD `079*******12`, OTP `******`.
3. **Kiểm thử Security Token (Dual-Mode):**
   - **Web:** Login → Response Cookie `HttpOnly` `access_token` (5m) & `refresh_token` (7d). `localStorage` trống. Response body không chứa token.
   - **Mobile:** Login với header `X-Client-Type: mobile` → Response body chứa `accessToken` + `refreshToken`. Không có `Set-Cookie` header. Token lưu trong Secure Storage (kiểm tra qua debug bridge: Keychain/Keystore có entry `finora_tokens`).
   - **Concurrent Refresh:** Mở 3 tab/screen cùng lúc → đợi token hết hạn → cả 3 gửi request → chỉ 1 lần gọi `/refresh` (kiểm tra server log), 3 request đều thành công.
   - **Refresh Token Rotation:** Sau refresh → dùng refresh token cũ gọi `/refresh` lần nữa → Keycloak reject + revoke session.
4. **Kiểm thử Phân quyền Chức năng (Method Security):**
   - User `BORROWER` gọi API Admin $\rightarrow$ Nhận `403 Forbidden`.
5. **Kiểm thử CCCD NFC:**
   - Mở `finora-mobile` trên điện thoại có NFC → nhập CAN → chạm CCCD → dữ liệu hiển thị trên form xác nhận → bấm Xác nhận → kiểm tra DB có hash + mã hóa.
6. **Kiểm thử Fallback nhập tay:**
   - Mở `finora-web` trên máy tính (không có NFC) → nhập tay thông tin CCCD → Xác nhận → kiểm tra DB.
7. **Kiểm thử NFC thất bại:**
   - Rút thẻ CCCD giữa chừng đang quét → app hiển thị lỗi rõ ràng, cho phép thử lại.
   - Nhập sai CAN → app báo "Mã truy cập không đúng".
