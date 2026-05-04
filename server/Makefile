.PHONY: doctor build run clean test proto migrate up down

ROOT := $(shell pwd)
DEV_DIR := $(ROOT)/.dev
BIN_DIR := $(DEV_DIR)/bin

SERVICES := gateway api auth asset signaling worker devserver

doctor:
	@./scripts/ensure-go.sh

build: $(addprefix build-,$(SERVICES))

build-%:
	@mkdir -p $(BIN_DIR)
	@echo "→ build $*"
	@cd cmd/$* && go build -o $(BIN_DIR)/gomob-$* .

run-%:
	@$(BIN_DIR)/gomob-$*

clean:
	@rm -rf $(DEV_DIR)/bin

test:
	@go test ./...

up:
	@docker compose -f configs/dev/docker-compose.yml up -d

down:
	@docker compose -f configs/dev/docker-compose.yml down

migrate:
	@./scripts/migrate.sh up

proto:
	@./scripts/proto-gen.sh
