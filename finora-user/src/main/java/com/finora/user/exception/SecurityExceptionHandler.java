package com.finora.user.exception;

import com.finora.common.dto.ApiErrorResponse;
import com.finora.common.logging.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Chuyển lỗi bảo mật thành đúng mã HTTP tại biên của {@code finora-user}.
 * <p>
 * Vì sao phải có lớp này thay vì thêm vào {@code GlobalExceptionHandler} dùng chung:
 * {@code finora-common} không phụ thuộc Spring Security, nên đưa
 * {@link AccessDeniedException} vào đó sẽ làm mọi service không có security hỏng
 * lúc khởi tạo advice. Hiện chỉ {@code finora-user} bật Spring Security.
 * <p>
 * Vì sao phải đặt {@link Ordered#HIGHEST_PRECEDENCE}: {@code GlobalExceptionHandler}
 * có {@code @ExceptionHandler(Exception.class)}. Spring duyệt các advice theo thứ tự
 * và lấy advice đầu tiên khớp được, nên nếu advice dùng chung được hỏi trước thì nó
 * nuốt luôn lỗi bảo mật thành 500 "Lỗi hệ thống chưa được xử lý" — đúng triệu chứng
 * đã gặp khi token thiếu quyền.
 * <p>
 * Lưu ý về nguồn gốc lỗi: request <b>không có token</b> bị chặn ngay ở filter chain
 * và trả 401 mà không đi qua đây. Lỗi tới được lớp này là lỗi từ {@code @PreAuthorize}
 * ở tầng method — tức đã xác thực nhưng thiếu quyền.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SecurityExceptionHandler {

    /** Đã đăng nhập nhưng token không mang đủ quyền mà endpoint yêu cầu. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        // Không log nội dung token hay danh sách quyền — đủ traceId để tra cứu
        log.warn("Từ chối truy cập do thiếu quyền: traceId={}", traceId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiErrorResponse(
                "ACCESS_DENIED",
                "Tài khoản không có quyền thực hiện thao tác này",
                null,
                traceId
        ));
    }

    /** Token thiếu, hết hạn hoặc không hợp lệ khi lọt tới tầng method security. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Yêu cầu chưa xác thực: traceId={}", traceId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiErrorResponse(
                "UNAUTHENTICATED",
                "Phiên đăng nhập không hợp lệ hoặc đã hết hạn",
                null,
                traceId
        ));
    }
}
