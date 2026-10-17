// Chaincode finora-ledger chạy theo mô hình chaincode-as-a-service (CCaaS): peer tự kết nối tới server gRPC này
// theo connection.json trong package, nên peer không cần docker.sock để build container chaincode (W3D, D51).
package main

import (
	"log"
	"os"
	"strings"

	"github.com/hyperledger/fabric-chaincode-go/v2/pkg/cid"
	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	"github.com/hyperledger/fabric-protos-go-apiv2/peer"
)

// ProofLedgerChaincode định tuyến lời gọi tới logic thuần trong ledger.go.
type ProofLedgerChaincode struct {
	writers map[string]bool
}

// Init không khởi tạo dữ liệu: Fabric 2.x lifecycle không bắt buộc init và sổ bắt đầu rỗng.
func (chaincode *ProofLedgerChaincode) Init(shim.ChaincodeStubInterface) *peer.Response {
	return shim.Success(nil)
}

// Invoke chỉ có ba hàm; không có hàm sửa/xóa để bản ghi là write-once.
func (chaincode *ProofLedgerChaincode) Invoke(stub shim.ChaincodeStubInterface) *peer.Response {
	function, args := stub.GetFunctionAndParameters()
	var (
		payload []byte
		err     error
	)
	switch function {
	case "RegisterProof":
		submitter, mspErr := cid.GetMSPID(stub)
		if mspErr != nil {
			return shim.Error(errForbidden + ": không đọc được MSP của người gửi")
		}
		payload, err = registerProof(stub, submitter, chaincode.writers, args)
	case "GetProof":
		payload, err = getProof(stub, args)
	case "GetProofsByAggregate":
		payload, err = getProofsByAggregate(stub, args)
	default:
		return shim.Error(errInvalid + ": hàm không tồn tại " + function)
	}
	if err != nil {
		return shim.Error(err.Error())
	}
	return shim.Success(payload)
}

func main() {
	ccid, err := chaincodeID()
	if err != nil {
		log.Fatalf("finora-ledger: %v", err)
	}
	address := os.Getenv("CHAINCODE_SERVER_ADDRESS")
	if address == "" {
		address = "0.0.0.0:9999"
	}
	writers := parseWriterMSPs(os.Getenv("FINORA_PROOF_WRITER_MSPIDS"))
	server := &shim.ChaincodeServer{
		CCID:    ccid,
		Address: address,
		CC:      &ProofLedgerChaincode{writers: writers},
		// Kết nối peer → chaincode chỉ đi trong mạng Docker nội bộ, cổng 9999 không mở ra host.
		TLSProps: shim.TLSProperties{Disabled: true},
	}
	log.Printf("finora-ledger: CCaaS lắng nghe %s, MSP được ghi: %s", address, strings.Join(writerList(writers), ","))
	if err := server.Start(); err != nil {
		log.Fatalf("finora-ledger: server dừng: %v", err)
	}
}

// chaincodeID đọc package ID từ biến môi trường hoặc từ file do script triển khai sinh sau khi install.
func chaincodeID() (string, error) {
	if value := strings.TrimSpace(os.Getenv("CHAINCODE_ID")); value != "" {
		return value, nil
	}
	path := os.Getenv("CHAINCODE_ID_FILE")
	if path == "" {
		return "", errMissingChaincodeID
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return "", errMissingChaincodeID
	}
	value := strings.TrimSpace(string(content))
	if value == "" {
		return "", errMissingChaincodeID
	}
	return value, nil
}

func writerList(writers map[string]bool) []string {
	list := make([]string, 0, len(writers))
	for msp := range writers {
		list = append(list, msp)
	}
	return list
}
