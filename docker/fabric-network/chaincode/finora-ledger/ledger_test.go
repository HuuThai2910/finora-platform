package main

import (
	"encoding/json"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	"github.com/hyperledger/fabric-protos-go-apiv2/ledger/queryresult"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const (
	testProofID = "0b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f50"
	testHash    = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
)

var org1Writers = map[string]bool{"Org1MSP": true}

// fakeState mô phỏng world state đủ cho logic: composite key cùng định dạng với Fabric và đếm số lần ghi.
type fakeState struct {
	values map[string][]byte
	puts   int
	txID   string
}

func newFakeState(txID string) *fakeState {
	return &fakeState{values: map[string][]byte{}, txID: txID}
}

func (state *fakeState) GetState(key string) ([]byte, error) { return state.values[key], nil }

func (state *fakeState) PutState(key string, value []byte) error {
	state.values[key] = value
	state.puts++
	return nil
}

func (state *fakeState) CreateCompositeKey(objectType string, attributes []string) (string, error) {
	return "\x00" + objectType + "\x00" + strings.Join(attributes, "\x00") + "\x00", nil
}

func (state *fakeState) GetStateByPartialCompositeKey(objectType string, keys []string) (shim.StateQueryIteratorInterface, error) {
	prefix, _ := state.CreateCompositeKey(objectType, keys)
	matched := make([]string, 0)
	for key := range state.values {
		if strings.HasPrefix(key, prefix) {
			matched = append(matched, key)
		}
	}
	sort.Strings(matched)
	entries := make([]*queryresult.KV, 0, len(matched))
	for _, key := range matched {
		entries = append(entries, &queryresult.KV{Key: key, Value: state.values[key]})
	}
	return &fakeIterator{entries: entries}, nil
}

func (state *fakeState) GetTxID() string { return state.txID }

func (state *fakeState) GetTxTimestamp() (*timestamppb.Timestamp, error) {
	return timestamppb.New(time.Date(2026, 10, 17, 3, 0, 0, 0, time.UTC)), nil
}

type fakeIterator struct {
	entries []*queryresult.KV
	index   int
}

func (iterator *fakeIterator) HasNext() bool { return iterator.index < len(iterator.entries) }

func (iterator *fakeIterator) Next() (*queryresult.KV, error) {
	entry := iterator.entries[iterator.index]
	iterator.index++
	return entry, nil
}

func (iterator *fakeIterator) Close() error { return nil }

func args(proofID, proofType, aggregateType, aggregateID, hash string) []string {
	return []string{proofID, proofType, aggregateType, aggregateID, hash, "2", "finora-loan"}
}

func TestRegisterProofStoresHashOnlyRecordWithTransactionReference(t *testing.T) {
	state := newFakeState("tx-1")

	payload, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "CONTRACT_DOCUMENT", "LoanContract", "LC-2026-000123", testHash))
	if err != nil {
		t.Fatalf("không ghi được proof: %v", err)
	}
	var record ProofRecord
	if err := json.Unmarshal(payload, &record); err != nil {
		t.Fatalf("payload không phải JSON: %v", err)
	}
	if record.TxID != "tx-1" || record.SubmitterMSPID != "Org1MSP" || record.RecordedAt != "2026-10-17T03:00:00Z" {
		t.Fatalf("thiếu tham chiếu giao dịch: %+v", record)
	}
	if record.PayloadHash != testHash || record.PayloadVersion != 2 || record.SourceService != "finora-loan" {
		t.Fatalf("nội dung proof sai: %+v", record)
	}
	if state.puts != 2 {
		t.Fatalf("phải ghi đúng bản ghi và index, đã ghi %d", state.puts)
	}
}

func TestNoteTransferDocumentProofIsAcceptedPerTrade(t *testing.T) {
	state := newFakeState("tx-1")

	payload, err := registerProof(state, "Org1MSP", org1Writers, []string{testProofID, "NOTE_TRANSFER_DOCUMENT",
		"NoteTransferDocument", "TRD-ABCDEF0123456789ABCD", testHash, "2", "finora-investment"})
	if err != nil {
		t.Fatalf("giấy xác nhận chuyển nhượng phải ghi được: %v", err)
	}
	var record ProofRecord
	if err := json.Unmarshal(payload, &record); err != nil {
		t.Fatalf("payload không phải JSON: %v", err)
	}
	if record.ProofType != "NOTE_TRANSFER_DOCUMENT" || record.AggregateType != "NoteTransferDocument" ||
		record.AggregateID != "TRD-ABCDEF0123456789ABCD" || record.SourceService != "finora-investment" {
		t.Fatalf("nội dung proof sai: %+v", record)
	}
}

func TestSameProofSubmittedAgainReturnsTheOriginalRecordWithoutWriting(t *testing.T) {
	state := newFakeState("tx-1")
	first, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "REPAYMENT", "Repayment", "7c1e1f0a-0d8b-4f55-9a52-2c3c3a8f4d10", testHash))
	if err != nil {
		t.Fatalf("lần đầu lỗi: %v", err)
	}
	state.txID = "tx-2"

	second, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "REPAYMENT", "Repayment", "7c1e1f0a-0d8b-4f55-9a52-2c3c3a8f4d10", testHash))
	if err != nil {
		t.Fatalf("gửi lại phải idempotent: %v", err)
	}
	if string(first) != string(second) {
		t.Fatalf("gửi lại phải trả bản ghi gốc")
	}
	if state.puts != 2 {
		t.Fatalf("gửi lại không được ghi thêm, đã ghi %d", state.puts)
	}
}

func TestSameProofIDWithDifferentHashIsRejected(t *testing.T) {
	state := newFakeState("tx-1")
	if _, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "DISBURSEMENT", "DisbursementSaga", "0e9b73a6-984a-4fe5-bd5f-0b409c9f0d38", testHash)); err != nil {
		t.Fatalf("lần đầu lỗi: %v", err)
	}

	_, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "DISBURSEMENT", "DisbursementSaga", "0e9b73a6-984a-4fe5-bd5f-0b409c9f0d38", strings.Repeat("a", 64)))

	if err == nil || !strings.HasPrefix(err.Error(), errConflict) {
		t.Fatalf("hash khác phải bị từ chối với %s, nhận %v", errConflict, err)
	}
}

func TestSecondProofForTheSameMilestoneIsRejected(t *testing.T) {
	state := newFakeState("tx-1")
	if _, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "CONTRACT_DOCUMENT", "LoanContract", "LC-2026-000123", testHash)); err != nil {
		t.Fatalf("lần đầu lỗi: %v", err)
	}

	_, err := registerProof(state, "Org1MSP", org1Writers,
		args("1b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f51", "CONTRACT_DOCUMENT", "LoanContract", "LC-2026-000123", testHash))

	if err == nil || !strings.HasPrefix(err.Error(), errConflict) {
		t.Fatalf("mốc đã có proof thì proofId khác phải bị từ chối, nhận %v", err)
	}
}

func TestOnlyWriterOrganisationCanRegister(t *testing.T) {
	state := newFakeState("tx-1")

	_, err := registerProof(state, "Org2MSP", org1Writers,
		args(testProofID, "CONTRACT_DOCUMENT", "LoanContract", "LC-2026-000123", testHash))

	if err == nil || !strings.HasPrefix(err.Error(), errForbidden) {
		t.Fatalf("tổ chức kiểm toán không được ghi, nhận %v", err)
	}
	if state.puts != 0 {
		t.Fatalf("không được ghi khi bị từ chối")
	}
}

func TestInputsThatCouldCarryPersonalDataOrMalformedValuesAreRejected(t *testing.T) {
	cases := map[string][]string{
		"proofId không phải UUID":     args("not-a-uuid", "REPAYMENT", "Repayment", "R-1", testHash),
		"loại proof lạ":               args(testProofID, "KYC_DOCUMENT", "Repayment", "R-1", testHash),
		"aggregateId có khoảng trắng": args(testProofID, "REPAYMENT", "Repayment", "Nguyen Van A", testHash),
		"aggregateId có dấu":          args(testProofID, "REPAYMENT", "Repayment", "Hồ-sơ-1", testHash),
		"hash chữ hoa":                args(testProofID, "REPAYMENT", "Repayment", "R-1", strings.ToUpper(testHash)),
		"hash ngắn":                   args(testProofID, "REPAYMENT", "Repayment", "R-1", "abc"),
		"version không dương":         {testProofID, "REPAYMENT", "Repayment", "R-1", testHash, "0", "finora-payment"},
		"source service lạ":           {testProofID, "REPAYMENT", "Repayment", "R-1", testHash, "2", "payment"},
		"thiếu tham số":               {testProofID, "REPAYMENT"},
	}
	for name, input := range cases {
		t.Run(name, func(t *testing.T) {
			state := newFakeState("tx-1")
			_, err := registerProof(state, "Org1MSP", org1Writers, input)
			if err == nil || !strings.HasPrefix(err.Error(), errInvalid) {
				t.Fatalf("phải từ chối với %s, nhận %v", errInvalid, err)
			}
			if state.puts != 0 {
				t.Fatalf("không được ghi dữ liệu không hợp lệ")
			}
		})
	}
}

func TestGetProofReturnsEmptyPayloadWhenMissing(t *testing.T) {
	state := newFakeState("tx-1")

	payload, err := getProof(state, []string{testProofID})

	if err != nil || payload != nil {
		t.Fatalf("không có proof phải trả payload rỗng, nhận %q, %v", payload, err)
	}
}

func TestGetProofsByAggregateListsEveryProofTypeOfTheAggregate(t *testing.T) {
	state := newFakeState("tx-1")
	contractNumber := "LC-2026-000123"
	if _, err := registerProof(state, "Org1MSP", org1Writers,
		args(testProofID, "CONTRACT_DOCUMENT", "LoanContract", contractNumber, testHash)); err != nil {
		t.Fatalf("ghi proof hợp đồng lỗi: %v", err)
	}
	if _, err := registerProof(state, "Org1MSP", org1Writers,
		args("2b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f52", "DISBURSEMENT", "LoanContract", contractNumber, testHash)); err != nil {
		t.Fatalf("ghi proof thứ hai lỗi: %v", err)
	}
	if _, err := registerProof(state, "Org1MSP", org1Writers,
		args("3b9f4f6e-6a52-5d6e-9c1f-6a3b1d2e4f53", "CONTRACT_DOCUMENT", "LoanContract", "LC-2026-000999", testHash)); err != nil {
		t.Fatalf("ghi proof aggregate khác lỗi: %v", err)
	}

	payload, err := getProofsByAggregate(state, []string{"LoanContract", contractNumber})
	if err != nil {
		t.Fatalf("truy vấn lỗi: %v", err)
	}
	var records []ProofRecord
	if err := json.Unmarshal(payload, &records); err != nil {
		t.Fatalf("payload không phải mảng JSON: %v", err)
	}
	if len(records) != 2 {
		t.Fatalf("phải có đúng hai proof của %s, nhận %d", contractNumber, len(records))
	}
}

func TestWriterListDefaultsToFinoraOrganisation(t *testing.T) {
	writers := parseWriterMSPs("  ")
	if !writers["Org1MSP"] || len(writers) != 1 {
		t.Fatalf("mặc định chỉ Org1MSP được ghi, nhận %v", writers)
	}
	writers = parseWriterMSPs("Org1MSP, Org3MSP")
	if !writers["Org3MSP"] || len(writers) != 2 {
		t.Fatalf("phải đọc được danh sách nhiều MSP, nhận %v", writers)
	}
}
