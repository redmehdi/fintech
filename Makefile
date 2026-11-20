SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help
ID ?= FX-001

.PHONY: help doctor check verify task pair review dry-run
help:
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
