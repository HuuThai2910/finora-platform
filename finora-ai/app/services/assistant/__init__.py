"""Trợ lý FINORA — chatbot chỉ đọc cho người vay, nhà đầu tư và quản trị (AI-CHAT-001).

Bounded context không có DB: mọi số liệu đọc từ service sở hữu bằng token của chính người hỏi
(`relay.py`), kiến thức chính sách/pháp lý là file YAML có phiên bản. Trạng thái duy nhất là bộ
đếm trong bộ nhớ (hạn mức nhà cung cấp, rate limit người dùng), mất khi restart là chấp nhận được.
"""
