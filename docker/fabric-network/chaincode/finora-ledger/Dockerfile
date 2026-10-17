# Chaincode finora-ledger chạy dạng chaincode-as-a-service (W3D, D51). Build kiểm vet + unit test trước khi đóng gói;
# image chạy chỉ chứa binary tĩnh và user không phải root.
FROM golang:1.26.7-alpine3.24 AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-mod=readonly
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN go vet ./... && go test -count=1 ./... && go build -trimpath -ldflags="-s -w" -o /out/finora-ledger .

FROM alpine:3.24.1
RUN addgroup -S chaincode && adduser -S -G chaincode chaincode
COPY --from=build /out/finora-ledger /usr/local/bin/finora-ledger
USER chaincode
EXPOSE 9999
ENTRYPOINT ["/usr/local/bin/finora-ledger"]
