-- ============================================
-- FINORA Platform — Database Initialization
-- ============================================

CREATE DATABASE IF NOT EXISTS finora_loan;
CREATE DATABASE IF NOT EXISTS finora_payment;
CREATE DATABASE IF NOT EXISTS finora_user;
CREATE DATABASE IF NOT EXISTS finora_investment;

-- Mỗi service sẽ tự tạo bảng qua JPA/Hibernate khi khởi động.
-- File này chỉ khởi tạo sẵn các schema rỗng.
