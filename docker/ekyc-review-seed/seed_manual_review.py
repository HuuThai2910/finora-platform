"""Tạo một lượt xác minh danh tính đang chờ duyệt tay để thử nút "Duyệt xác minh" trên web quản trị.

Chọn một khách hàng đang "Chờ duyệt tay" chưa có lượt mở, rồi chèn vào DB finora-user một dòng
`ekyc_attempts` trạng thái MANUAL_REVIEW với bản nháp định danh mã hoá AES-GCM giống `CryptoConverter`
(khoá = SHA-256 của FINORA_AES_SECRET, lưu Base64(IV 12 byte + ciphertext + tag)). Số CCCD trong bản nháp
lấy đúng số đã lưu của khách (giải mã) để bước duyệt không báo lệch số. Lượt mẫu không có ảnh/video bằng chứng.

Đọc kết nối và khoá từ `finora-user/.env` (USER_DB_URL, USER_DB_USERNAME, USER_DB_PASSWORD, FINORA_AES_SECRET,
FINORA_HMAC_SECRET). Không in PII: số CCCD chỉ hiện 4 số cuối.

    python seed_manual_review.py --dry-run          # chạy thử rồi ROLLBACK
    python seed_manual_review.py                    # chèn thật, ghi out/<attemptId>.json để gỡ
    python seed_manual_review.py --email a@b.vn     # chọn khách cụ thể (phải đang Chờ duyệt tay)
    python seed_manual_review.py --cleanup <attemptId>

Gỡ: xoá lượt (và nhật ký xem bằng chứng nếu có), trả các cột xác minh của hồ sơ về như lúc chèn, xoá việc đối
chiếu CIC nếu do lần duyệt tạo ra. Trường định danh đã được điền khi duyệt (họ tên, ngày sinh…) chỉ được trả về
nếu trước đó trống. Claim trên Keycloak sẽ tự đúng lại ở lần đăng nhập sau.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import sys
import uuid
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import psycopg
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT / "finora-user" / ".env"
OUT_DIR = Path(__file__).resolve().parent / "out"

# Các cột hồ sơ mà duyệt/từ chối hoặc applyIdentity có thể đổi; chụp lại để gỡ.
PROFILE_COLUMNS = [
    "ekyc_status", "document_verified", "liveness_verified", "face_match_score", "ekyc_completed_at",
    "full_name", "date_of_birth", "gender", "place_of_origin", "address", "id_card_type", "id_issue_date",
    "id_expiry_date", "birth_registration_place", "id_number_hash", "id_number_encrypted",
]

ADDRESSES = [
    "Số 18 ngõ 42 Trần Duy Hưng, Phường Trung Hòa, Quận Cầu Giấy, Hà Nội",
    "Số 215 Nguyễn Văn Cừ, Phường 4, Quận 5, TP. Hồ Chí Minh",
    "Tổ 7, Phường Hòa Khánh Bắc, Quận Liên Chiểu, Đà Nẵng",
]
ORIGINS = ["Xã Nghĩa Hưng, Huyện Nghĩa Đàn, Nghệ An", "Xã Tân Phong, Huyện Kiến Xương, Thái Bình", "Phường An Cựu, TP. Huế"]


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def connect(env: dict[str, str]) -> psycopg.Connection:
    url = urlparse(env["USER_DB_URL"].removeprefix("jdbc:"))
    sslmode = parse_qs(url.query).get("sslmode", ["require"])[0]
    return psycopg.connect(
        host=url.hostname, port=url.port or 5432, dbname=url.path.lstrip("/"),
        user=env["USER_DB_USERNAME"], password=env["USER_DB_PASSWORD"], sslmode=sslmode,
    )


def aes_key(secret: str) -> bytes:
    return hashlib.sha256(secret.encode("utf-8")).digest()


def encrypt(plaintext: str, secret: str) -> str:
    iv = os.urandom(12)
    return base64.b64encode(iv + AESGCM(aes_key(secret)).encrypt(iv, plaintext.encode("utf-8"), None)).decode()


def decrypt(ciphertext: str, secret: str) -> str:
    raw = base64.b64decode(ciphertext)
    return AESGCM(aes_key(secret)).decrypt(raw[:12], raw[12:], None).decode("utf-8")


def hmac_hex(data: str, secret: str) -> str:
    return hmac.new(secret.encode("utf-8"), data.encode("utf-8"), hashlib.sha256).hexdigest()


def mask(value: str) -> str:
    return "*" * max(0, len(value) - 4) + value[-4:]


def json_default(value):
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    raise TypeError(type(value))


def pick_profile(cur, email: str | None):
    where = "p.ekyc_status = 'MANUAL_REVIEW' and not exists (select 1 from ekyc_attempts a where a.user_profile_id = p.id" \
            " and a.status in ('IN_PROGRESS','MANUAL_REVIEW'))"
    params: list = []
    if email:
        where += " and lower(p.email) = lower(%s)"
        params.append(email)
    cols = ", ".join(f"p.{c}" for c in PROFILE_COLUMNS)
    cur.execute(f"select p.id, p.email, {cols} from user_profiles p where {where} order by p.full_name is null, p.id limit 1",
                params)
    row = cur.fetchone()
    if not row:
        sys.exit("Không có khách hàng nào đang Chờ duyệt tay mà chưa có lượt mở"
                 + (f" với email {email}." if email else "."))
    names = ["id", "email", *PROFILE_COLUMNS]
    return dict(zip(names, row))


def new_id_number(cur, hmac_secret: str) -> str:
    # 12 số: 3 số mã tỉnh (001 Hà Nội), 1 số thế kỷ/giới tính, 2 số năm sinh, 6 số ngẫu nhiên; không trùng hash đã có.
    for _ in range(50):
        candidate = "0010" + "95" + f"{random.randint(0, 999999):06d}"
        cur.execute("select 1 from user_profiles where id_number_hash = %s", (hmac_hex(candidate, hmac_secret),))
        if not cur.fetchone():
            return candidate
    sys.exit("Không sinh được số CCCD chưa dùng.")


def build_draft(attempt_id: uuid.UUID, profile: dict, id_number: str) -> dict:
    dob = profile["date_of_birth"] or date(1995, 3, 12)
    gender = {"MALE": "Nam", "FEMALE": "Nữ"}.get(profile["gender"] or "", "Nam")

    def field(value: str | None, *sources: str) -> dict:
        return {"value": value, "sources": list(sources)}

    fields = {
        "idNumber": field(id_number, "QR", "OCR"),
        "fullName": field((profile["full_name"] or "Nguyễn Minh Anh").upper(), "QR", "OCR"),
        "dateOfBirth": field(dob.strftime("%d/%m/%Y"), "QR", "OCR"),
        "gender": field(gender, "QR", "OCR"),
        "address": field(random.choice(ADDRESSES), "QR"),
        "placeOfOrigin": field(random.choice(ORIGINS), "AI"),
        "birthRegistrationPlace": field(None),
        "issueDate": field("15/08/2024", "QR"),
        "expiryDate": field(date(dob.year + 40, dob.month, min(dob.day, 28)).strftime("%d/%m/%Y"), "OCR"),
    }
    return {
        "attemptId": str(attempt_id),
        "cardType": "CAN_CUOC_2024",
        "documentDecision": "NEEDS_REVIEW",
        "documentReasons": ["MRZ_UNREADABLE", "SINGLE_SOURCE", "GLARE"],
        "documentWarnings": ["SOFT_FLAGS"],
        "documentChecks": None,
        "documentThresholdsVersion": "doc-2026.10",
        "fields": fields,
        "noExpiry": False,
        "portraitEmbeddingB64": None,
        "livenessResult": "REVIEW",
        "faceMatchScore": 0.58,
        "livenessReasons": ["ACTIONS_MATCH", "FACE_MATCH_GRAY", "TOO_DARK"],
        "livenessThresholdsVersion": "live-2026.10",
        "livenessCaptureMode": "VIDEO",
        "livenessRound": 1,
        "editedFields": [],
    }


def seed(env: dict[str, str], email: str | None, dry_run: bool) -> None:
    aes, hmac_secret = env["FINORA_AES_SECRET"], env["FINORA_HMAC_SECRET"]
    with connect(env) as conn, conn.cursor() as cur:
        profile = pick_profile(cur, email)
        id_number = decrypt(profile["id_number_encrypted"], aes) if profile["id_number_encrypted"] else new_id_number(cur, hmac_secret)
        attempt_id = uuid.uuid4()
        draft = build_draft(attempt_id, profile, id_number)
        now = datetime.now(timezone.utc)
        cur.execute(
            """insert into ekyc_attempts (public_id, user_profile_id, status, card_type, document_decision, document_reasons,
                   liveness_result, liveness_reasons, face_match_score, thresholds_version, draft_encrypted, submitted_at,
                   version, created_at, updated_at)
               values (%s, %s, 'MANUAL_REVIEW', %s, 'NEEDS_REVIEW', %s, 'REVIEW', %s, %s, %s, %s, %s, 0, %s, %s)""",
            (attempt_id, profile["id"], draft["cardType"], json.dumps(draft["documentReasons"]),
             json.dumps(draft["livenessReasons"]), draft["faceMatchScore"], "doc-2026.10,live-2026.10",
             encrypt(json.dumps(draft, ensure_ascii=False), aes), now, now, now),
        )
        cur.execute("select count(*) from user_cic_mapping_tasks where user_profile_id = %s", (profile["id"],))
        had_cic_task = cur.fetchone()[0] > 0
        print(f"Khách: #{profile['id']} {profile['email']} (CCCD {mask(id_number)})")
        print(f"Lượt: {attempt_id}")
        if dry_run:
            conn.rollback()
            print("Chạy thử: đã ROLLBACK, DB không đổi.")
            return
        conn.commit()
    OUT_DIR.mkdir(exist_ok=True)
    snapshot = {k: v for k, v in profile.items() if k not in ("id_number_encrypted", "id_number_hash")}
    snapshot["had_id_number"] = profile["id_number_hash"] is not None
    manifest = {"attempt_id": str(attempt_id), "user_profile_id": profile["id"], "seeded_at": now,
                "had_cic_task": had_cic_task, "profile_before": snapshot}
    (OUT_DIR / f"{attempt_id}.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, default=json_default),
                                                encoding="utf-8")
    print(f"Đã chèn. Mở web quản trị: /customers/kyc/review/{attempt_id}")
    print(f"Gỡ: python {Path(__file__).name} --cleanup {attempt_id}")


def cleanup(env: dict[str, str], attempt_id: str) -> None:
    manifest_path = OUT_DIR / f"{attempt_id}.json"
    if not manifest_path.exists():
        sys.exit(f"Không thấy {manifest_path}; chỉ gỡ được lượt do script này tạo.")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    before = manifest["profile_before"]
    profile_id = manifest["user_profile_id"]
    with connect(env) as conn, conn.cursor() as cur:
        cur.execute("select id from ekyc_attempts where public_id = %s", (attempt_id,))
        row = cur.fetchone()
        if row:
            cur.execute("delete from ekyc_evidence_access_log where attempt_id = %s", (row[0],))
            cur.execute("delete from ekyc_evidence where attempt_id = %s", (row[0],))
            cur.execute("delete from ekyc_attempts where id = %s", (row[0],))
        # Cột xác minh luôn trả về; cột định danh chỉ trả về rỗng nếu trước đó rỗng (applyIdentity chỉ điền chỗ trống).
        cur.execute(
            """update user_profiles set ekyc_status = %s, document_verified = %s, liveness_verified = %s,
                   face_match_score = %s, ekyc_completed_at = %s, updated_at = now() where id = %s""",
            (before["ekyc_status"], before["document_verified"], before["liveness_verified"], before["face_match_score"],
             before["ekyc_completed_at"], profile_id),
        )
        for column in ("full_name", "date_of_birth", "gender", "place_of_origin", "address", "id_card_type",
                       "id_issue_date", "id_expiry_date", "birth_registration_place"):
            if before[column] is None:
                cur.execute(f"update user_profiles set {column} = null where id = %s", (profile_id,))
        if not before["had_id_number"]:
            cur.execute("update user_profiles set id_number_hash = null, id_number_encrypted = null where id = %s",
                        (profile_id,))
        if not manifest["had_cic_task"]:
            cur.execute("delete from user_cic_mapping_tasks where user_profile_id = %s and created_at >= %s",
                        (profile_id, manifest["seeded_at"]))
        conn.commit()
    manifest_path.unlink()
    print(f"Đã gỡ lượt {attempt_id} và trả hồ sơ #{profile_id} về như lúc chèn.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--email", help="Email khách đang Chờ duyệt tay; bỏ trống thì tự chọn")
    parser.add_argument("--dry-run", action="store_true", help="Chạy thử rồi ROLLBACK")
    parser.add_argument("--cleanup", metavar="ATTEMPT_ID", help="Gỡ lượt đã chèn")
    args = parser.parse_args()
    env = load_env(ENV_FILE)
    if args.cleanup:
        cleanup(env, args.cleanup)
    else:
        seed(env, args.email, args.dry_run)


if __name__ == "__main__":
    main()
