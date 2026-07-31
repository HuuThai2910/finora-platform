# Hiệu năng và truy cập dữ liệu

## JPA/Hibernate

- Giữ association mặc định `LAZY`; cấm chuyển sang `EAGER` để che N+1.
- Cấm trả entity từ controller hoặc serialize lazy association.
- Endpoint danh sách phải phân trang và map sang DTO. Cấm `findAll()` trên dữ liệu nghiệp vụ có thể tăng không giới hạn.
- Chỉ tải dữ liệu cần cho use case bằng DTO projection, `JOIN FETCH`, `@EntityGraph` hoặc query chuyên biệt.
- Không fetch join nhiều collection dạng bag trong cùng query. Tách query hoặc dùng batch fetch có kiểm soát.
- Cấm gọi repository hoặc REST client theo từng phần tử trong vòng lặp. Gom ID rồi query/batch call một lần.
- Query filter/sort/join thường xuyên phải có index phù hợp. Kiểm tra cả thứ tự cột của composite index.
- Batch insert/update phải flush và clear theo lô; không giữ hàng chục nghìn entity trong persistence context.
- Dùng `Slice` khi không cần tổng số; chỉ dùng `Page` khi nghiệp vụ thật sự cần count query.
- Không bật Open Session in View. Mapping DTO diễn ra trong application/service transaction.

## Kiểm chứng N+1

- Integration test endpoint/query quan trọng với ít nhất 3 bản ghi cha, mỗi bản ghi có dữ liệu con.
- Đếm statement bằng datasource proxy/Hibernate statistics khi phù hợp; số query phải gần như cố định theo kích thước trang.
- Kiểm tra query plan cho query tài chính hoặc báo cáo lớn; lưu bằng chứng trong PR khi thêm query phức tạp.

## MongoDB, Redis và API

- Mongo query có filter/sort phải có index; dùng projection, cursor/pagination và bulk operation.
- Redis chỉ là cache/lock hỗ trợ, không phải nguồn sự thật của số dư.
- Mọi cache phải định nghĩa key, TTL, owner và invalidation. Không cache số dư nếu chưa chứng minh tính nhất quán.
- Giới hạn `size` bằng cấu hình; mặc định 20, tối đa 100 nếu use case không yêu cầu khác.
- External call luôn có connect/read timeout. Không giữ DB transaction trong lúc gọi mạng nếu có thể tách an toàn.

