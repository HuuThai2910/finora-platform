# EKYC-002 — Đọc CCCD tất định, liveness + so khớp mặt, lưu bằng chứng, điều khoản đồng ý

- **Trạng thái:** READY_FOR_REVIEW (16/10/2026) — code bốn phần xong, test tự động + E2E cục bộ xanh; còn chạy thử trên điện thoại (Expo Go), build image Docker có Tesseract, hiệu chỉnh ngưỡng (mục 10.8)
- **Owner:** Hải (User, AI, Mobile, Web). Thái đã giao Hải toàn quyền phần User/Common/Gateway.
- **Nguồn thiết kế đã chốt:** `C:\Users\PC\Desktop\Data\Đồ Án\DE-XUAT-EKYC-CCCD-LIVENESS.md` (đọc mục 2, 3, 5, Phụ lục A). File này là **hợp đồng triển khai**: tên field, mã lỗi, endpoint ở đây là chuẩn. Có mâu thuẫn với file đề xuất thì theo file này.
- **Legal gate:** `LEGAL-DATA-01` (docs/LEGAL-COMPLIANCE.md). Ảnh thẻ căn cước + dữ liệu sinh trắc học là dữ liệu nhạy cảm theo NĐ 356/2025 Điều 4 khoản 1 điểm i, đ; lưu ngoài lãnh thổ là chuyển dữ liệu xuyên biên giới (Luật 91/2025 Điều 20). Đây là **giới hạn demo**, không phải tuân thủ production.

## 0. Phạm vi

**Trong phạm vi**
- `finora-ai`: pipeline đọc thẻ tất định (`/document`), liveness + so mặt (`/liveness`), header nội bộ, thu hẹp Gemini, model YuNet/SFace, test, script hiệu chỉnh/đánh giá.
- `finora-user`: điều khoản + bản ghi đồng ý, luồng quét CCCD mới, thử thách + chấm liveness, xác nhận có điều kiện, lưu bằng chứng (S3 API — SeaweedFS khi dev local, Amazon S3 khi deploy), duyệt tay cho admin, migration V6, test.
- `finora-common`: thêm mã `EkycResultCode`.
- `finora-gateway`: chặn `/api/v1/ai/ekyc/**` từ bên ngoài.
- `docker/`: SeaweedFS + seaweedfs-init cho local (profile `ekyc`); `Dockerfile.ai` cài Tesseract + tải model mặt.
- `finora-mobile` (Expo Go only): màn điều khoản, chụp CCCD (sửa mờ, quét QR sống, nguồn chụp thay thế được), liveness quay video, màn kết quả có nhãn nguồn, Quyền riêng tư eKYC.
- `finora-web`: trang Duyệt eKYC cho admin.

**Ngoài phạm vi:** NFC/C06, tạo bucket S3 thật, deploy Azure, chương báo cáo, thu dữ liệu tình nguyện viên, git commit/push.

## 1. Luồng nghiệp vụ (người dùng thấy gì)

1. Hồ sơ › "Xác minh danh tính" → **Điều khoản**. Đã đồng ý đúng phiên bản hiện hành thì bỏ qua màn này.
2. **Chụp mặt trước → mặt sau.** App quét QR sống khi ngắm. Gửi cả hai ảnh.
3. Server trả một trong: `DRAFT_READY` (sang liveness), `RETAKE` (chụp lại đúng mặt kèm lý do), hoặc từ chối (CMND, hết hạn, dưới 18, số đã dùng…).
4. **Liveness:** app xin thử thách, quay video 8–10 giây bằng camera trước theo lời nhắc, gửi lên. Kết quả `LIVENESS_PASSED` / `LIVENESS_REVIEW` (đi tiếp) / `RETAKE` / `LIVENESS_FAILED` / `FACE_MISMATCH` (thử lại, còn N lần). Trượt 3 lần trong 24 giờ → hồ sơ vào `MANUAL_REVIEW`.
5. **Soát và xác nhận:** từng trường có nhãn nguồn. Chỉ trường "AI đọc" sửa được. Xác nhận:
   - giấy tờ `AUTO_OK` + liveness `PASS` → `VERIFIED`;
   - còn lại → `MANUAL_REVIEW` (admin duyệt trên web).
6. **Admin** xem ảnh thẻ, video, khung hình, điểm số → Duyệt (`VERIFIED`) hoặc Từ chối (`FAILED`, người dùng làm lại được).
7. **Rút lại đồng ý** (Cài đặt › Quyền riêng tư eKYC): xoá ảnh/video, hồ sơ về chưa xác minh.

## 2. Hợp đồng `finora-ai` (chỉ gọi nội bộ)

### 2.0 Chung
- Mọi route `/api/v1/ai/ekyc/*` cần access token service account của `finora-user` (`finora-user-client`, client role `ai:ekyc:ocr`; W3A D34/D35, quyền gắn ở `main.py`). Thiếu/sai token `401 AI_UNAUTHENTICATED`, thiếu quyền `403 AI_FORBIDDEN`. Bản đầu dùng header `X-Internal-Token`; đã bỏ khi gộp `main` (mục 10.9).
- **Xoá** `/api/v1/ai/ekyc/ocr` cùng schema/test cũ.
- Handler là `def` (không `async def`) để FastAPI chạy trong threadpool, không chặn `/credit`, `/health`.
- Một phân tích nặng (document hoặc liveness) tại một thời điểm: `threading.Semaphore(1)` không chờ; bận → `503`, header `Retry-After: 3`, body `{"detail":"BUSY"}`.
- Không log base64/ảnh/nội dung trường PII; chỉ log mã quyết định, cờ, thời gian.
- Ngưỡng nằm trong `app/ml/ekyc/thresholds.py`, đọc env, kèm `THRESHOLDS_VERSION` trả về trong response.

### 2.1 `POST /api/v1/ai/ekyc/document` (JSON)

Request:
```json
{
  "front_image_base64": "JPEG/PNG base64, ≤ 6_000_000 ký tự",
  "back_image_base64": "…",
  "qr_text": "chuỗi QR client đã đọc | null",
  "qr_side": "FRONT | BACK | null",
  "front_source": "IN_APP_CAMERA | SYSTEM_CAMERA | GALLERY",
  "back_source": "IN_APP_CAMERA | SYSTEM_CAMERA | GALLERY",
  "retake_count": 0
}
```
Base64 sai / không phải ảnh (magic bytes JPEG/PNG) / quá lớn → `422`.

Response `200`:
```json
{
  "decision": "AUTO_OK | NEEDS_REVIEW | RETAKE",
  "card_type": "CCCD_CHIP_2021 | CAN_CUOC_2024 | CCCD_CHIP_UNKNOWN | CCCD_BARCODE | CMND | UNKNOWN",
  "reasons": ["QR_MRZ_AGREE", "…"],
  "warnings": ["ID_PROVINCE_UNKNOWN", "…"],
  "retake": [{"side": "FRONT | BACK", "reason": "BLUR | GLARE | CARD_CUT | TOO_DARK | WRONG_SIDE | MRZ_UNREADABLE | QR_UNREADABLE | PORTRAIT_UNUSABLE | SOURCES_DISAGREE | NO_DATA"}],
  "fields": {
    "id_number":                {"value": "001095012345", "sources": ["QR", "MRZ"]},
    "full_name":                {"value": "NGUYỄN VĂN A", "sources": ["QR"]},
    "date_of_birth":            {"value": "15/03/1995", "sources": ["QR", "MRZ"]},
    "gender":                   {"value": "Nam", "sources": ["QR", "MRZ"]},
    "address":                  {"value": "…", "sources": ["QR"]},
    "place_of_origin":          {"value": null, "sources": []},
    "birth_registration_place": {"value": null, "sources": []},
    "issue_date":               {"value": "01/07/2021", "sources": ["QR"]},
    "expiry_date":              {"value": "15/03/2035", "sources": ["MRZ"], "no_expiry": false}
  },
  "checks": {
    "qr":  {"found": true, "by": "CLIENT | SERVER | null", "side": "FRONT | BACK | null", "valid": true},
    "mrz": {"found": true, "valid": true, "engine": "TESSERACT | GEMINI | null",
            "doc_number_ok": true, "dob_ok": true, "expiry_ok": true, "composite_ok": true, "id12_ok": true},
    "id_rules_ok": true,
    "sources_agree": true,
    "front_back_linked": true
  },
  "quality": {
    "front": {"card_found": true, "sharpness": 152.3, "glare_ratio": 0.004, "brightness": 0.55, "ok": true},
    "back":  {"card_found": true, "sharpness": 140.1, "glare_ratio": 0.01, "brightness": 0.51, "ok": true}
  },
  "portrait": {"found": true, "ied_px": 64.0, "sharpness": 63.1, "embedding_b64": "base64 của 128 float32 little-endian, đã chuẩn hoá L2"},
  "ai_used": false,
  "thresholds_version": "doc-2026-10-placeholder"
}
```
- `sources` chỉ gồm: `QR`, `MRZ`, `OCR` (Tesseract đọc chữ in), `AI` (Gemini).
- Ngày luôn `dd/mm/yyyy`. Giới tính luôn `Nam`/`Nữ`. Tên giữ dấu (từ QR/AI) hoặc IN HOA không dấu (chỉ có MRZ).
- `portrait.embedding_b64` chỉ có khi `found=true`.

**Pipeline** (chi tiết thuật toán: đề xuất mục 2.5; tóm tắt bắt buộc):
1. Giải mã, kiểm magic bytes, mã hoá lại.
2. Tìm thẻ + nắn phẳng (contour lớn nhất ~4 đỉnh, diện tích ≥ 20% ảnh, `warpPerspective` về tỉ lệ 85,6:54). Không tìm được → dùng nguyên ảnh (mobile đã cắt theo khung), `card_found=false`.
3. Cổng chất lượng trên thẻ đã nắn đưa về rộng 1000 px: Laplacian variance, tỉ lệ pixel V>250, độ sáng trung bình.
4. QR: parse `qr_text` client gửi; song song server tự giải bằng **zxing-cpp** trên ảnh gốc, từng góc phần tư và bản phóng to ×2 của mỗi mặt. Định dạng 7 trường `ID12|CMND9 (có thể rỗng)|Họ tên|ddmmyyyy|Nam/Nữ|Địa chỉ|ddmmyyyy (ngày cấp)`; trường thứ 8+ bỏ qua. Ưu tiên giải `bytes` thô bằng UTF-8; nếu chữ lỗi mã (mojibake/ký tự CJK) thì thử sửa (`latin-1`/`cp1252`/`big5`/`shift_jis` → `utf-8`); sửa không được thì bỏ tên từ QR.
5. MRZ mặt sau: cắt ~35% dưới của thẻ đã nắn, phóng to, nhị phân, **Tesseract** (`pytesseract`, whitelist `A-Z0-9<`, thử vài `--psm`/ngưỡng), ghép 3 dòng × 30. Parse TD1 + sửa ký tự nhầm (O/0, I/1, B/8, S/5, Z/2, G/6) ở trường số tới khi **mọi check digit đúng** (7-3-1 mod 10; `<`=0; A–Z=10–35). Bố cục VN: `L1[5:14]` = 9 số cuối ID + `L1[14]` check; `L1[15:27]` = ID 12 số, `L1[27:29]='<<'`, `L1[29]` = check của `L1[15:29]` (tín hiệu mềm); `L2[0:6]` sinh + check; `L2[7]` M/F; `L2[8:14]` hết hạn + check; `L2[15:18]='VNM'`; `L2[29]` composite của `L1[5:30]+L2[0:7]+L2[8:15]+L2[18:29]`; `L3` = `HO<<TEN<DEM`. Không có Tesseract hoặc đọc hỏng → nếu Gemini bật thì gửi **riêng crop MRZ** cho Gemini, chỉ nhận khi đủ check digit.
6. Luật số định danh: 3 số đầu thuộc 63 mã tỉnh (cờ mềm `ID_PROVINCE_UNKNOWN`); số thứ 4 → thế kỷ + giới tính (0/1: 1900–1999 nam/nữ; 2/3: 2000–2099; 4/5: 2100…); số 5–6 = 2 số cuối năm sinh. Lệch với ngày sinh/giới tính → `ID_STRUCTURE_MISMATCH` (chỉ đẩy về NEEDS_REVIEW, không RETAKE/REJECT).
7. Gắn hai mặt:
   - thẻ 2021 (QR mặt trước + MRZ mặt sau): QR khớp MRZ là gắn;
   - thẻ 2024 hoặc không có QR mặt trước: tìm chuỗi 12 số đã biết trong kết quả Tesseract chỉ đọc chữ số của 65% bên phải mặt trước (cho lệch 1 ký tự). Không thấy và Gemini bật → hỏi Gemini số định danh in ở mặt trước (crop đã che chân dung).
8. AI chỉ điền chỗ trống (Gemini, crop đã che 38% bên trái mặt trước, không bao giờ gửi khuôn mặt):
   - tên có dấu khi QR hỏng (chỉ nhận nếu bỏ dấu khớp tên MRZ);
   - địa chỉ khi QR hỏng;
   - `place_of_origin`/`birth_registration_place` chỉ khi `EKYC_OPTIONAL_FIELDS=true` (mặc định **false**);
   - thẻ mã vạch/không QR không MRZ: đọc toàn bộ → tối đa `NEEDS_REVIEW`;
   - nhận diện CMND (tiêu đề "CHỨNG MINH NHÂN DÂN" hoặc số 9 chữ số) → `card_type=CMND`.
   `EKYC_GEMINI_MODE=off|fallback` (mặc định `fallback` nếu có key). Gemini lỗi/hết quota → bỏ qua, không làm hỏng cả request.
9. Chân dung: YuNet trên vùng trái ~45% mặt trước (thẻ nắn rộng 1400 px). Đúng **1** mặt (score ≥ `EKYC_PORTRAIT_MIN_SCORE`, mặc định 0.6), IED ≥ `EKYC_PORTRAIT_MIN_IED` (40 px), độ nét vùng mặt ≥ ngưỡng → SFace embedding 128 chiều.
10. Quyết định (`fusion.py`):
   - **RETAKE** (khi `retake_count < EKYC_MAX_RETAKES`, mặc định 2): chân dung không dùng được (`PORTRAIT_UNUSABLE` — luôn RETAKE bất kể số lần, vì không so mặt được); mặt trước mờ/loá/tối mà QR mặt trước không đọc được; mặt sau mờ/loá và MRZ không hợp lệ; MRZ tìm thấy nhưng sai check digit (`MRZ_UNREADABLE`); QR và MRZ mâu thuẫn về số/ngày sinh/giới tính (`SOURCES_DISAGREE`); không có số định danh từ nguồn nào (`NO_DATA`).
   - Khi `retake_count >= EKYC_MAX_RETAKES` và lỗi không phải chân dung: mâu thuẫn → `NEEDS_REVIEW` (reason `SOURCES_DISAGREE_PERSISTENT`, giá trị lấy theo nguồn tất định); không có số định danh → vẫn `RETAKE NO_DATA`.
   - **AUTO_OK**: QR hợp lệ **và** MRZ đủ check digit, khớp số + ngày sinh + giới tính; luật số khớp; hai mặt gắn được; chất lượng đạt; không mặt nào từ `GALLERY`. Với `EKYC_ALLOW_SINGLE_DETERMINISTIC=true`: thay bằng **một** nguồn tất định + một nguồn đọc độc lập (OCR/AI) khớp số, ngày sinh, giới tính + luật số khớp + hai mặt gắn được.
   - Còn lại → **NEEDS_REVIEW** (chỉ AI; ảnh thư viện; thẻ mã vạch; cờ mềm; không gắn được hai mặt; không đọc được hạn thẻ).
   - Quy tắc nghiệp vụ (tuổi, hạn thẻ, CMND, trùng số) **không** quyết ở AI — để finora-user.

### 2.2 `POST /api/v1/ai/ekyc/liveness` (multipart/form-data)

Parts:
- `spec` (text, JSON):
```json
{
  "steps": [{"action": "FRONTAL", "hold_ms": 1500}, {"action": "TURN_LEFT", "hold_ms": 1300}, "…"],
  "portrait_embedding_b64": "…",
  "capture_mode": "VIDEO | STILLS",
  "platform": "ios | android | web | null",
  "frame_times_ms": [0, 1000, 2000]
}
```
- `video` (file mp4/mov, ≤ 10 MB) khi `VIDEO`;
- `frames` (nhiều file JPEG, ≤ 20 file, mỗi file ≤ 1,5 MB) khi `STILLS`, cùng thứ tự với `frame_times_ms`.

Response `200`:
```json
{
  "decision": "PASS | REVIEW | FAIL | RETAKE",
  "reasons": ["ACTIONS_MATCH", "FACE_MATCH_HIGH"],
  "video": {"duration_ms": 7600, "fps": 29.7, "pts_monotonic": true, "max_gap_ms": 70, "codec": "h264"},
  "faces": {"sampled_frames": 76, "single_face_ratio": 0.97, "multi_face_frames": 0, "min_ied_px": 92.0, "track_continuous": true},
  "actions": {"expected": ["TURN_LEFT", "LOOK_UP", "TURN_RIGHT"], "detected": ["TURN_LEFT", "LOOK_UP", "TURN_RIGHT"], "extra_events": 0, "intervals_ok": true},
  "identity": {"min_pair_cos": 0.71, "frames_checked": 5},
  "face_match": {"score": 0.52, "zone": "HIGH | GRAY | LOW"},
  "quality": {"best_sharpness": 143.0, "brightness": 0.52},
  "evidence_frames": [{"label": "FRONTAL", "jpeg_b64": "…"}, {"label": "TURN_LEFT", "jpeg_b64": "…"}],
  "thresholds_version": "liveness-2026-10-placeholder"
}
```
- `evidence_frames`: tối đa 4 khung (chính diện đầu + khung đỉnh mỗi hành động), rộng 480 px, JPEG q80.
- Mã lý do: `VIDEO_UNREADABLE`, `VIDEO_TOO_SHORT`, `VIDEO_GAPS`, `NO_FACE`, `FACE_TOO_SMALL`, `TOO_DARK`, `BLURRY`, `MULTIPLE_FACES`, `TRACK_BROKEN`, `ACTIONS_MISMATCH`, `ACTIONS_MISSING`, `ACTIONS_EXTRA`, `INTERVALS_MISMATCH`, `IDENTITY_INCONSISTENT`, `FACE_MISMATCH`, `FACE_MATCH_GRAY`, `STILLS_MODE`, `SOFT_ENCODER_TAG`, và mã tích cực `ACTIONS_MATCH`, `FACE_MATCH_HIGH`.
- Ánh xạ quyết định:
  - `RETAKE`: `VIDEO_UNREADABLE`, `VIDEO_TOO_SHORT`, `NO_FACE`, `FACE_TOO_SMALL`, `TOO_DARK`, `BLURRY` (khi chúng làm không phân tích được);
  - `FAIL`: `MULTIPLE_FACES`, `ACTIONS_*`, `INTERVALS_MISMATCH`, `IDENTITY_INCONSISTENT`, `FACE_MISMATCH`;
  - `REVIEW`: `FACE_MATCH_GRAY`, `STILLS_MODE`, `VIDEO_GAPS`, `TRACK_BROKEN`, `SOFT_ENCODER_TAG`;
  - `PASS`: còn lại và zone `HIGH`.
- Thuật toán (đề xuất mục 3.4): đọc video bằng `cv2.VideoCapture` (file tạm), lấy mẫu ~10 khung/giây; YuNet trên khung thu về rộng 480 (IED đổi về toạ độ gốc); `r = (x_mũi − x_giữa_hai_mắt)/|x_mắt_phải − x_mắt_trái|`, `p = (y_mũi − y_mắt)/(y_miệng − y_mắt)`; mức nền từ đoạn FRONTAL đầu; sự kiện = chính diện → lệch ≥ θ → về dưới θ/2, thời điểm sự kiện = đỉnh. Dấu: `TURN_LEFT` khi `r − r0 > θ_r` với video **không lật gương**; `EKYC_LIVENESS_YAW_INVERT=true` đảo. `LOOK_UP` khi `p − p0 < −θ_p`, `LOOK_DOWN` khi `> θ_p`. Khớp **chặt**: chuỗi phát hiện phải **bằng đúng** chuỗi kỳ vọng (không thừa/thiếu); khoảng cách giữa các đỉnh liên tiếp khớp khoảng cách kỳ vọng (tâm mỗi bước) với sai số `max(EKYC_INTERVAL_TOL_S=0.7, 0.35×khoảng kỳ vọng)`. Cùng người: embedding SFace ở 4–6 khung chính diện rải theo thời gian, cos nhỏ nhất ≥ `T_same`. So mặt: khung chính diện nét nhất (đã qua kiểm cùng người) với embedding chân dung.
- Ngưỡng tạm (env): `θ_r=0.25`, `θ_p=0.15`, `T_same=0.5`, `T_high=0.50`, `T_low=0.25`, IED mặt selfie tối thiểu 40 px, tỉ lệ khung đúng 1 mặt ≥ 0.9, độ sáng tối thiểu 0.25, fps tối thiểu 10, khoảng hở PTS mềm 250 ms, thời lượng trong [tổng hold − 1 s, tổng hold + 3 s].
- `STILLS`: bỏ kiểm video; quyết định tối đa `REVIEW` (reason `STILLS_MODE`).
- Model thiếu → `503 {"detail":"FACE_MODELS_MISSING"}`.

### 2.3 Model mặt
- YuNet `face_detection_yunet_2023mar.onnx` (MIT) + SFace `face_recognition_sface_2021dec.onnx` (Apache-2.0) từ opencv_zoo, đặt ở `finora-ai/models/face/`, **không commit** (`.gitignore`), tải bằng `finora-ai/scripts/download_face_models.py` (ghim SHA-256). `Dockerfile.ai` chạy script này lúc build.

## 3. Hợp đồng `finora-user` (qua gateway)

Endpoint người dùng bọc `BaseResponse<T>` như hiện tại; quyền `user:cccd:scan`. Lỗi đầu vào/trạng thái dùng `BusinessException(status, code, message)` → `ApiErrorResponse {code, message, details, traceId}`.

### 3.1 Điều khoản
- `GET /api/v1/users/profile/ekyc/consent` → `BaseResponse<EkycConsentResponse>`:
```json
{ "version": "ekyc-2026-10", "title": "Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)",
  "body": "Markdown đã điền chỗ trống",
  "consentLabel": "Tôi đã đọc và đồng ý với Điều khoản xử lý dữ liệu khi xác minh danh tính.",
  "purposes": ["ID_DOCUMENT", "FACE_BIOMETRIC", "STORAGE_ABROAD", "OWN_DOCUMENT"],
  "textSha256": "hex 64", "accepted": false, "acceptedAt": null }
```
- `POST /api/v1/users/profile/ekyc/consent` body `{"version":"…","textSha256":"…","platform":"ios|android|web","appVersion":"…"}` → như trên với `accepted=true`. Version/hash không khớp bản hiện hành → `409 CONSENT_VERSION_STALE`.
- `DELETE /api/v1/users/profile/ekyc/consent` → rút lại: ghi `withdrawn_at`; xoá mọi object bằng chứng của user (prefix) và đánh dấu `ekyc_evidence.deleted_at`; xoá `draft_encrypted` của các attempt; hồ sơ `VERIFIED`/`MANUAL_REVIEW` → `PENDING`, `documentVerified=false`, `livenessVerified=false`, `faceMatchScore=null`; ghi claim Keycloak sau commit. **Giữ** `id_number_hash`/`id_number_encrypted` (chống một CCCD nhiều tài khoản). Trả `accepted=false`.
- Đồng ý "còn hiệu lực" ⇔ bản ghi mới nhất chưa rút có `version` = hiện hành **và** `text_sha256` = hash nội dung hiện hành.
- Nội dung: resource `finora-user/src/main/resources/ekyc/consent-ekyc-2026-10.md` = Phụ lục A của file đề xuất (mục "Nội dung hiển thị", bỏ ký tự `> ` đầu dòng, bỏ dòng nút bấm), thay chỗ trống bằng `{{CONTACT}}`, `{{RETENTION}}`, `{{STORAGE_LOCATION}}`, `{{SERVER_LOCATION}}`, `{{EFFECTIVE_DATE}}`; đoạn Gemini bọc `<!-- IF:GEMINI -->…<!-- ENDIF:GEMINI -->`. Cấu hình `finora.ekyc.consent.*`:
  - `version` = `ekyc-2026-10`;
  - `contact-email` (`EKYC_CONSENT_CONTACT_EMAIL`, mặc định rỗng → chữ "mục Hỗ trợ trong ứng dụng FINORA");
  - `storage-location` (`EKYC_STORAGE_LOCATION_TEXT`, mặc định "hệ thống lưu trữ thử nghiệm của FINORA");
  - `server-location` (`EKYC_SERVER_LOCATION_TEXT`, mặc định "Malaysia");
  - `effective-date` (`EKYC_CONSENT_EFFECTIVE_DATE`, mặc định "16/10/2026");
  - `mentions-gemini` (`EKYC_CONSENT_MENTIONS_GEMINI`, mặc định `true`);
  - `{{RETENTION}}` = "`<EKYC_EVIDENCE_RETENTION_DAYS>` ngày".
  `textSha256` = SHA-256 của body sau khi điền (UTF-8).

### 3.2 Quét CCCD — `POST /api/v1/users/profile/ekyc-verify` (giữ đường cũ)
Request:
```json
{ "cccdFrontBase64": "…", "cccdBackBase64": "…",
  "frontSource": "IN_APP_CAMERA | SYSTEM_CAMERA | GALLERY", "backSource": "…",
  "qr": {"text": "…", "side": "FRONT | BACK", "method": "LIVE_SCAN | STILL_SCAN | SCANNER"},
  "consentVersion": "ekyc-2026-10" }
```
`qr` có thể `null`; `frontSource`/`backSource` mặc định `IN_APP_CAMERA`; mỗi base64 ≤ 6_000_000 ký tự.

Thứ tự xử lý: hồ sơ đã `VERIFIED` → trả `VERIFIED`; đang `MANUAL_REVIEW` → `REVIEW_PENDING`; chưa có đồng ý còn hiệu lực → `CONSENT_REQUIRED`; storage tắt → `STORAGE_UNAVAILABLE`; rate limit → `RATE_LIMITED`; gọi AI `/document` (kèm `retake_count` từ Redis `ekyc_retake_count:<uid>` TTL 1 giờ) → lỗi/timeout/503 → `AI_UNAVAILABLE` **và trả lại suất rate limit**; `RETAKE` → tăng `retake_count`, trả `RETAKE` kèm danh sách mặt + lý do + thông điệp; luật nghiệp vụ: `card_type=CMND` hoặc `UNKNOWN` không có số → `CARD_UNSUPPORTED`; hết hạn (`expiry_date < hôm nay` theo `Asia/Ho_Chi_Minh`) → `CARD_EXPIRED`; dưới 18 tuổi → `UNDERAGE`; số khác hồ sơ đã có → `ID_MISMATCH`; số thuộc tài khoản khác → `ID_TAKEN`; đạt → huỷ attempt `IN_PROGRESS` cũ (nếu có), tạo `ekyc_attempts` mới (`IN_PROGRESS`), đẩy hai ảnh lên storage (tag `stage=pending`), lưu bản nháp Redis (TTL 15 phút) gồm `attemptId`, trường + nguồn, `cardType`, `documentDecision`, `reasons`, `warnings`, `portraitEmbeddingB64`; reset `retake_count`; trả `DRAFT_READY` với `nextStep=LIVENESS`. Upload lỗi → attempt `ABANDONED`, `STORAGE_UNAVAILABLE`.

### 3.3 `EkycResultResponse` (dùng chung cho verify / liveness / confirm)
```json
{
  "status": "PENDING | VERIFIED | FAILED | MANUAL_REVIEW",
  "resultCode": "…xem 3.6…",
  "message": "thông điệp tiếng Việt cho người dùng",
  "ocrWarnings": ["FULL_NAME_MISMATCH", "ID_PROVINCE_UNKNOWN"],
  "cardType": "CCCD_CHIP_2021 | … | null",
  "reviewRequired": false,
  "retake": [{"side": "FRONT", "reason": "BLUR", "message": "Ảnh mặt trước bị mờ…"}],
  "draft": {
    "idNumber":               {"value": "…", "sources": ["QR", "MRZ"], "editable": false},
    "fullName":               {"value": "…", "sources": ["QR"], "editable": false},
    "dateOfBirth":            {"…": "…"},
    "gender":                 {"…": "…"},
    "address":                {"…": "…"},
    "placeOfOrigin":          null,
    "birthRegistrationPlace": null,
    "issueDate":              {"…": "…"},
    "expiryDate":             {"…": "…"}
  },
  "nextStep": "LIVENESS | CONFIRM | NONE",
  "attemptsLeft": null,
  "liveness": {"result": "PASSED | REVIEW | FAILED | RETAKE", "faceMatchScore": 0.52,
               "reasons": [{"code": "TOO_DARK", "message": "…"}]}
}
```
- `editable=true` chỉ khi `sources == ["AI"]` và trường thuộc {`fullName`, `address`, `placeOfOrigin`, `birthRegistrationPlace`}.
- Trường không thu (value null và sources rỗng, ví dụ quê quán khi tắt optional) trả `null` thay vì object.

### 3.4 Liveness
- `POST /api/v1/users/profile/ekyc/liveness/challenge` (không body) → `BaseResponse<LivenessChallengeResponse>`:
```json
{ "sessionId": "uuid", "expiresAt": "2026-10-16T10:01:30Z", "maxDurationSec": 13,
  "steps": [{"action": "FRONTAL", "holdMs": 1500}, {"action": "TURN_LEFT", "holdMs": 1300}, {"action": "FRONTAL", "holdMs": 1100},
            {"action": "LOOK_UP", "holdMs": 1600}, {"action": "FRONTAL", "holdMs": 900}, {"action": "TURN_RIGHT", "holdMs": 1400},
            {"action": "FRONTAL", "holdMs": 1000}],
  "video": {"quality": "4:3", "bitrate": 1500000, "mute": true} }
```
  - 3 hành động khác nhau trong {`TURN_LEFT`,`TURN_RIGHT`,`LOOK_UP`,`LOOK_DOWN`} bằng `SecureRandom`; `holdMs` ngẫu nhiên 900–1700, FRONTAL đầu cố định 1500; xen FRONTAL giữa các hành động và ở cuối.
  - `maxDurationSec` = ceil(tổng hold / 1000) + 3.
  - Redis `ekyc_challenge:<uid>` JSON `{sessionId, steps, attemptId, issuedAt}`, TTL 90 giây, ghi đè phiên cũ.
  - Lỗi (ném `BusinessException`): không có bản nháp → `409 DRAFT_EXPIRED`; chưa đồng ý → `409 CONSENT_REQUIRED`; đang chờ duyệt → `409 REVIEW_PENDING`; gọi dồn → `429 RATE_LIMITED`.
- `POST /api/v1/users/profile/ekyc/liveness` multipart: `sessionId` (text), `captureMode` (`VIDEO|STILLS`), `platform` (text), `video` (file) **hoặc** `frames` (file, nhiều, ≤ 20) + `frameTimesMs` (text JSON). → `BaseResponse<EkycResultResponse>`. Xử lý theo thứ tự:
  1. rate limit;
  2. `GETDEL` thử thách, sai/thiếu `sessionId` hoặc quá hạn → `CHALLENGE_EXPIRED`;
  3. thiếu bản nháp → `DRAFT_EXPIRED`;
  4. kiểm kích thước/kiểu (video ≤ 10 MB);
  5. SHA-256 video (hoặc toàn bộ khung); đã có trong Redis `ekyc_media_sha:<sha>` (TTL 30 ngày) → `LIVENESS_FAILED` reason `MEDIA_REPLAYED`;
  6. gọi AI `/liveness` (RestClient multipart, header nội bộ, timeout đọc 60 s) → lỗi → `AI_UNAVAILABLE`;
  7. đẩy video (hoặc khung) + `evidence_frames` lên storage (tag `pending`), ghi `ekyc_evidence`;
  8. cập nhật attempt (`liveness_result`, `liveness_reasons`, `face_match_score`, `thresholds_version`) và bản nháp (`livenessResult`, `faceMatchScore`);
  9. ánh xạ: `PASS` → `LIVENESS_PASSED` (`nextStep=CONFIRM`); `REVIEW` → `LIVENESS_REVIEW` (`nextStep=CONFIRM`, `reviewRequired=true`); `RETAKE` → `RETAKE` (không tính lần trượt, `nextStep=LIVENESS`); `FAIL` → tăng `ekyc_liveness_fail:<uid>` (TTL 24 giờ) → `FACE_MISMATCH` nếu lý do có `FACE_MISMATCH`, ngược lại `LIVENESS_FAILED`; `attemptsLeft = 3 − số lần trượt`; đủ 3 → attempt + hồ sơ `MANUAL_REVIEW`, đổi tag `final`, trả `REVIEW_PENDING`, `nextStep=NONE`.
  - Giới hạn multipart: `spring.servlet.multipart.max-file-size=12MB`, `max-request-size=40MB`; vượt → `413 PAYLOAD_TOO_LARGE`.

### 3.5 Xác nhận — `POST /api/v1/users/profile/ekyc-confirm`
Body tuỳ chọn `{"edits": {"fullName": "…", "address": "…", "placeOfOrigin": "…", "birthRegistrationPlace": "…"}}` (chỉ nhận trường `editable`; trường khác → `400 EDIT_NOT_ALLOWED`; tối đa 500 ký tự; trường được sửa đổi nguồn thành `USER`).
- không có bản nháp → `DRAFT_EXPIRED`;
- liveness chưa `PASS`/`REVIEW` → `LIVENESS_REQUIRED`;
- kiểm trùng số lần cuối → `ID_TAKEN`;
- `documentDecision=AUTO_OK` và liveness `PASS` → ghi hồ sơ (như luồng cũ + `id_card_type`, `id_issue_date`, `id_expiry_date`, `birth_registration_place`), `markEkycVerified(faceMatchScore)` (`documentVerified=true`, `livenessVerified=true`), attempt `VERIFIED`, tạo CIC mapping task, đổi tag object sang `final`, ghi `result.json`, claim Keycloak sau commit → `VERIFIED`;
- còn lại → attempt `MANUAL_REVIEW` (lưu `draft_encrypted` + `submitted_at`), hồ sơ `MANUAL_REVIEW` (**chưa** ghi trường định danh), đổi tag `final`, ghi `result.json`, claim sau commit → `REVIEW_PENDING`;
- xoá bản nháp, thử thách, bộ đếm sau commit.

### 3.6 Mã kết quả (`finora-common` `EkycResultCode`) và thông điệp
Giữ mã cũ (`DRAFT_READY`, `DRAFT_EXPIRED`, `VERIFIED`, `OCR_FAILED`, `ID_MISMATCH`, `ID_TAKEN`, `RATE_LIMITED`, `AI_UNAVAILABLE`), thêm:

| Mã | Khi nào | Thông điệp mặc định |
|---|---|---|
| `RETAKE` | Cần chụp/quay lại | theo từng lý do (bảng dưới) |
| `CARD_EXPIRED` | Thẻ hết hạn | "Thẻ căn cước đã hết hạn. Vui lòng dùng thẻ còn hiệu lực." |
| `CARD_UNSUPPORTED` | CMND hoặc không nhận diện được thẻ | "FINORA chỉ nhận thẻ Căn cước công dân gắn chip hoặc thẻ Căn cước." |
| `UNDERAGE` | Dưới 18 tuổi | "Bạn cần đủ 18 tuổi để sử dụng dịch vụ vay và đầu tư." |
| `CONSENT_REQUIRED` | Chưa đồng ý điều khoản hiện hành | "Vui lòng đọc và đồng ý điều khoản trước khi xác minh." |
| `STORAGE_UNAVAILABLE` | Kho bằng chứng tắt/lỗi | "Hệ thống lưu trữ đang bận, vui lòng thử lại sau." |
| `REVIEW_PENDING` | Đã gửi/đang chờ duyệt tay | "Hồ sơ của bạn đang được duyệt. FINORA sẽ thông báo khi có kết quả." |
| `LIVENESS_REQUIRED` | Xác nhận khi chưa qua liveness | "Bạn cần hoàn tất bước kiểm tra khuôn mặt trước khi xác nhận." |
| `CHALLENGE_EXPIRED` | Phiên quay hết hạn/sai | "Phiên quay đã hết hạn, vui lòng thử lại." |
| `LIVENESS_PASSED` | Liveness đạt | "Kiểm tra khuôn mặt thành công." |
| `LIVENESS_REVIEW` | Liveness vùng xám | "Đã ghi nhận. Hồ sơ sẽ được duyệt thêm sau khi bạn xác nhận." |
| `LIVENESS_FAILED` | Không làm đúng động tác/không nhất quán | "Chưa làm đúng các động tác. Hãy làm chậm và rõ từng động tác." |
| `FACE_MISMATCH` | Mặt không khớp ảnh thẻ | "Khuôn mặt chưa khớp với ảnh trên thẻ căn cước." |

Lý do chụp lại CCCD (`retake[].reason`): `BLUR` "Ảnh bị mờ. Đưa điện thoại ra xa khoảng 20 cm, giữ yên 1 giây rồi chụp lại."; `GLARE` "Ảnh bị loá. Nghiêng thẻ một chút hoặc tránh đèn chiếu thẳng."; `CARD_CUT` "Chưa thấy đủ 4 góc thẻ. Đặt thẻ gọn trong khung."; `WRONG_SIDE` "Có vẻ bạn chụp nhầm mặt thẻ."; `MRZ_UNREADABLE` "Chưa đọc được 3 dòng ký tự ở cuối mặt sau. Chụp đủ sáng, rõ nét."; `QR_UNREADABLE` "Chưa đọc được mã QR. Đưa mã QR vào giữa khung và giữ yên."; `PORTRAIT_UNUSABLE` "Ảnh chân dung trên thẻ bị mờ hoặc loá, chưa dùng được để so khuôn mặt. Hãy chụp lại mặt trước."; `TOO_DARK` "Ảnh quá tối. Ra chỗ sáng hơn."; `SOURCES_DISAGREE` "Thông tin đọc được ở hai mặt thẻ chưa khớp. Hãy chụp lại cả hai mặt thật rõ."; `NO_DATA` "Chưa đọc được thông tin trên thẻ. Hãy chụp lại rõ hơn.".

Lý do liveness (`liveness.reasons[].code` → message): `TOO_DARK` "Ảnh quá tối, hãy ra chỗ sáng hơn."; `NO_FACE` "Không thấy khuôn mặt. Giữ mặt trong khung oval."; `FACE_TOO_SMALL` "Mặt ở quá xa. Giữ điện thoại cách mặt khoảng một cánh tay."; `BLURRY` "Video bị mờ. Giữ máy yên và quay đầu chậm hơn."; `VIDEO_UNREADABLE`/`VIDEO_TOO_SHORT` "Video không đọc được hoặc quá ngắn, vui lòng quay lại."; `MULTIPLE_FACES` "Chỉ một người được xuất hiện trong khung hình."; `ACTIONS_MISMATCH`/`ACTIONS_MISSING`/`ACTIONS_EXTRA` "Chưa làm đúng các động tác theo hướng dẫn."; `INTERVALS_MISMATCH` "Động tác chưa khớp nhịp hướng dẫn trên màn hình."; `IDENTITY_INCONSISTENT` "Khuôn mặt trong video không nhất quán."; `FACE_MISMATCH` "Khuôn mặt chưa khớp với ảnh trên thẻ."; `FACE_MATCH_GRAY` "Hệ thống chưa chắc chắn khuôn mặt khớp ảnh trên thẻ, hồ sơ sẽ được duyệt thêm."; `MEDIA_REPLAYED` "Video này đã được dùng trước đó.". Backend là nguồn thông điệp chính; mobile chỉ giữ bản dự phòng.

### 3.7 Admin (route `/api/v1/admin/users/**` → user-admin-service; trả DTO trực tiếp như `AdminUserController`)
- `GET /api/v1/admin/users/ekyc-attempts?status=MANUAL_REVIEW&page=0&size=20` (`user:admin:read_all`, size ≤ 100) → `PageResponse<EkycAttemptSummaryResponse>` (`content, page, size, totalElements, totalPages, last`):
  `{attemptId (UUID), userProfileId, email, status, cardType, documentDecision, livenessResult, faceMatchScore, faceMatchZone (HIGH|GRAY|LOW|null), reasons: [..], createdAt, submittedAt}`; chi tiết có thêm `livenessReasons: [mã]`.
- `GET /api/v1/admin/users/ekyc-attempts/{attemptId}` → `EkycAttemptDetailResponse` = summary + `draft` (giải mã, cùng khuôn 3.3) + `livenessReasons` + `consentVersion` + `evidence: [{evidenceId, kind, label, contentType, sizeBytes, deleted}]` + `reviewNote`, `reviewedAt`, `reviewerKeycloakId`, `reviewerEmail`, `reviewerName` (hai trường sau tra từ `user_profiles` theo `keycloak_user_id` lúc đọc; admin không có hồ sơ FINORA thì `null`).
- `GET /api/v1/admin/users/ekyc-attempts/{attemptId}/evidence/{evidenceId}` → stream byte, `Content-Type` theo bản ghi, `Cache-Control: no-store`, `Content-Disposition: inline`; ghi `ekyc_evidence_access_log`; object đã xoá → `410 EVIDENCE_DELETED`.
- `POST …/{attemptId}/approve` body `{"note":"…"}` (`user:admin:lock`) → chỉ từ `MANUAL_REVIEW`; kiểm trùng số; ghi hồ sơ từ `draft_encrypted` → `VERIFIED` (`livenessVerified` = liveness_result∈{PASS,REVIEW}), CIC task, claim sau commit; attempt `APPROVED`. Trả detail.
- `POST …/{attemptId}/reject` body `{"note":"…"}` (`user:admin:lock`, note bắt buộc) → attempt `REJECTED`, hồ sơ `FAILED`, claim sau commit. Trả detail.
- Hai admin bấm cùng lúc: `@Version` trên attempt → bên thua nhận `409 ATTEMPT_ALREADY_DECIDED`.

## 4. Dữ liệu — Flyway `V6__ekyc_consents_attempts_evidence.sql`

```sql
ALTER TABLE user_profiles
    ADD COLUMN id_card_type             VARCHAR(30),
    ADD COLUMN id_issue_date            DATE,
    ADD COLUMN id_expiry_date           DATE,
    ADD COLUMN birth_registration_place VARCHAR(500);

CREATE TABLE ekyc_consents (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_profile_id  BIGINT       NOT NULL,
    version          VARCHAR(40)  NOT NULL,
    purposes         VARCHAR(200) NOT NULL,           -- mã mục đích, phân tách dấu phẩy
    text_sha256      VARCHAR(64)  NOT NULL,
    platform         VARCHAR(20),
    app_version      VARCHAR(40),
    accepted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    withdrawn_at     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ekyc_consents_profile FOREIGN KEY (user_profile_id) REFERENCES user_profiles (id)
);
CREATE INDEX idx_ekyc_consents_user_profile_id_accepted_at ON ekyc_consents (user_profile_id, accepted_at DESC);

CREATE TABLE ekyc_attempts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id             UUID         NOT NULL,
    user_profile_id       BIGINT       NOT NULL,
    consent_id            BIGINT,
    status                VARCHAR(20)  NOT NULL,
    card_type             VARCHAR(30),
    document_decision     VARCHAR(20)  NOT NULL,
    document_reasons      TEXT,                       -- JSON mảng mã, không PII
    liveness_result       VARCHAR(10),
    liveness_reasons      TEXT,
    face_match_score      DOUBLE PRECISION,
    thresholds_version    VARCHAR(80),
    draft_encrypted       TEXT,                       -- JSON bản nháp, AES-GCM qua CryptoConverter
    submitted_at          TIMESTAMP WITH TIME ZONE,
    decided_at            TIMESTAMP WITH TIME ZONE,
    reviewer_keycloak_id  UUID,
    review_note           VARCHAR(1000),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ekyc_attempts_public_id UNIQUE (public_id),
    CONSTRAINT fk_ekyc_attempts_profile FOREIGN KEY (user_profile_id) REFERENCES user_profiles (id),
    CONSTRAINT fk_ekyc_attempts_consent FOREIGN KEY (consent_id) REFERENCES ekyc_consents (id),
    CONSTRAINT ck_ekyc_attempts_status CHECK (status IN ('IN_PROGRESS','VERIFIED','MANUAL_REVIEW','APPROVED','REJECTED','ABANDONED')),
    CONSTRAINT ck_ekyc_attempts_document_decision CHECK (document_decision IN ('AUTO_OK','NEEDS_REVIEW')),
    CONSTRAINT ck_ekyc_attempts_liveness_result CHECK (liveness_result IS NULL OR liveness_result IN ('PASS','REVIEW','FAIL'))
);
-- Trang duyệt: lọc theo trạng thái, sắp theo thời điểm gửi.
CREATE INDEX idx_ekyc_attempts_status_submitted_at ON ekyc_attempts (status, submitted_at);
-- Huỷ lượt cũ khi quét lại, quét dọn lượt bỏ dở.
CREATE INDEX idx_ekyc_attempts_user_profile_id_created_at ON ekyc_attempts (user_profile_id, created_at DESC);

CREATE TABLE ekyc_evidence (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id    BIGINT       NOT NULL,
    kind          VARCHAR(20)  NOT NULL,
    label         VARCHAR(30),
    object_key    VARCHAR(300) NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    content_type  VARCHAR(60)  NOT NULL,
    stage         VARCHAR(10)  NOT NULL,              -- pending | final, khớp tag object
    deleted_at    TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ekyc_evidence_object_key UNIQUE (object_key),
    CONSTRAINT fk_ekyc_evidence_attempt FOREIGN KEY (attempt_id) REFERENCES ekyc_attempts (id),
    CONSTRAINT ck_ekyc_evidence_kind CHECK (kind IN ('CCCD_FRONT','CCCD_BACK','LIVENESS_VIDEO','LIVENESS_FRAME','RESULT')),
    CONSTRAINT ck_ekyc_evidence_stage CHECK (stage IN ('pending','final'))
);
CREATE INDEX idx_ekyc_evidence_attempt_id ON ekyc_evidence (attempt_id);
-- Quét dọn theo hạn lưu.
CREATE INDEX idx_ekyc_evidence_stage_created_at ON ekyc_evidence (stage, created_at) WHERE deleted_at IS NULL;

CREATE TABLE ekyc_evidence_access_log (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id         BIGINT NOT NULL,
    evidence_id        BIGINT,
    admin_keycloak_id  UUID   NOT NULL,
    accessed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ekyc_access_attempt FOREIGN KEY (attempt_id) REFERENCES ekyc_attempts (id),
    CONSTRAINT fk_ekyc_access_evidence FOREIGN KEY (evidence_id) REFERENCES ekyc_evidence (id)
);
CREATE INDEX idx_ekyc_evidence_access_log_attempt_id ON ekyc_evidence_access_log (attempt_id, accessed_at);
```

ERD: `user_profiles 1 — 0..N ekyc_consents` (mỗi lần đồng ý/đồng ý lại một bản ghi); `user_profiles 1 — 0..N ekyc_attempts` (mỗi lần quét đạt một lượt); `ekyc_consents 1 — 0..N ekyc_attempts` (lượt ghi lại đồng ý đang hiệu lực lúc tạo); `ekyc_attempts 1 — 0..N ekyc_evidence`; `ekyc_attempts 1 — 0..N ekyc_evidence_access_log`; `ekyc_evidence 1 — 0..N ekyc_evidence_access_log`.

Vòng đời attempt: `IN_PROGRESS → VERIFIED | MANUAL_REVIEW | ABANDONED`; `MANUAL_REVIEW → APPROVED | REJECTED`. Quét lại khi còn `IN_PROGRESS` → lượt cũ `ABANDONED`, xoá object của nó.

**Quét dọn** (`@Scheduled`, mỗi giờ, có cờ bật/tắt): attempt `IN_PROGRESS` quá 24 giờ → `ABANDONED` + xoá object `pending`; evidence `final` quá `EKYC_EVIDENCE_RETENTION_DAYS` (30) → xoá object + `deleted_at`. Không phụ thuộc lifecycle của bucket (lifecycle chỉ là lớp phòng thủ thêm).

## 5. Lưu bằng chứng
- Interface `EkycEvidenceStorage`: `put(key, bytes, contentType, stage)`, `open(key)` (stream), `setStage(key, stage)` (PutObjectTagging `stage=…`), `delete(key)`, `deletePrefix(prefix)`, `isEnabled()`. Bản AWS SDK v2 (`software.amazon.awssdk:s3`, BOM) + bản tắt (`isEnabled=false`).
- Gắn tag là **best-effort** (16/10, khi đổi sang SeaweedFS): PutObject kèm tag mà kho từ chối tag (400/403/501) thì ghi lại không tag và log WARN; `setStage` lỗi chỉ log WARN. Cột `ekyc_evidence.stage` + worker quét dọn là nguồn chuẩn cho hạn lưu. Lỗi PutObject/GetObject/DeleteObject thật vẫn là `STORAGE_UNAVAILABLE`.
- Cấu hình `finora.ekyc.storage.*`: `enabled` (`EKYC_EVIDENCE_ENABLED`, mặc định `false`), `endpoint` (`EKYC_S3_ENDPOINT`), `region` (`EKYC_S3_REGION`, `us-east-1`), `bucket` (`EKYC_S3_BUCKET`, `finora-ekyc`), `path-style` (`EKYC_S3_PATH_STYLE`, `false`), `access-key`/`secret-key` (`EKYC_S3_ACCESS_KEY`/`EKYC_S3_SECRET_KEY`), `env` (`EKYC_S3_ENV`, `dev`), `retention-days` (`EKYC_EVIDENCE_RETENTION_DAYS`, 30).
- Key: `ekyc/{env}/{userProfileId}/{attemptPublicId}/{file}` — file: `cccd-front.jpg`, `cccd-back.jpg`, `liveness-{n}.mp4|mov`, `liveness-frame-{label}-{n}.jpg`, `still-{n}.jpg`, `result.json`. Không PII trong key/metadata.
- **SeaweedFS local** (thay MinIO ngày 16/10: repo MinIO đã archive, image `minio/minio`, `minio/mc` và binary dl.min.io không còn tải được): `docker/docker-compose.yml` (và `docker-compose.app.yml`) profile `ekyc` thêm `seaweedfs` (`chrislusf/seaweedfs:4.48`, Apache-2.0; `server -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json -s3.encryptVolumeData -master.volumeSizeLimitMB=64`; S3 chỉ ở `127.0.0.1:9100` — **không** dùng 9000 vì trùng cic-service; volume `seaweedfs-data`) + `seaweedfs-init` (`amazon/aws-cli:2.38.1`, tài khoản admin): tạo bucket (bỏ qua nếu đã có), `put-bucket-lifecycle-configuration` lọc theo tag (`pending` 1 ngày, `final` `EKYC_EVIDENCE_RETENTION_DAYS`) — SeaweedFS nhận nhưng chưa chắc thực thi, chỉ là lớp phòng thủ thêm. `s3.json` sinh lúc khởi động từ env: `ekyc-admin` (`SEAWEEDFS_ADMIN_ACCESS_KEY/SECRET_KEY`, Admin) và `finora-user` (`EKYC_S3_ACCESS_KEY/SECRET_KEY`, chỉ `Read/Write/List/Tagging:finora-ekyc`). Mã hoá khi lưu bằng `-s3.encryptVolumeData` (bỏ `MINIO_KMS_SECRET_KEY`); ẩn danh bị từ chối vì đã khai báo identities. finora-user từ IntelliJ: `EKYC_S3_ENDPOINT=http://localhost:9100`, trong compose `http://seaweedfs:8333`; `EKYC_S3_PATH_STYLE=true`, `EKYC_S3_REGION=us-east-1`. Biến trong `docker/.env.example`.
- **Không** thêm kho S3 local vào compose Azure (`C:\Users\PC\Desktop\Data\Đồ Án\deploy\docker-compose.azure.yml`); ở đó chỉ thêm `EKYC_EVIDENCE_ENABLED=false` cho user (User → AI dùng service account, mục 10.9). Không deploy.

## 6. Gateway
- Servlet filter (`OncePerRequestFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE)`) trả `404 {"code":"RESOURCE_NOT_FOUND","message":"Không tìm thấy tài nguyên"}` cho mọi path bắt đầu `/api/v1/ai/ekyc/` (không phân biệt hoa thường, đã chuẩn hoá `//`, `/./`). `/api/v1/ai/credit/**` vẫn đi qua. Có unit test.

## 7. Mobile (Expo Go, không native module mới ngoài bộ Expo Go hỗ trợ)
- Gói thêm: `expo-image-picker`, `expo-brightness` (cài bằng `npx expo install`).
- Màn mới: `EkycConsentScreen`, `EkycLivenessScreen`, `EkycPrivacyScreen` (Cài đặt › Quyền riêng tư eKYC); sửa `EkycCaptureScreen`, `EkycResultScreen`, `AccountScreen` (thêm dòng cài đặt), `AccountInfoScreen` (điểm vào eKYC đi qua `EkycConsent`).
- `src/features/ekyc/capture/`: `CardCaptureSource` + `inAppCameraSource` (mặc định) + `systemCameraSource` (dự phòng, `launchCameraAsync`, Android `allowsEditing` + `aspect [8,5]`, kèm màn/hộp hướng dẫn chụp), chọn bằng `EXPO_PUBLIC_EKYC_CAPTURE_SOURCE=in_app|system`. Không có nút cho người dùng đổi nguồn.
- Chống mờ: bỏ `autofocus="on"`; `takePictureAsync({quality: 1})`; nén **một lần** 0.9; `CCCD_WIDTH=1600`; preset zoom; nhắc khoảng cách ~20 cm; chụp 2–3 tấm giữ JPEG lớn nhất.
- QR: `barcodeScannerSettings={{barcodeTypes:['qr']}}` + `onBarcodeScanned` (nhận chuỗi khớp `^\d{12}\|`), remount `CameraView` (đổi `key`) sau ~4 giây chưa có kết quả; sau khi chụp mà chưa có QR → `CameraView.scanFromURLAsync` trên ảnh gốc (Android thử thêm 4 góc phần tư).
- Server trả `RETAKE` → quay lại đúng mặt, hiện thông điệp. Cùng một mặt bị `RETAKE` 2 lần liên tiếp → hiện thêm nút "Chọn ảnh có sẵn" (`launchImageLibraryAsync`, `source=GALLERY`).
- Liveness: `CameraView` `facing="front"`, `mode="video"` ngay từ lúc mount, `mute`, `videoQuality="4:3"`, `videoBitrate` theo challenge; iOS `recordAsync({codec:'avc1'})`; `maxDuration`, `maxFileSize: 8_000_000`; lời nhắc theo lịch `steps`; tăng độ sáng màn hình trong lúc quay (`expo-brightness`), viền trắng quanh oval; upload `FormData` (không base64). `recordAsync` lỗi → xin thử thách mới và chạy chế độ STILLS (chụp ~1 khung/giây, `frames` + `frameTimesMs`). Không bao giờ chọn video từ thư viện.
- `src/lib/api.ts`: thêm hàm gửi `FormData` có token + refresh 401 + `timeoutMs`, **không** đặt `Content-Type` JSON.
- Màn kết quả: mỗi trường có nhãn nguồn (`QR` "Từ mã QR", `MRZ` "Từ dòng mã máy đọc", `OCR` "Từ chữ in trên thẻ", `AI` "AI đọc — hãy kiểm tra", `USER` "Bạn đã sửa"); ô sửa cho trường `editable`; ghi chú khi `reviewRequired`; xác nhận gửi `edits`.
- Điều khoản: render Markdown đơn giản (tiêu đề `###`, dòng `**…**`, gạch đầu dòng `- `, đoạn văn, inline `**đậm**`/`*nghiêng*`), ô tick chỉ bật khi cuộn hết, nút "Để sau"/"Bắt đầu xác minh".
- Mock miền `ekyc` cập nhật theo hợp đồng mới.
- Kiểm: `npx tsc --noEmit` sạch.

## 8. Web admin
- Trang "Duyệt eKYC" (list theo trạng thái, mặc định `MANUAL_REVIEW`; chi tiết: trường + nguồn, lý do, chỉ số liveness, ảnh/video tải bằng fetch có xác thực → object URL, nút Duyệt/Từ chối có ghi chú). Theo pattern API/feature hiện có của finora-web. Kiểm: `npm run build`.

## 9. Kiểm thử bắt buộc
- `finora-ai`: `pytest` toàn bộ xanh trong venv khớp `requirements.txt` (numpy 1.26.3). Test: check digit/parse/sửa MRZ; parse QR + sửa mojibake; luật số định danh; ma trận quyết định; chất lượng + nắn thẻ trên ảnh tổng hợp; giải QR end-to-end (zxing ghi QR → dán lên thẻ giả → giải); Tesseract MRZ (skip nếu máy không có Tesseract); state machine + khớp chặt liveness với chuỗi landmark tổng hợp; router (token nội bộ, 422, 503 BUSY); `/liveness` với video tổng hợp không có mặt → `RETAKE NO_FACE`.
- `finora-user`/`finora-common`/`finora-gateway`: `mvn -q -pl finora-common,finora-user,finora-gateway -am test` xanh; migration V6 chạy thử trên Postgres local (DB tạm) nếu có.
- Mobile `npx tsc --noEmit`; Web `npm run build`.

## 10. Bản đồ code thực tế

Phần Java (finora-user, finora-common, finora-gateway) — 16/10/2026. Package gốc `com.finora.user`.
Nguyên tắc chung: **không transaction nào bao lời gọi AI hay kho S3**. Service điều phối gọi mạng trước/sau,
còn mọi thay đổi DB nằm trong khối `TransactionTemplate` ngắn; các hàm ghi dùng chung ở `EkycAttemptService`
đều `@Transactional(propagation = MANDATORY)`. Redis và claim Keycloak (`EkycClaimPublisher`) chỉ chạm sau commit.

### 10.1 Endpoint → controller → service → dữ liệu/tích hợp

| Endpoint / trigger | Controller | Service | Transaction và lời gọi ngoài |
|---|---|---|---|
| `GET/POST/DELETE /api/v1/users/profile/ekyc/consent` | `EkycConsentController` | `EkycConsentService` + `EkycConsentDocument` (render + SHA-256 lúc khởi động) | POST: 1 insert `ekyc_consents` (đã có đồng ý hiệu lực thì trả bản cũ). DELETE: tx1 ghi `withdrawn_at`, lượt `IN_PROGRESS`/`MANUAL_REVIEW` → `ABANDONED`, xoá mọi `draft_encrypted`, hồ sơ `resetEkycAfterConsentWithdrawal()` (giữ hash + bản mã hoá số định danh) → sau commit: `EkycSessionCleanup`, claim, `deletePrefix(ekyc/{env}/{profileId}/)` → tx2 `deleted_at` (xoá hỏng thì để worker) |
| `POST /api/v1/users/profile/ekyc-verify` | `UserProfileController.verifyEkyc` | `EkycVerificationService.verify` | kiểm rẻ → giải mã ảnh (magic JPEG/PNG) → rate limit → `AiEkycClient.analyzeDocument` (Feign, `X-Internal-Token`) → luật nghiệp vụ (`Asia/Ho_Chi_Minh`) → tx `EkycAttemptService.start` (bỏ lượt cũ, mở lượt) → xoá object lượt cũ → `EkycEvidenceService.storeCardImages` → tx `recordEvidence` (lỗi: tx `abandon`) → `EkycDraftStore.save` |
| `POST /api/v1/users/profile/ekyc/liveness/challenge` | `EkycLivenessController.createChallenge` | `EkycLivenessService.createChallenge` + `LivenessChallengeGenerator` | chỉ Redis `ekyc_challenge:<uid>` (90 s, ghi đè) |
| `POST /api/v1/users/profile/ekyc/liveness` (multipart) | `EkycLivenessController.submitLiveness` (đọc file thành `LivenessUpload`) | `EkycLivenessService.submit` | rate limit → `GETDEL` thử thách → bản nháp/lượt → kiểm file → `registerMedia` (SET NX) → `AiLivenessClient.analyze` (RestClient multipart, timeout đọc 60 s) → `storeLiveness` → tx `recordLiveness` → Redis draft. Lần trượt thứ 3: `markFinal` + `result.json` (best-effort) → tx `submitForReview` → cleanup + claim |
| `POST /api/v1/users/profile/ekyc-confirm` | `UserProfileController.confirmEkyc` | `EkycVerificationService.confirm` | `markFinal` (best-effort) + `storeResult` (**bắt buộc**, lỗi → `STORAGE_UNAVAILABLE`) **trước** tx → tx đọc lại hồ sơ/lượt rồi `completeVerified` (định danh + hồ sơ/lượt `VERIFIED` + evidence `final` + `result.json` + task CIC) hoặc `submitForReview` → sau commit: cleanup + claim. Unique `id_number_hash` vi phạm → `ID_TAKEN`; optimistic lock → đọc lại trạng thái thật |
| `GET /api/v1/admin/users/ekyc-attempts` | `AdminEkycAttemptController.listAttempts` | `EkycAdminReviewService.list` | 1 câu JPQL join email (`findSummariesByStatus` dùng index `(status, submitted_at)` / `findAllSummaries`) + count |
| `GET …/ekyc-attempts/{attemptId}` | `getAttempt` | `detail` | tx: đọc + ghi `ekyc_evidence_access_log` (`evidence_id = null`) |
| `GET …/{attemptId}/evidence/{evidenceId}` | `downloadEvidence` | `openEvidence` | tx: kiểm thuộc lượt, `deleted_at` → 410, ghi access log; sau đó `GetObject` stream (`no-store`, `inline`, `nosniff`) |
| `POST …/{attemptId}/approve` / `reject` | `approve` / `reject` | `approve` / `reject` | tx + `saveAndFlush` (`@Version`) → thua race `409 ATTEMPT_ALREADY_DECIDED`; sau commit: claim `VERIFIED`/`FAILED` |
| `@Scheduled` mỗi giờ (`EKYC_SWEEPER_ENABLED`) | — | `EkycEvidenceSweeper.sweep` | tx theo lô 100: `IN_PROGRESS` > 24 h → `ABANDONED`; xoá S3 ngoài tx rồi đánh `deleted_at` cho: object của lượt `ABANDONED`, `final` quá hạn lưu, object tạo trước lần rút đồng ý; xoá `draft_encrypted` lượt `APPROVED/REJECTED` quá hạn. Chỉ trong `ekyc/{env}/` |
| Gateway mọi request | `InternalAiRouteBlockingFilter` (`@Order(HIGHEST_PRECEDENCE)`) | — | 404 JSON cho `/api/v1/ai/ekyc[/**]` sau khi giải mã %, bỏ `;param`, gộp `//`, `/./`, `/../`, không phân biệt hoa thường; `/api/v1/ai/credit/**` đi tiếp |

### 10.2 Thành phần và dữ liệu

- Domain: `EkycConsent`, `EkycAttempt` (`@Version`, chuyển trạng thái whitelist), `EkycEvidence` (`stage` qua `EkycEvidenceStage.DbConverter` ghi `pending|final`), `EkycEvidenceAccessLog`; value object `EkycIdentityDraft` + `EkycDraftField` + `EkycDraftFieldName` (quy tắc `editable`); `LivenessAction`, `LivenessStep`. `UserProfile` thêm 4 cột V6 + `markEkycVerified(score, livenessVerified, at)`, `markEkycManualReview()`, `markEkycFailed(at)`, `resetEkycAfterConsentWithdrawal()`; đã bỏ `markEkycDocumentVerified`.
- Tích hợp: `client/AiEkycClient` (Feign `/document`, DTO camelCase + `@JsonNaming(SnakeCase)`), `client/AiLivenessClient` (RestClient multipart `/liveness`), `integration/storage/*` (`EkycEvidenceStorage`, `S3EkycEvidenceStorage` AWS SDK 2.30.20 — `endpointOverride` + `forcePathStyle` + checksum `WHEN_REQUIRED` khi có endpoint, retry STANDARD 3 lần, tag best-effort; `DisabledEkycEvidenceStorage`; `EkycStorageKeys`), chọn bean ở `config/EkycStorageConfiguration`.
- Cấu hình: `AiServiceProperties` (`finora.ai.*`), `EkycConsentProperties` (`finora.ekyc.consent.*`), `EkycStorageProperties` (`finora.ekyc.storage.*`); multipart 12 MB/40 MB, `server.tomcat.max-swallow-size=50MB`; `UploadExceptionHandler` → `413 PAYLOAD_TOO_LARGE`.
- Redis: `ekyc_verify_rate` (10 s), `ekyc_liveness_rate` (10 s), `ekyc_challenge_rate` (3 s), `ekyc_retake_count` (1 h), `ekyc_draft` (15 phút, AES-GCM), `ekyc_challenge` (90 s), `ekyc_liveness_fail` (24 h từ lần đầu), `ekyc_media_sha` (30 ngày, SET NX).
- Kiểm chứng DB: V1–V6 qua Flyway + Hibernate `ddl-auto=validate` + chạy thử mọi JPQL mới + round-trip ghi/đọc (CHECK `stage`, bản nháp mã hoá, optimistic lock) trên PostgreSQL 18.6 local (DB tạm đã xoá).

### 10.3 Sai khác so với dự kiến

1. Rút đồng ý đưa cả lượt `MANUAL_REVIEW` chưa duyệt về `ABANDONED` (bằng chứng và bản nháp đã bị xoá nên không còn gì để duyệt).
2. Thẻ "không thời hạn" (`no_expiry=true`, không có ngày): `draft.expiryDate.value = "Không thời hạn"`, hồ sơ lưu `id_expiry_date = null`.
3. Ảnh không phải JPEG/PNG hoặc base64 hỏng → `400 INVALID_IMAGE` trước rate limit. AI `/document` trả 400/422 → `OCR_FAILED` (không trả suất). Không có embedding chân dung dù AI không yêu cầu chụp lại → `RETAKE` (`FRONT`, `PORTRAIT_UNUSABLE`). Số 9 chữ số → `CARD_UNSUPPORTED`; số không đủ 12 chữ số ở thẻ khác → `OCR_FAILED`.
4. Liveness: file sai kiểu/thiếu → `400 INVALID_MEDIA`; quá 10 MB (video) hoặc 1,5 MB/ảnh → `413 PAYLOAD_TOO_LARGE`; AI trả 400/413/422 → `RETAKE` (`VIDEO_UNREADABLE`, không tính trượt); AI lỗi khác → `AI_UNAVAILABLE` và trả suất rate limit. `MEDIA_REPLAYED` tính một lần trượt và ghi `liveness_result=FAIL`. Quyết định AI lạ → xử lý như `RETAKE`.
5. Spec gửi AI có thêm `platform` (`ios|android|web|null`). `liveness_reasons` lưu thêm mã vùng so mặt (`FACE_MATCH_HIGH|FACE_MATCH_GRAY|FACE_MISMATCH`) suy từ `face_match.zone` khi AI không tự đưa — nguồn của `faceMatchZone`. `thresholds_version` = `<doc>,<liveness>`.
6. Lần trượt thứ 3: lưu `draft_encrypted` + `submitted_at` như đường xác nhận → admin duyệt được; `result.json` best-effort.
7. Admin: thiếu `status` → `MANUAL_REVIEW`, `ALL` → mọi trạng thái, giá trị lạ → `400 INVALID_STATUS`; `attemptId`/`evidenceId` sai định dạng → 404. Summary/detail có thêm `faceMatchZone`. Duyệt khi lượt mất bản nháp → `409 ATTEMPT_DRAFT_MISSING`; số đã thuộc tài khoản khác/khác hồ sơ → `409 ID_TAKEN|ID_MISMATCH`; từ chối thiếu ghi chú → `400 VALIDATION_FAILED`. Từ chối đặt `ekyc_completed_at` để thống kê `FAILED` có mốc.
8. Bản nháp Redis mã hoá AES-GCM bằng khoá PII; bản nháp lưu DB không có embedding khuôn mặt.
9. Worker quét dọn có thêm hai quy tắc dọn sót (lượt `ABANDONED`, object trước lần rút đồng ý) và xoá bản nháp đã quyết định quá hạn lưu.
10. Ảnh PNG lưu `cccd-front.png`/`cccd-back.png` (đuôi theo kiểu file thật).

### 10.4 Bằng chứng kiểm thử

- `mvn -q -pl finora-common,finora-user,finora-gateway -am test` (sau `clean`): xanh — finora-common 33, finora-user 403
  (gồm 200 lần lặp kiểm thử thử thách ngẫu nhiên), finora-gateway 22; 0 lỗi, 0 bỏ qua. Lưu ý: auto-build của VS Code Java
  (JDT) đang ghi class Lombok lỗi ("Flexible Constructor Bodies") vào `target/classes`, và compile tăng dần của Maven coi
  chúng là mới — luôn chạy `mvn clean …` trước khi test/install.
- `S3EkycEvidenceStorageIT` (bật bằng `EKYC_S3_IT_ENDPOINT`): chạy thật với SeaweedFS 4.48 local — put kèm tag, đọc tag, HeadObject content-type, stream, đổi tag `final`, xoá theo tiền tố, NoSuchKey.
- `docker compose config` cho `docker-compose.yml`, `docker-compose.app.yml`, `deploy/docker-compose.azure.yml`; `sh -n` cho script `seaweedfs`/`seaweedfs-init` và chạy thử bộ sinh `s3.json`.

### 10.5 Phần finora-ai (Python)

| Endpoint | Router | Service | Module xử lý |
|---|---|---|---|
| `POST /api/v1/ai/ekyc/document` | `app/api/ekyc_router.py` (`def`, quyền `ai:ekyc:ocr` gắn ở `main.py` (D35), semaphore 1 → `503 BUSY` + `Retry-After: 3`) | `app/services/ekyc/service.py` | `image_io` (magic bytes, giải mã) → `card_warp` (tìm thẻ, nắn phẳng) → `quality` (Laplacian, loá, sáng) → `qr_reader` (zxing-cpp, góc phần tư, ×2, sửa mojibake từ byte thô) → `mrz` + `tesseract_ocr` (TD1 VN, check digit 7-3-1, sửa ký tự nhầm) → `id_rules` (+ `data/province_codes.json`) → `front_id_ocr` (gắn hai mặt) → `gemini_extractor` (chỉ crop đã che chân dung, chế độ `EKYC_GEMINI_MODE`) → `face.extract_portrait` (YuNet + SFace) → `fusion.decide` |
| `POST /api/v1/ai/ekyc/liveness` | như trên (multipart: `spec`, `video` hoặc `frames`) | như trên | `liveness_video` (đọc video, lấy mẫu ~10 khung/giây, thử xoay 90/180/270° nếu gần như không thấy mặt, tỉ số r/p, sự kiện có trễ, khớp **chặt** chuỗi + khoảng cách, cùng người, so mặt theo vùng HIGH/GRAY/LOW, khung bằng chứng) |

- Ngưỡng: `app/ml/ekyc/thresholds.py`, phiên bản `doc-2026-10-placeholder` / `liveness-2026-10-placeholder`; ghi đè qua env thì phiên bản thêm `+env-<hash>`. Chiều quay đầu theo nền tảng: `EKYC_LIVENESS_YAW_INVERT_IOS` / `_ANDROID`, mặc định theo `EKYC_LIVENESS_YAW_INVERT`.
- Mã lý do giấy tờ (`fusion.py`): `QR_MRZ_AGREE`, `SINGLE_SOURCE`, `SINGLE_SOURCE_CONFIRMED`, `SOURCES_INCOMPLETE`, `AI_ONLY`, `QR_MISSING`, `MRZ_MISSING`, `ID_RULES_OK`, `ID_RULES_FAILED`, `FRONT_BACK_LINKED`, `NOT_LINKED`, `PORTRAIT_OK`, `QUALITY_OK`, `QUALITY_LOW`, `GALLERY_SOURCE`, `EXPIRY_UNREADABLE`, `NO_EXPIRY_UNVERIFIED`, `SOFT_FLAGS`, `SOURCES_DISAGREE`, `SOURCES_DISAGREE_PERSISTENT`, `CARD_CMND`, `CARD_BARCODE`, `CARD_UNKNOWN`. Cờ mềm (`warnings`): `ID_PROVINCE_UNKNOWN`, `ID_STRUCTURE_MISMATCH`, `FULL_NAME_MISMATCH`, `QR_CLIENT_MISMATCH`, `MRZ_ID12_CHECK_FAILED`, `EXPIRY_MILESTONE_MISMATCH`.
- Sai khác so với dự kiến: chỉ QR do server đọc từ ảnh (không phải chuỗi client gửi) mới gắn được hai mặt; QR–MRZ lệch quá số lần chụp lại thì lấy giá trị MRZ; CMND trả `card_type=CMND` + `NEEDS_REVIEW` (finora-user đổi thành `CARD_UNSUPPORTED`); MRZ hết hạn `<<<<<<` → `no_expiry=true`, `NO_EXPIRY_UNVERIFIED`; `ai_used=true` mỗi khi có gửi Gemini (cờ kiểm toán riêng tư); mặt sau thẻ 2024 không bao giờ gửi Gemini; byte không phải video đọc được → `200 RETAKE VIDEO_UNREADABLE`; mã chi tiết 422 dạng chuỗi (`IMAGE_*`, `SPEC_INVALID`, `VIDEO_*`, `FRAME_*`).
- Pin: `opencv-python-headless==4.11.0.86` (bản cuối nhận numpy 1.x), `zxing-cpp==3.1.1`, `pytesseract==0.3.13`, `pillow==12.3.0`, `python-multipart==0.0.32`. Model mặt tải bằng `scripts/download_face_models.py` (ghim commit + SHA-256), `models/face/*.onnx` không commit; `Dockerfile.ai` cài `tesseract-ocr` + `tesseract-ocr-eng` và tải model ở stage build.
- Windows: scikit-learn 1.4 mang `msvcp140.dll` cũ làm bộ ghi barcode của zxing-cpp sập tiến trình nếu sklearn được nạp trước → `main.py` và `tests/conftest.py` import `zxingcpp` trước. Linux không bị.
- Đo trên máy dev: `/document` 0,23–0,29 s (cặp ảnh 1600×1200), `/liveness` ~1 s (clip 9 s, 640×480), bộ nhớ ~160 MB lúc rảnh, đỉnh ~318 MB (Windows; container Linux chưa đo).
- Test: `tests/ekyc` 295 passed, 8 skipped (6 cần ảnh mặt thật, 2 cần Tesseract); đặt `EKYC_TEST_FACE_IMAGE` (ảnh mặt do AI sinh) → 301 passed, 2 skipped. Test ảnh chân dung mờ đã chỉnh độ mờ tỉ lệ với cỡ ảnh mặt (độ nét đo trên ảnh căn 112×112).

### 10.6 Mobile và web

- Mobile (`finora-mobile`, plan `plans/MOBILE-EKYC-002-cccd-liveness.md`): `src/features/ekyc/{capture,liveness,constants,mappers,hooks,components,screens}`; thêm `expo-image-picker ~57.0.20`, `expo-brightness ~57.0.2`; `src/lib/api.ts` có `authUploadWithToken` (FormData, không đặt Content-Type JSON). Biến thử: `EXPO_PUBLIC_EKYC_CAPTURE_SOURCE=in_app|system`, `EXPO_PUBLIC_EKYC_LIVENESS_CAPTURE=video|stills`, `EXPO_PUBLIC_MOCK_SCENARIOS=ekyc-retake|ekyc-reject|ekyc-review|ekyc-liveness-fail`. Sai khác: remount camera khi chưa đọc QR chỉ trên Android (tối đa 2 lần); preset zoom theo nền tảng (Android 0/0,2/0,3; iOS 0/0,1/0,18 — giá trị tạm); hồ sơ đọc `ekycStatus` từ `/users/me`.
- Web (`finora-web`, plan `plans/WEB-EKYC-002-duyet-ekyc.md`): `src/features/ekyc-review/**`, route `/customers/ekyc-review` và `/customers/ekyc-review/:attemptId`, menu Người dùng › Duyệt eKYC; ảnh/video tải bằng `apiFetchBlob` (cookie + refresh 401) thành object URL; ưu tiên `faceMatchZone` từ API, thiếu thì suy từ `livenessReasons`; nhãn tiếng Việt cho toàn bộ mã lý do của `fusion.py`.

### 10.7 Bằng chứng E2E cục bộ (16/10/2026)

Chạy thật cả backend trên máy dev, không Docker, không Keycloak, không Neon: finora-user (jar, DB tạm `finora_user_ekyc_e2e` trên PostgreSQL 18.6 local, Flyway V1–V6), Redis 8.10 bản chạy trực tiếp, SeaweedFS 4.48, JWKS giả để kiểm JWT thật, finora-ai (venv ghim, `EKYC_GEMINI_MODE=off`). Dữ liệu: thẻ mẫu tổng hợp ghi "MẪU THỬ" (QR 7 trường có dấu, MRZ hợp lệ check digit), chân dung là khuôn mặt do AI sinh (không phải người thật). Kết quả **0 lỗi**:

1. Chưa đồng ý → `CONSENT_REQUIRED`; hash cũ → `409 CONSENT_VERSION_STALE`; đồng ý → `accepted=true`.
2. Quét thẻ → `DRAFT_READY`, `cardType=CCCD_CHIP_2021`, số/tên có dấu/ngày sinh/giới tính/địa chỉ/ngày cấp từ QR do server tự đọc; `reviewRequired=true` (máy không có Tesseract nên thiếu MRZ). Hai ảnh lên SeaweedFS với tag `stage=pending`; lượt `IN_PROGRESS`.
3. Xác nhận trước liveness → `LIVENESS_REQUIRED`.
4. Liveness: video tĩnh của **người khác** → `FACE_MISMATCH` (điểm 0,20); gửi lại đúng video cũ → `MEDIA_REPLAYED`; lần trượt thứ 3 → `REVIEW_PENDING`, hồ sơ `MANUAL_REVIEW`, lượt lưu bản nháp mã hoá. Video tĩnh của **đúng người** → `LIVENESS_FAILED` (`ACTIONS_MISSING`) dù điểm so mặt 0,87 (HIGH) — đúng ý đồ chống ảnh tĩnh.
5. Admin: danh sách, chi tiết (bản nháp giải mã), tải từng bằng chứng (SHA-256 khớp DB, `Cache-Control: no-store`), ghi access log; người vay gọi API admin → 403; duyệt → hồ sơ `VERIFIED` đủ trường; duyệt lần 2 → `409 ATTEMPT_ALREADY_DECIDED`; object đổi tag `final`.
6. Rút đồng ý → object bị xoá khỏi SeaweedFS, evidence `deleted_at`, hồ sơ về `PENDING`; tải lại bằng chứng → `410 EVIDENCE_DELETED`.
7. Kiểm kiểu: JSON thật của mọi endpoint trên được gán vào type TypeScript của mobile (`EkycConsent`, `EkycResultResponse`, `LivenessChallenge`) và web (`EkycAttemptSummary`, `EkycAttemptDetail`), `tsc` xanh; mẫu sai cố ý thì `tsc` bắt được.

### 10.8 Việc còn lại trước khi ACCEPTED

- Chạy thử trên điện thoại thật qua Expo Go (Android + iPhone): chụp thẻ thật, quay liveness thật (luồng PASS chưa thể kiểm bằng video tổng hợp), xác định chiều quay đầu theo nền tảng.
- Build `docker/Dockerfile.ai` và đo MRZ bằng Tesseract trên ảnh thẻ thật (máy dev chưa có Tesseract; đường MRZ mới kiểm trên chuỗi).
- Thu dữ liệu tình nguyện viên, chạy `scripts/ekyc_calibrate.py` / `ekyc_eval_cccd.py`, thay các ngưỡng tạm.
- Deploy: `finora-user` cần secret của `finora-user-client` có client role `ai:ekyc:ocr` (mục 10.9); tạo bucket S3 rồi bật `EKYC_EVIDENCE_ENABLED`.

### 10.9 Gộp `main` ngày 2026-10-20

`main` (Wave 3, W3A) đã đặt xác thực cho mọi router của AI trước khi nhánh này gộp vào, nên bản gộp theo W3A:

- User gọi `/api/v1/ai/ekyc/document` và `/liveness` bằng access token service account `finora-user-client` (client role
  `ai:ekyc:ocr`, D34). `AiEkycClient` (Feign) dùng `ServiceAccountFeignConfig`; `AiLivenessClient` (RestClient) gắn
  `ClientCredentialsTokenProvider.bearerInterceptor()`, AI trả 401 thì token cũ bị bỏ để lần sau lấy mới. AI kiểm quyền ở
  `main.py` (`require_any(EKYC_OCR)`, D35).
- Bỏ `X-Internal-Token`, `AI_INTERNAL_TOKEN`, `require_internal_token` và chế độ dev "token rỗng thì nhận hết" của bản
  đầu; test bảo mật của AI (`tests/test_security.py`) đổi route đã xoá `/ekyc/ocr` sang `/ekyc/document` và kiểm thêm
  `/liveness`.
- Giữ `InternalAiRouteBlockingFilter` (Gateway trả 404 cho `/api/v1/ai/ekyc/**` từ ngoài) làm lớp chặn thứ hai.
- `POST /api/v1/users/profile/ekyc-verify` chịu thêm giới hạn chung của D40 (`@RateLimited` 5 lần/10 phút, `429
  RATE_LIMITED` kèm `Retry-After`) ngoài giới hạn 10 giây của mục 3.
- Ánh xạ CIC dùng ID hồ sơ `user_profiles.id` làm `borrowerId` (B26 của `main`).
