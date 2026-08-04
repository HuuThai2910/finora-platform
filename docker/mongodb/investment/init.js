const databaseName = process.env.MONGO_INITDB_DATABASE;
const applicationUsername = process.env.INVESTMENT_DB_USERNAME;
const applicationPassword = process.env.INVESTMENT_DB_PASSWORD;

if (!databaseName || !applicationUsername || !applicationPassword) {
    throw new Error("Thiếu cấu hình tạo application user cho Investment MongoDB.");
}

// Investment chỉ có quyền read/write trên database của chính service, không dùng Mongo root khi chạy ứng dụng.
db.getSiblingDB(databaseName).createUser({
    user: applicationUsername,
    pwd: applicationPassword,
    roles: [{role: "readWrite", db: databaseName}]
});
