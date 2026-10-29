package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// Sổ bằng chứng hash-only của FINORA (R6, W3D). Mỗi bản ghi chỉ có định danh nghiệp vụ, SHA-256 của artifact đã
// chuẩn hóa và metadata tối thiểu; chaincode không nhận PDF, CCCD, tên người hay số tiền.
//
// Bất biến:
//   - Bản ghi ghi một lần, không có hàm sửa/xóa.
//   - RegisterProof idempotent: cùng proofId + cùng nội dung trả lại bản ghi cũ; khác nội dung bị từ chối.
//   - Mỗi mốc nghiệp vụ (proofType + aggregateType + aggregateId) chỉ có một proofId trên chain.
//   - Chỉ MSP trong danh sách writer (mặc định Org1MSP — FINORA) được ghi; tổ chức kiểm toán chỉ đọc nhưng vẫn
//     phải endorse mọi lần ghi theo endorsement policy MAJORITY của channel.

const (
	proofObjectType    = "finora~proof"
	aggregateIndexType = "finora~aggregate~type"

	errInvalid   = "PROOF_INVALID"
	errConflict  = "PROOF_CONFLICT"
	errForbidden = "PROOF_FORBIDDEN"
	errState     = "PROOF_STATE_ERROR"
)

var (
	uuidPattern          = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	hashPattern          = regexp.MustCompile(`^[0-9a-f]{64}$`)
	aggregateTypePattern = regexp.MustCompile(`^[A-Z][A-Za-z0-9]{1,49}$`)
	// Chỉ ký tự của mã nghiệp vụ (LC-2026-000123, UUID, TRD-...): không có khoảng trắng hay chữ có dấu nên không
	// thể chèn họ tên/địa chỉ vào chain qua trường này.
	aggregateIDPattern   = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$`)
	sourceServicePattern = regexp.MustCompile(`^finora-[a-z0-9-]{1,40}$`)

	allowedProofTypes = map[string]bool{
		"CONTRACT_DOCUMENT":      true,
		"DISBURSEMENT":           true,
		"REPAYMENT":              true,
		"NOTE_OWNERSHIP":         true,
		"NOTE_TRANSFER_DOCUMENT": true, // giấy xác nhận chuyển nhượng Note (PDF ký PAdES), theo tradeReference
	}
)

// ProofRecord là nội dung lưu trên world state; thứ tự field cố định để mọi endorser trả cùng bytes.
type ProofRecord struct {
	ProofID        string `json:"proofId"`
	ProofType      string `json:"proofType"`
	AggregateType  string `json:"aggregateType"`
	AggregateID    string `json:"aggregateId"`
	PayloadHash    string `json:"payloadHash"`
	PayloadVersion int    `json:"payloadVersion"`
	SourceService  string `json:"sourceService"`
	TxID           string `json:"txId"`
	RecordedAt     string `json:"recordedAt"`
	SubmitterMSPID string `json:"submitterMspId"`
}

// ledgerState là phần nhỏ của stub mà logic cần; shim.ChaincodeStubInterface thỏa interface này, test dùng bản giả.
type ledgerState interface {
	GetState(key string) ([]byte, error)
	PutState(key string, value []byte) error
	CreateCompositeKey(objectType string, attributes []string) (string, error)
	GetStateByPartialCompositeKey(objectType string, keys []string) (shim.StateQueryIteratorInterface, error)
	GetTxID() string
	GetTxTimestamp() (*timestamppb.Timestamp, error)
}

type proofInput struct {
	proofID        string
	proofType      string
	aggregateType  string
	aggregateID    string
	payloadHash    string
	payloadVersion int
	sourceService  string
}

func (input proofInput) sameContent(record ProofRecord) bool {
	return record.ProofID == input.proofID &&
		record.ProofType == input.proofType &&
		record.AggregateType == input.aggregateType &&
		record.AggregateID == input.aggregateID &&
		record.PayloadHash == input.payloadHash &&
		record.PayloadVersion == input.payloadVersion &&
		record.SourceService == input.sourceService
}

func parseProofInput(args []string) (proofInput, error) {
	if len(args) != 7 {
		return proofInput{}, fmt.Errorf("%s: RegisterProof cần 7 tham số, nhận %d", errInvalid, len(args))
	}
	input := proofInput{
		proofID:       args[0],
		proofType:     args[1],
		aggregateType: args[2],
		aggregateID:   args[3],
		payloadHash:   args[4],
		sourceService: args[6],
	}
	if !uuidPattern.MatchString(input.proofID) {
		return proofInput{}, fmt.Errorf("%s: proofId phải là UUID chữ thường", errInvalid)
	}
	if !allowedProofTypes[input.proofType] {
		return proofInput{}, fmt.Errorf("%s: proofType không nằm trong danh sách được phép", errInvalid)
	}
	if !aggregateTypePattern.MatchString(input.aggregateType) {
		return proofInput{}, fmt.Errorf("%s: aggregateType không hợp lệ", errInvalid)
	}
	if !aggregateIDPattern.MatchString(input.aggregateID) {
		return proofInput{}, fmt.Errorf("%s: aggregateId chỉ nhận mã nghiệp vụ", errInvalid)
	}
	if !hashPattern.MatchString(input.payloadHash) {
		return proofInput{}, fmt.Errorf("%s: payloadHash phải là SHA-256 hex chữ thường", errInvalid)
	}
	version, err := strconv.Atoi(args[5])
	if err != nil || version < 1 || version > 1000 {
		return proofInput{}, fmt.Errorf("%s: payloadVersion phải là số nguyên 1..1000", errInvalid)
	}
	input.payloadVersion = version
	if !sourceServicePattern.MatchString(input.sourceService) {
		return proofInput{}, fmt.Errorf("%s: sourceService phải theo mẫu finora-<service>", errInvalid)
	}
	return input, nil
}

// registerProof ghi bằng chứng mới hoặc trả lại bản ghi cũ nếu nội dung trùng khớp.
func registerProof(state ledgerState, submitterMSP string, writers map[string]bool, args []string) ([]byte, error) {
	if !writers[submitterMSP] {
		return nil, fmt.Errorf("%s: MSP %s không được ghi bằng chứng", errForbidden, submitterMSP)
	}
	input, err := parseProofInput(args)
	if err != nil {
		return nil, err
	}
	proofKey, err := state.CreateCompositeKey(proofObjectType, []string{input.proofID})
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	existing, err := state.GetState(proofKey)
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	if len(existing) > 0 {
		var record ProofRecord
		if err := json.Unmarshal(existing, &record); err != nil {
			return nil, fmt.Errorf("%s: bản ghi cũ không đọc được", errState)
		}
		if !input.sameContent(record) {
			return nil, fmt.Errorf("%s: proofId %s đã ghi với nội dung khác", errConflict, input.proofID)
		}
		// Lần gửi lại sau timeout/restart: không ghi gì thêm, trả bản ghi gốc kèm txId của lần ghi đầu.
		return existing, nil
	}

	indexKey, err := state.CreateCompositeKey(aggregateIndexType,
		[]string{input.aggregateType, input.aggregateID, input.proofType})
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	indexed, err := state.GetState(indexKey)
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	if len(indexed) > 0 && string(indexed) != input.proofID {
		return nil, fmt.Errorf("%s: mốc %s %s/%s đã có proof khác", errConflict,
			input.proofType, input.aggregateType, input.aggregateID)
	}

	timestamp, err := state.GetTxTimestamp()
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	record := ProofRecord{
		ProofID:        input.proofID,
		ProofType:      input.proofType,
		AggregateType:  input.aggregateType,
		AggregateID:    input.aggregateID,
		PayloadHash:    input.payloadHash,
		PayloadVersion: input.payloadVersion,
		SourceService:  input.sourceService,
		TxID:           state.GetTxID(),
		// Timestamp nằm trong proposal nên mọi endorser tính ra cùng giá trị.
		RecordedAt:     timestamp.AsTime().UTC().Format(time.RFC3339Nano),
		SubmitterMSPID: submitterMSP,
	}
	payload, err := json.Marshal(record)
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	if err := state.PutState(proofKey, payload); err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	if err := state.PutState(indexKey, []byte(input.proofID)); err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	return payload, nil
}

// getProof trả JSON bản ghi; không tìm thấy thì trả nil (payload rỗng) để client phân biệt với lỗi.
func getProof(state ledgerState, args []string) ([]byte, error) {
	if len(args) != 1 || !uuidPattern.MatchString(args[0]) {
		return nil, fmt.Errorf("%s: GetProof cần một proofId dạng UUID", errInvalid)
	}
	key, err := state.CreateCompositeKey(proofObjectType, []string{args[0]})
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	payload, err := state.GetState(key)
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	if len(payload) == 0 {
		return nil, nil
	}
	return payload, nil
}

// getProofsByAggregate liệt kê mọi proof của một aggregate qua composite key, chạy được trên LevelDB.
func getProofsByAggregate(state ledgerState, args []string) ([]byte, error) {
	if len(args) != 2 || !aggregateTypePattern.MatchString(args[0]) || !aggregateIDPattern.MatchString(args[1]) {
		return nil, fmt.Errorf("%s: GetProofsByAggregate cần aggregateType và aggregateId hợp lệ", errInvalid)
	}
	iterator, err := state.GetStateByPartialCompositeKey(aggregateIndexType, []string{args[0], args[1]})
	if err != nil {
		return nil, fmt.Errorf("%s: %v", errState, err)
	}
	defer iterator.Close()
	records := make([]ProofRecord, 0)
	for iterator.HasNext() {
		entry, err := iterator.Next()
		if err != nil {
			return nil, fmt.Errorf("%s: %v", errState, err)
		}
		payload, err := getProof(state, []string{string(entry.GetValue())})
		if err != nil {
			return nil, err
		}
		if payload == nil {
			return nil, fmt.Errorf("%s: index trỏ tới proof không tồn tại", errState)
		}
		var record ProofRecord
		if err := json.Unmarshal(payload, &record); err != nil {
			return nil, fmt.Errorf("%s: bản ghi không đọc được", errState)
		}
		records = append(records, record)
	}
	return json.Marshal(records)
}

// parseWriterMSPs đọc danh sách MSP được ghi từ biến môi trường; rỗng thì chỉ Org1MSP (FINORA).
func parseWriterMSPs(raw string) map[string]bool {
	writers := map[string]bool{}
	for _, value := range strings.Split(raw, ",") {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			writers[trimmed] = true
		}
	}
	if len(writers) == 0 {
		writers["Org1MSP"] = true
	}
	return writers
}

var errMissingChaincodeID = errors.New("thiếu CHAINCODE_ID hoặc CHAINCODE_ID_FILE (package ID sau khi install)")
