# syntax=docker/dockerfile:1

FROM golang:1.21-alpine AS builder
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/bonding-server ./cmd/bonding-server

FROM gcr.io/distroless/base-debian12
COPY --from=builder /out/bonding-server /bonding-server
EXPOSE 443 8080
ENV LISTEN_ADDR=:443
ENV METRICS_ADDR=:8080
ENTRYPOINT ["/bonding-server"]
