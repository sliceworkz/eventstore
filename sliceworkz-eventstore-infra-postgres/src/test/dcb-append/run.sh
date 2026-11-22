#!/usr/bin/env bash
#
# Driver for the append-pipelining experiment (docs/design/dcb-single-round-trip-append.md). Runs every scenario
# of a scenario file in both modes, REPS times, against a dedicated PostgreSQL container, and leaves one
# directory of raw CSVs and summaries per run under OUT. Aggregate with aggregate.py.
#
#   run.sh <out-dir> <scenario-file> [reps]
#
# A scenario file has one scenario per line: a name and the harness arguments, e.g.
#   overhead-small  corpus=1000 writers=1
# Lines starting with # are ignored.
#
# Environment: CP (the harness classpath, see the design note), PG_IMAGE (default postgres:18),
# PG_CPUS / JVM_CPUS (cpusets; default 0-3 for the database, 4-11 for the JVMs), DELAY_US (one-way
# delay the run phase's connections go through, by DelayProxy on PROXY_CPUS, default 10-11, so give the JVMs 4-9 then; unset or
# 0 connects directly; init and verify always connect directly).
set -euo pipefail

OUT=${1:?out dir}
SCENARIOS=${2:?scenario file}
REPS=${3:-3}
PG_IMAGE=${PG_IMAGE:-postgres:18}
PG_CPUS=${PG_CPUS:-0-3}
JVM_CPUS=${JVM_CPUS:-4-11}
PG_NAME=dcbbench-pg
PG_PORT=55432
CP=${CP:?set CP to the harness classpath}
JAVA=${JAVA:-java}
MAIN=org.sliceworkz.eventstore.infra.postgres.DcbAppendBenchmark
JDBC="jdbc:postgresql://localhost:${PG_PORT}/bench"
DELAY_US=${DELAY_US:-0}
PROXY_CPUS=${PROXY_CPUS:-10-11}
PROXY_PORT=55433
RUN_JDBC="$JDBC"

mkdir -p "$OUT"
cp "$SCENARIOS" "$OUT/scenarios.txt"

if ! docker inspect "$PG_NAME" >/dev/null 2>&1; then
	docker run -d --name "$PG_NAME" --cpuset-cpus="$PG_CPUS" --memory=6g --shm-size=1g -p ${PG_PORT}:5432 \
		-e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench "$PG_IMAGE" \
		-c shared_buffers=1GB -c max_connections=300 -c max_wal_size=4GB >/dev/null
	until docker exec "$PG_NAME" pg_isready -U bench -d bench >/dev/null 2>&1; do sleep 1; done
	sleep 2
fi

{
	echo "date: $(date -u +%FT%TZ)"
	echo "commit: $(git rev-parse HEAD 2>/dev/null || echo unknown)"
	echo "pg_image: $PG_IMAGE ($(docker exec "$PG_NAME" postgres --version))"
	echo "pg_cpus: $PG_CPUS  jvm_cpus: $JVM_CPUS  host_cpus: $(nproc)  arch: $(uname -m)"
	echo "java: $($JAVA -version 2>&1 | head -1)"
	echo "reps: $REPS"
} > "$OUT/environment.txt"

if (( DELAY_US > 0 )); then
	taskset -c "$PROXY_CPUS" "$JAVA" -cp "$CP" org.sliceworkz.eventstore.infra.postgres.DelayProxy \
		"$PROXY_PORT" localhost "$PG_PORT" "$DELAY_US" > "$OUT/proxy.log" 2>&1 &
	PROXY_PID=$!
	trap 'kill $PROXY_PID 2>/dev/null' EXIT
	sleep 2
	RUN_JDBC="jdbc:postgresql://localhost:${PROXY_PORT}/bench"
	echo "delay_us: $DELAY_US (one way, through DelayProxy on cpus $PROXY_CPUS)" >> "$OUT/environment.txt"
fi

harness ( ) {
	taskset -c "$JVM_CPUS" "$JAVA" -Xms1g -Xmx1g -XX:+UseG1GC -cp "$CP" "$MAIN" jdbcUrl="$JDBC" "$@"
}

while read -r name args; do
	[[ -z "${name:-}" || "$name" == \#* ]] && continue
	instances=$(sed -n 's/.*instances=\([0-9]*\).*/\1/p' <<< "$args"); instances=${instances:-1}
	for rep in $(seq 1 "$REPS"); do
		if (( rep % 2 == 1 )); then modes="off single-round-trip"; else modes="single-round-trip off"; fi
		for mode in $modes; do
			run="${name}__${mode}__r${rep}"
			dir="$OUT/$run"
			if [[ -f "$dir/done" ]]; then continue; fi
			mkdir -p "$dir"
			echo "[$(date +%T)] $run"
			# shellcheck disable=SC2086
			harness phase=init instances="$instances" $args > "$dir/init.log" 2>&1
			pids=()
			for i in $(seq 0 $((instances - 1))); do
				# shellcheck disable=SC2086
				harness phase=run mode="$mode" instance="$i" instances="$instances" runId="$run" out="$dir" \
					cpuStatContainer="$PG_NAME" seed="$rep" $args jdbcUrl="$RUN_JDBC" > "$dir/run-i$i.log" 2>&1 &
				pids+=($!)
			done
			for pid in "${pids[@]}"; do wait "$pid"; done
			# shellcheck disable=SC2086
			harness phase=verify instances="$instances" runId="$run" out="$dir" $args > "$dir/verify.log" 2>&1 || {
				echo "  INVARIANT VIOLATION in $run, see $dir/verify.log"; }
			grep -h 'decisions/s' "$dir"/run-i*.log | sed 's/^/  /'
			grep -h 'verify:' "$dir/verify.log" | sed 's/^/  /'
			touch "$dir/done"
		done
	done
done < "$SCENARIOS"
