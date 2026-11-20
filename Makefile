SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help
ID ?= FX-001

.PHONY: help doctor check verify task pair review dry-run install build test lint web-dev api-run infra-up infra-down infra-logs
help:
	@printf 'make install                 Install frontend dependencies (npm ci)\n'
	@printf 'make build                   Build frontend and backend\n'
	@printf 'make test                    Run frontend and backend tests\n'
	@printf 'make lint                    Frontend lint + typecheck, backend compile checks\n'
	@printf 'make web-dev                 Run Next.js dev server (SIMULATION mode)\n'
	@printf 'make api-run                 Run Spring Boot API (SIMULATION mode)\n'
	@printf 'make infra-up                Start local PostgreSQL and Redis\n'
	@printf 'make infra-down              Stop local PostgreSQL and Redis\n'
	@printf 'make infra-logs              Follow local infrastructure logs\n'
	@printf 'make doctor                  Check CLI and local prerequisites\n'
	@printf 'make check                   Run offline harness regression tests\n'
	@printf 'make task ID=FX-001          Create a new clean Git feature branch\n'
	@printf 'make dry-run ID=FX-001       Show planned pair-programming actions\n'
	@printf 'make pair ID=FX-001          Claude implement + Codex review + repair\n'
	@printf 'make review ID=FX-001        Review uncommitted changes without implementation\n'
	@printf 'make verify                  Check scaffold + builds + tests\n'
doctor:
	@bash scripts/doctor.sh
check:
	@bash scripts/verify.sh --tooling-only
verify:
	@bash scripts/verify.sh --require-scaffold
task:
	@bash scripts/new-task.sh "$(ID)"
dry-run:
	@bash scripts/pair.sh "$(ID)" --dry-run
pair:
	@bash scripts/pair.sh "$(ID)"
review:
	@bash scripts/pair.sh "$(ID)" --skip-implement --fix-rounds 0

# --- FX-001 application scaffold ---------------------------------------------
install:
	npm --prefix apps/web ci
build:
	npm --prefix apps/web run build
	cd services/api && ./mvnw -B -ntp -DskipTests package
test:
	npm --prefix apps/web test
	cd services/api && ./mvnw -B -ntp test
lint:
	npm --prefix apps/web run lint
	npm --prefix apps/web run typecheck
	cd services/api && ./mvnw -B -ntp validate compile
web-dev:
	npm --prefix apps/web run dev
api-run:
	cd services/api && ./mvnw -B -ntp spring-boot:run

# --- FX-002 local infrastructure ---------------------------------------------
infra-up:
	docker compose up -d
infra-down:
	docker compose down
infra-logs:
	docker compose logs -f postgres redis
