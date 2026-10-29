### Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)
Phiên bản ekyc-2026-10 · Áp dụng từ {{EFFECTIVE_DATE}}

Để vay hoặc đầu tư trên FINORA, bạn cần xác minh danh tính. Trước khi bắt đầu, vui lòng đọc kỹ điều khoản dưới đây. Bạn chỉ bắt đầu xác minh được khi đã đọc hết và tick ô xác nhận ở cuối trang. Nếu chưa muốn, bạn chọn "Để sau": bạn vẫn dùng được ứng dụng, nhưng chưa vay hoặc đầu tư được.

**1. Ai xử lý dữ liệu của bạn**

FINORA là bên quyết định mục đích và cách xử lý dữ liệu trong quá trình xác minh. Mọi câu hỏi hoặc yêu cầu về dữ liệu cá nhân, bạn gửi tới {{CONTACT}}.

**2. Chúng tôi thu những dữ liệu nào**
- Ảnh chụp mặt trước và mặt sau thẻ Căn cước công dân hoặc thẻ Căn cước của bạn.
- Thông tin đọc được từ thẻ (từ mã QR, dòng ký tự máy đọc ở mặt sau, và chữ in trên thẻ): số định danh cá nhân, họ và tên, ngày sinh, giới tính, nơi thường trú hoặc nơi cư trú, ngày cấp, ngày hết hạn.
- Một video ngắn (khoảng 5–10 giây) quay khuôn mặt bạn khi làm theo hướng dẫn trên màn hình, và một số khung hình trích từ video đó.
- Kết quả xác minh: điểm so khớp khuôn mặt, kết quả kiểm tra người thật, lý do nếu hồ sơ cần duyệt lại.
- Thông tin kỹ thuật của lần xác minh: thời điểm, loại thiết bị, phiên bản ứng dụng.

**3. Đây là dữ liệu cá nhân nhạy cảm**

Theo Nghị định 356/2025/NĐ-CP, **hình ảnh thẻ căn cước** và **dữ liệu sinh trắc học** (đặc điểm khuôn mặt dùng để nhận dạng) là dữ liệu cá nhân nhạy cảm. FINORA chỉ xử lý các dữ liệu này khi bạn đồng ý, và áp dụng các biện pháp bảo vệ nêu ở mục 7.

**4. Chúng tôi dùng dữ liệu để làm gì**
- Xác minh bạn là chủ thật của tấm thẻ, và chính bạn đang thực hiện xác minh (không phải ảnh, video quay sẵn hay người khác).
- Điền sẵn thông tin hồ sơ của bạn từ thẻ để bạn kiểm tra và xác nhận.
- Bảo đảm mỗi số định danh chỉ gắn với một tài khoản, phòng chống mạo danh và gian lận.
- Để nhân viên duyệt hồ sơ xem lại khi hệ thống không tự kết luận được, và để đối chiếu khi có khiếu nại hoặc tranh chấp.

FINORA **không** dùng các dữ liệu này cho quảng cáo, **không** bán hay chia sẻ cho bên khác để họ dùng vào mục đích riêng.

**5. Dữ liệu được xử lý và lưu ở đâu**
- Máy chủ xử lý của FINORA đặt tại **{{SERVER_LOCATION}}**.
- Ảnh thẻ, video khuôn mặt và kết quả xác minh được lưu, có mã hoá, trên **{{STORAGE_LOCATION}}**.
<!-- IF:GEMINI -->
- Nếu hệ thống không đọc được một số thông tin từ mã QR và dòng ký tự máy đọc, phần chữ trên thẻ (đã **che ảnh chân dung**) có thể được gửi tới dịch vụ AI của Google để đọc. Ảnh khuôn mặt và video **không bao giờ** được gửi tới dịch vụ này.
<!-- ENDIF:GEMINI -->

Vì các máy chủ trên đặt ngoài Việt Nam, việc này là **chuyển dữ liệu cá nhân ra nước ngoài**. FINORA chỉ làm vậy khi bạn đồng ý ở ô xác nhận bên dưới.

**6. Ai được xem dữ liệu của bạn**
- Hệ thống tự động xử lý để đưa ra kết quả.
- Chỉ **nhân viên duyệt hồ sơ** được giao quyền mới xem được ảnh thẻ và video, qua đường xem có thời hạn ngắn. Mỗi lần xem đều được ghi lại.
- FINORA chỉ cung cấp dữ liệu cho cơ quan nhà nước khi pháp luật yêu cầu.

**7. Chúng tôi bảo vệ dữ liệu thế nào**
- Dữ liệu được mã hoá khi truyền đi và khi lưu trữ.
- Kho lưu trữ không cho truy cập công khai; chỉ dịch vụ của FINORA có quyền ghi và đọc.
- Số định danh được lưu dưới dạng mã hoá; ứng dụng không ghi ảnh hay video vào nhật ký hệ thống.
- Nếu xảy ra sự cố lộ dữ liệu sinh trắc học của bạn, FINORA sẽ thông báo cho bạn theo thời hạn pháp luật quy định.

**8. Lưu trong bao lâu**
- Lần xác minh bị bỏ dở giữa chừng: ảnh đã chụp được xoá sau **1 ngày**.
- Lần xác minh đã có kết quả: ảnh thẻ, video và kết quả được lưu trong **{{RETENTION}}**, sau đó tự động xoá.
- Dữ liệu chỉ được giữ lâu hơn khi pháp luật bắt buộc. Trong thời gian đó, dữ liệu chỉ được dùng cho đúng nghĩa vụ pháp lý.

**9. Quyền của bạn**

Bạn có quyền:
- được biết về việc xử lý dữ liệu của mình;
- đồng ý hoặc không đồng ý, và **rút lại sự đồng ý** bất cứ lúc nào trong *Cài đặt › Quyền riêng tư eKYC*;
- xem và yêu cầu chỉnh sửa thông tin đọc từ thẻ;
- yêu cầu xoá, hạn chế xử lý, hoặc phản đối việc xử lý dữ liệu của mình;
- khiếu nại hoặc khởi kiện theo quy định của pháp luật.

FINORA giải quyết yêu cầu của bạn trong thời hạn pháp luật quy định.

**Khi bạn rút lại sự đồng ý:**
- ảnh thẻ, video và kết quả xác minh sẽ bị xoá, trừ phần pháp luật bắt buộc lưu giữ;
- tài khoản trở về trạng thái **chưa xác minh**, nên bạn tạm thời không vay hoặc đầu tư được;
- việc rút lại không ảnh hưởng tới những lần xử lý đã diễn ra hợp pháp trước đó.

**10. Nếu bạn không đồng ý**

Mọi nội dung nêu trong điều khoản này đều cần thiết để xác minh danh tính. Nếu bạn không đồng ý, FINORA không thể xác minh bạn, và bạn chưa vay hoặc đầu tư được. Bạn vẫn có thể xem sản phẩm và dùng các tính năng không cần xác minh.

**11. Thay đổi điều khoản**

Khi nội dung điều khoản thay đổi, FINORA sẽ hiển thị lại để bạn đọc và đồng ý trước khi xác minh tiếp.
