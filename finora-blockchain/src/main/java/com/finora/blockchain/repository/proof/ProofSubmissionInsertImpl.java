package com.finora.blockchain.repository.proof;

import com.finora.blockchain.domain.proof.ProofSubmission;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code INSERT ... ON CONFLICT DO NOTHING} không chỉ định conflict target: trùng {@code (source_service,
 * source_event_id)}, trùng {@code proof_id} hay trùng mốc ({@code uq_blockchain_proof_submissions_milestone}) đều bỏ qua.
 * Dùng JDBC với kiểu SQL tường minh để tham số null (bản ghi thiếu metadata) không bị Hibernate gửi sai kiểu; câu lệnh
 * chạy trên cùng connection/transaction JPA hiện hành.
 */
@RequiredArgsConstructor
public class ProofSubmissionInsertImpl implements ProofSubmissionInsert {

    private static final String SQL = """
            INSERT INTO blockchain_proof_submissions (
                proof_id, source_service, source_event_id, source_event_type, source_event_version,
                source_occurred_at, aggregate_type, aggregate_id, loan_application_id,
                proof_type, payload_hash, payload_version, provider, status,
                attempt_count, available_at, version, created_at, updated_at
            ) VALUES (
                :proofId, :sourceService, :sourceEventId, :sourceEventType, :sourceEventVersion,
                :sourceOccurredAt, :aggregateType, :aggregateId, :loanApplicationId,
                :proofType, :payloadHash, :payloadVersion, :provider, 'PENDING',
                0, :now, 0, :now, :now
            )
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public int insertIfAbsent(ProofSubmission candidate) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("proofId", candidate.getProofId(), Types.OTHER)
                .addValue("sourceService", candidate.getSourceService(), Types.VARCHAR)
                .addValue("sourceEventId", candidate.getSourceEventId(), Types.OTHER)
                .addValue("sourceEventType", candidate.getSourceEventType(), Types.VARCHAR)
                .addValue("sourceEventVersion", candidate.getSourceEventVersion(), Types.INTEGER)
                .addValue("sourceOccurredAt", utc(candidate.getSourceOccurredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("aggregateType", candidate.getAggregateType(), Types.VARCHAR)
                .addValue("aggregateId", candidate.getAggregateId(), Types.VARCHAR)
                .addValue("loanApplicationId", candidate.getLoanApplicationId(), Types.BIGINT)
                .addValue("proofType", candidate.getProofType().name(), Types.VARCHAR)
                .addValue("payloadHash", candidate.getPayloadHash(), Types.VARCHAR)
                .addValue("payloadVersion", candidate.getPayloadVersion(), Types.INTEGER)
                .addValue("provider", candidate.getProvider().name(), Types.VARCHAR)
                .addValue("now", utc(candidate.getCreatedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.update(SQL, parameters);
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
