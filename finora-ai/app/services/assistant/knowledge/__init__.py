"""Kho kiến thức của Trợ lý FINORA: đoạn chính sách/pháp lý dạng YAML + tìm kiếm BM25.

- `store.py`: đọc và kiểm khuôn `chunks/*.yaml` thành `KnowledgeChunk`.
- `bm25.py`: tách từ tiếng Việt và chỉ mục BM25 trong bộ nhớ (`get_index()`).
- `legal_sources.yaml`: nguồn pháp lý được phép trích dẫn, đọc bằng `app.services.assistant.legal`.

Nội dung YAML là dữ liệu có phiên bản; sửa nội dung không cần sửa code.
"""
