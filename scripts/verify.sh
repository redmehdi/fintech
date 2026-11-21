#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_scaffold=0; tooling_only=0
for arg in "$@"; do
 case "$arg" in
   --require-scaffold) require_scaffold=1 ;;
   --tooling-only) tooling_only=1 ;;
   *) die "Unknown option: $arg" ;;
 esac
done
note 'Checking shell scripts and Python CLI harness tests'
for f in scripts/*.sh; do bash -n "$f"; done
python3 -m unittest discover -s tests -p 'test_*.py' -v
[[ "$tooling_only" == 1 ]] && exit 0
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
 git diff --check && git diff --cached --check
fi
if [[ -e apps/web/package.json ]]; then
 command -v npm >/dev/null || die 'npm not installed'
 [[ -d apps/web/node_modules ]] || die 'Frontend dependencies missing: run (cd apps/web && npm ci) or npm install for the initial lockfile'
 if [[ -f contracts/pom.xml ]]; then
  note 'Validating OpenAPI and generating the TypeScript client'
  make contract-generate
 fi
 note 'Frontend checks: test if present, lint, typecheck, build'
 npm --prefix apps/web run test --if-present
 npm --prefix apps/web run lint --if-present
 npm --prefix apps/web run typecheck --if-present
 npm --prefix apps/web run build
elif [[ "$require_scaffold" == 1 ]]; then
 die 'Missing apps/web/package.json (FX-001 scaffold incomplete)'
else note 'Skipping frontend checks: scaffold not yet created'; fi
if [[ -e services/api/pom.xml ]]; then
 if [[ -n "${FINTECH_MAVEN:-}" || -x services/api/mvnw ]]; then
   (cd services/api && "${FINTECH_MAVEN:-./mvnw}" -B -ntp verify)
 else
   command -v mvn >/dev/null || die 'Maven missing; add services/api/mvnw or install mvn'
   mvn -B -ntp -f services/api/pom.xml verify
 fi
elif [[ -e services/api/build.gradle ]] || [[ -e services/api/build.gradle.kts ]]; then
 if [[ -x services/api/gradlew ]]; then
   (cd services/api && ./gradlew --no-daemon test)
 else
   command -v gradle >/dev/null || die 'Gradle wrapper absent and gradle unavailable'
   (cd services/api && gradle test)
 fi
elif [[ "$require_scaffold" == 1 ]]; then
 die 'Missing services/api/pom.xml or Gradle build (FX-001 scaffold incomplete)'
else note 'Skipping backend checks: scaffold not yet created'; fi
note 'Checks passed (not a production security certification)'
