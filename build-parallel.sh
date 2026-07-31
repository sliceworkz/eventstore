#!/usr/bin/env bash
#
# Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
# Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Lesser General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
# ---------------------------------------------------------------------------------------------
#
# `mvn clean install` with the shared TCK run against every backend at the same time instead of one
# after the other.
#
#   ./build-parallel.sh                                   all four backends
#   BACKENDS="inmem postgres:18" ./build-parallel.sh       a subset (no Docker needed for inmem alone)
#   ./build-parallel.sh -o                                 extra arguments go to the build
#   TCK_ARGS="-Dtest=UpcastMultiTest" ./build-parallel.sh   extra arguments go to each backend leg
#
# Backend parallelism is one JVM per backend, never threads inside one: per-test isolation on
# Postgres is initializeDatabase() dropping and recreating the tables for the store's prefix, so two
# scenarios sharing a backend concurrently would drop each other's tables mid-test.
#
# Sharing one working tree across the legs means keeping two things apart by hand — hence the
# separate test-compile step (so nothing writes to target/test-classes concurrently) and the report
# suffix (so the legs do not overwrite each other's surefire reports).

set -euo pipefail

cd "$(dirname "$0")"

BACKENDS="${BACKENDS:-inmem inmem-fs postgres:17 postgres:18}"
TESTS_MODULE="sliceworkz-eventstore-tests"
LOG_DIR="${TESTS_MODULE}/target/parallel-logs"

echo "==> building and installing everything except ${TESTS_MODULE}"
mvn -B clean install "$@" -pl "!${TESTS_MODULE}"

echo "==> compiling the TCK runner once"
mvn -B test-compile -pl "${TESTS_MODULE}"

mkdir -p "${LOG_DIR}"
echo "==> running the TCK against: ${BACKENDS}"

pids=()
names=()
for backend in ${BACKENDS}; do
	safe="${backend//:/-}"
	mvn -B surefire:test -pl "${TESTS_MODULE}" \
		-Deventstore.testing.backends="${backend}" \
		-Dsurefire.reports.suffix="-${safe}" \
		${TCK_ARGS:-} \
		> "${LOG_DIR}/${safe}.log" 2>&1 &
	pids+=($!)
	names+=("${backend}")
done

failed=()
for i in "${!pids[@]}"; do
	if wait "${pids[$i]}"; then
		printf '    %-14s %s\n' "${names[$i]}" "$(grep -Eo 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "${LOG_DIR}/${names[$i]//:/-}.log" | tail -1)"
	else
		failed+=("${names[$i]}")
		printf '    %-14s FAILED\n' "${names[$i]}"
	fi
done

if [ ${#failed[@]} -gt 0 ]; then
	echo
	echo "==> failed on: ${failed[*]}"
	for backend in "${failed[@]}"; do
		echo
		echo "--- ${backend} ---"
		grep -E '^\[ERROR\]' "${LOG_DIR}/${backend//:/-}.log" | head -30
		echo "    full log: ${LOG_DIR}/${backend//:/-}.log"
	done
	exit 1
fi

echo "==> installing ${TESTS_MODULE}"
mvn -B install -DskipTests -pl "${TESTS_MODULE}"

echo "==> all backends green"
