#!/usr/bin/env bash
# Wrapper to run the overbooking JMeter test from CLI.
# Usage:
#   1. Start the Spring Boot app:  cd ..; mvn spring-boot:run
#   2. Create a concert and copy the concertId:
#        curl -X POST http://localhost:8080/concerts \
#          -H 'Content-Type: application/json' \
#          -d '{"name":"Overbooking Test","capacity":100}'
#   3. ./run-overbooking-test.sh <concertId>
#   4. Inspect ./results/summary.jtl and ./results/report/index.html

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <concertId>"
  exit 1
fi

CONCERT_ID="$1"
RESULTS_DIR="./results"
JTL="${RESULTS_DIR}/summary.jtl"
REPORT_DIR="${RESULTS_DIR}/report"

rm -rf "${RESULTS_DIR}"
mkdir -p "${RESULTS_DIR}"

jmeter -n \
  -t booking-overbooking.jmx \
  -JCONCERT_ID="${CONCERT_ID}" \
  -l "${JTL}" \
  -e -o "${REPORT_DIR}"

echo ""
echo "Done. Open ${REPORT_DIR}/index.html in your browser."
echo ""
echo "Quick check (response code counts):"
awk -F',' 'NR>1 {print $4}' "${JTL}" | sort | uniq -c
echo ""
echo "Then verify invariants via:"
echo "  curl http://localhost:8080/concerts/${CONCERT_ID}/stats | jq ."
