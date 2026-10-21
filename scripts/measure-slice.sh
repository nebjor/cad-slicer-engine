#!/usr/bin/env bash
#
# measure-slice.sh: how much does one quote cost the host?
#
# Slices MODEL several times with the Bambu Studio CLI exactly as the backend does
# (SlicerExecutionService.executeSlicingQuote: --slice 0, the flattened machine and process
# profiles, the flattened filament profile, --clone-objects/--arrange for more than one copy)
# and prints wall-clock, CPU time and peak resident memory per run as a Markdown table, once
# for each requested quantity. The numbers size the server, the slicer timeout and the
# container memory limit (docs/feasibility/bambu-studio-cli-headless.md, section 6).
#
# Profiles: the CLI applies only the keys of the file it is given, so the backend flattens the
# profile inheritance chain first (SlicerProfileResolver) into
# $TMPDIR/slicer-profiles-*/{machine,process,filament}/<name>.json. This script reuses those
# files, which guarantees it measures what a quote really runs, supports and bed type included.
# If none exist yet it asks the running backend for one quote of MODEL (POST /api/quotes), which
# makes the resolver write them, and looks again. Pass -p to point at a directory of flattened
# profiles explicitly, for example one copied out of a container.
#
# Measurement: GNU time (/usr/bin/time -v) when installed, which reports exact rusage figures.
# Without it the script falls back to bash's own timing for wall-clock and CPU (the "times"
# builtin) and samples VmHWM from /proc every 0.2 s for the CLI process and its children, which
# can miss a very short peak. The report states which method was used.
#
# Usage:
#   scripts/measure-slice.sh [-n RUNS] [-q "1 4"] [-Q medium] [-F PLA] [-p PROFILE_DIR] MODEL
#
#   -n RUNS         runs per quantity (default 3)
#   -q QUANTITIES   space-separated copies to measure (default "1 4")
#   -Q QUALITY      coarse | medium | high, the quote quality tier (default medium)
#   -F FILAMENT     PLA | PETG | PCTG, the quote filament (default PLA)
#   -p PROFILE_DIR  directory with flattened profiles; default: newest $TMPDIR/slicer-profiles-*
#
# Environment:
#   SLICER_BINARY_PATH      the CLI, as for the backend (default bambu-studio-cli)
#   BACKEND_URL             backend used for the warm-up quote (default http://localhost:8080)
#   SLICER_MACHINE_PROFILE  machine profile name (default "Bambu Lab A1 0.4 nozzle")
#   TMPDIR                  where the backend writes flattened profiles and this script its scratch
#
# Development stack (pod-frontend repository; the backend container has Maven and the CLI):
#   docker compose -f docker-compose.local.yml exec backend \
#       scripts/measure-slice.sh -n 3 /workspace/public/3DBenchy.stl
#
# Production host (the image holds only the jar, so copy the script and a model in first):
#   docker compose cp scripts/measure-slice.sh slicer-engine:/tmp/measure-slice.sh
#   docker compose cp model.stl slicer-engine:/tmp/model.stl
#   docker compose exec slicer-engine bash /tmp/measure-slice.sh -n 3 /tmp/model.stl
#
set -euo pipefail

RUNS=3
QUANTITIES="1 4"
QUALITY=medium
FILAMENT=PLA
PROFILE_DIR=""
BACKEND_URL=${BACKEND_URL:-http://localhost:8080}
MACHINE_PROFILE=${SLICER_MACHINE_PROFILE:-Bambu Lab A1 0.4 nozzle}
SCRATCH=${TMPDIR:-/tmp}

usage() {
    sed -n '3,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}
die() {
    echo "measure-slice: $*" >&2
    exit 1
}

while getopts ":n:q:Q:F:p:h" opt; do
    case $opt in
        n) RUNS=$OPTARG ;;
        q) QUANTITIES=$OPTARG ;;
        Q) QUALITY=$OPTARG ;;
        F) FILAMENT=$OPTARG ;;
        p) PROFILE_DIR=$OPTARG ;;
        h) usage 0 ;;
        *) usage 2 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -eq 1 ]] || usage 2
MODEL=$1
[[ -f $MODEL ]] || die "model not found: $MODEL"
[[ $RUNS =~ ^[1-9][0-9]*$ ]] || die "-n expects a positive number"

# Same mapping as PrintQuality and FilamentType in the backend.
case $QUALITY in
    coarse) PROCESS_PROFILE="0.24mm Draft @BBL A1" ;;
    medium) PROCESS_PROFILE="0.20mm Standard @BBL A1" ;;
    high)   PROCESS_PROFILE="0.12mm Fine @BBL A1" ;;
    *) die "unknown quality $QUALITY (coarse, medium, high)" ;;
esac
case ${FILAMENT^^} in
    PLA)  FILAMENT=PLA;  FILAMENT_PROFILE="Bambu PLA Basic @BBL A1" ;;
    PETG) FILAMENT=PETG; FILAMENT_PROFILE="PolyLite PETG @BBL A1" ;;
    PCTG) FILAMENT=PCTG; FILAMENT_PROFILE="Generic PCTG @BBL A1" ;;
    *) die "unknown filament $FILAMENT (PLA, PETG, PCTG)" ;;
esac

# The backend splits slicer.binary-path on whitespace; do the same.
read -r -a BINARY <<< "${SLICER_BINARY_PATH:-bambu-studio-cli}"
command -v "${BINARY[0]}" >/dev/null 2>&1 || die "slicer CLI not found: ${BINARY[0]} (set SLICER_BINARY_PATH)"

# ---- flattened profiles ------------------------------------------------------------------------

has_profiles() {
    [[ -f "$1/machine/$MACHINE_PROFILE.json" && -f "$1/process/$PROCESS_PROFILE.json" \
        && -f "$1/filament/$FILAMENT_PROFILE.json" ]]
}

# Newest directory the backend wrote that holds all three profiles for this quality/filament.
find_profiles() {
    local dir
    while IFS= read -r dir; do
        if has_profiles "$dir"; then
            echo "$dir"
            return 0
        fi
    done < <(ls -dt "$SCRATCH"/slicer-profiles-* 2>/dev/null || true)
    return 1
}

warm_up() {
    echo "No flattened profiles for $QUALITY/$FILAMENT under $SCRATCH; requesting one quote from $BACKEND_URL" >&2
    command -v curl >/dev/null 2>&1 || die "curl is needed for the warm-up quote; or pass -p PROFILE_DIR"
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' -F "file=@$MODEL" -F "filament=$FILAMENT" \
        -F "quality=$QUALITY" -F quantity=1 "$BACKEND_URL/api/quotes") || code=000
    [[ $code == 200 ]] || die "warm-up quote answered HTTP $code; is the backend up in this container? Otherwise pass -p PROFILE_DIR"
}

if [[ -n $PROFILE_DIR ]]; then
    has_profiles "$PROFILE_DIR" || die "$PROFILE_DIR lacks machine/process/filament profiles for $QUALITY/$FILAMENT"
elif ! PROFILE_DIR=$(find_profiles); then
    warm_up
    PROFILE_DIR=$(find_profiles) \
        || die "backend answered but wrote no profiles under $SCRATCH (different TMPDIR?); pass -p PROFILE_DIR"
fi

# ---- timing method -----------------------------------------------------------------------------

GNU_TIME=""
for candidate in /usr/bin/time "$(type -P time 2>/dev/null || true)"; do
    if [[ -n $candidate && -x $candidate ]] && "$candidate" --version 2>&1 | grep -qi 'GNU'; then
        GNU_TIME=$candidate
        break
    fi
done
if [[ -n $GNU_TIME ]]; then
    METHOD="GNU time ($GNU_TIME -v): exact rusage of the CLI process tree"
else
    METHOD="fallback: bash timing for wall/CPU, peak RSS sampled from /proc VmHWM every 0.2 s (install the time package for exact figures)"
fi

# "1:02.50" or "1:02:03" to seconds
clock_to_seconds() {
    awk -F: '{ if (NF == 3) print $1 * 3600 + $2 * 60 + $3; else print $1 * 60 + $2 }' <<< "$1"
}

# "0m12.345s" to seconds
bash_time_to_seconds() {
    awk -F'[ms]' '{ print $1 * 60 + $2 }' <<< "$1"
}

# Sum of VmHWM (kB) over a pid and its direct children, read without forking.
tree_hwm_kb() {
    local root=$1 status key value pid ppid hwm total=0
    for status in /proc/[0-9]*/status; do
        pid=${status#/proc/}
        pid=${pid%/status}
        ppid=""
        hwm=0
        # A process may exit between the glob and the read; the group hides that error.
        {
            while read -r key value _; do
                case $key in
                    PPid:) ppid=$value ;;
                    VmHWM:) hwm=$value ;;
                esac
            done < "$status"
        } 2>/dev/null || continue
        if [[ $pid == "$root" || $ppid == "$root" ]]; then
            total=$(( total + hwm ))
        fi
    done
    echo "$total"
}

# ---- one run -----------------------------------------------------------------------------------

# Sets WALL, CPU, RSS_MB, EXIT, PLATES and PREDICTED for one slice of $1 copies.
run_once() {
    local qty=$1 out
    out=$(mktemp -d "$SCRATCH/measure-slice-XXXXXX")
    local cmd=("${BINARY[@]}" --slice 0
        --load-settings "$PROFILE_DIR/machine/$MACHINE_PROFILE.json;$PROFILE_DIR/process/$PROCESS_PROFILE.json"
        --load-filaments "$PROFILE_DIR/filament/$FILAMENT_PROFILE.json")
    if (( qty > 1 )); then
        cmd+=(--clone-objects "$qty" --arrange 1)
    fi
    cmd+=(--outputdir "$out" "$MODEL")

    if [[ -n $GNU_TIME ]]; then
        set +e
        "$GNU_TIME" -v -o "$out/time.txt" "${cmd[@]}" > "$out/cli.log" 2>&1
        EXIT=$?
        set -e
        local user sys rss_kb
        WALL=$(clock_to_seconds "$(awk -F'): ' '/Elapsed \(wall clock\)/ { print $2 }' "$out/time.txt")")
        user=$(awk -F': ' '/User time/ { print $2 }' "$out/time.txt")
        sys=$(awk -F': ' '/System time/ { print $2 }' "$out/time.txt")
        CPU=$(awk "BEGIN { printf \"%.1f\", $user + $sys }")
        rss_kb=$(awk -F': ' '/Maximum resident set size/ { print $2 }' "$out/time.txt")
        RSS_MB=$(( rss_kb / 1024 ))
    else
        local before after start end peak=0 sample pid
        times > "$out/times-before.txt"
        start=$(date +%s.%N)
        set +e
        "${cmd[@]}" > "$out/cli.log" 2>&1 &
        pid=$!
        while kill -0 "$pid" 2>/dev/null; do
            sample=$(tree_hwm_kb "$pid")
            (( sample > peak )) && peak=$sample
            sleep 0.2
        done
        wait "$pid"
        EXIT=$?
        set -e
        end=$(date +%s.%N)
        times > "$out/times-after.txt"
        WALL=$(awk "BEGIN { print $end - $start }")
        # The second line of "times" holds the user and system time of waited-for children,
        # cumulative for this shell, so the run's share is the difference.
        before=$(sed -n 2p "$out/times-before.txt")
        after=$(sed -n 2p "$out/times-after.txt")
        CPU=$(awk "BEGIN { printf \"%.1f\", \
            ($(bash_time_to_seconds "${after%% *}") + $(bash_time_to_seconds "${after##* }")) \
            - ($(bash_time_to_seconds "${before%% *}") + $(bash_time_to_seconds "${before##* }")) }")
        RSS_MB=$(( peak / 1024 ))
    fi
    WALL=$(awk "BEGIN { printf \"%.1f\", $WALL }")

    PLATES=$(find "$out" -maxdepth 1 -name 'plate_*.gcode' | wc -l | tr -d ' ')
    if [[ -f $out/result.json ]]; then
        PREDICTED=$(grep -o '"total_predication": *[0-9.]*' "$out/result.json" \
            | awk -F': *' '{ s += $2 } END { printf "%.0f", s }')
    else
        PREDICTED="n/a"
    fi
    if (( EXIT != 0 )); then
        echo "run failed with exit $EXIT; last CLI output:" >&2
        tail -n 5 "$out/cli.log" >&2 || true
    fi
    rm -rf "$out"
}

# ---- report ------------------------------------------------------------------------------------

model_mb=$(awk "BEGIN { printf \"%.1f\", $(stat -c %s "$MODEL") / 1048576 }")
cpus=$(nproc 2>/dev/null || echo "?")
mem_limit="unlimited"
for f in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory/memory.limit_in_bytes; do
    if [[ -r $f ]]; then
        v=$(cat "$f")
        [[ $v =~ ^[0-9]+$ ]] && mem_limit="$(( v / 1048576 )) MB"
        break
    fi
done

echo "### Slice measurement $(date -u +%Y-%m-%dT%H:%MZ)"
echo
echo "- Model: $(basename "$MODEL") ($model_mb MB)"
echo "- Profiles: $MACHINE_PROFILE / $PROCESS_PROFILE / $FILAMENT_PROFILE (flattened, from $PROFILE_DIR)"
echo "- CLI: ${BINARY[*]}; host: $(uname -m), $cpus CPU(s), container memory limit $mem_limit"
echo "- Method: $METHOD"
echo
echo "| quantity | run | wall s | CPU s | peak RSS MB | plates | predicted print s | exit |"
echo "|---|---|---|---|---|---|---|---|"
for qty in $QUANTITIES; do
    [[ $qty =~ ^[1-9][0-9]*$ ]] || die "quantity must be a positive number: $qty"
    wall_max=0
    wall_sum=0
    cpu_max=0
    rss_max=0
    for (( run = 1; run <= RUNS; run++ )); do
        run_once "$qty"
        echo "| $qty | $run | $WALL | $CPU | $RSS_MB | $PLATES | $PREDICTED | $EXIT |"
        wall_max=$(awk "BEGIN { print ($WALL > $wall_max) ? $WALL : $wall_max }")
        wall_sum=$(awk "BEGIN { print $wall_sum + $WALL }")
        cpu_max=$(awk "BEGIN { print ($CPU > $cpu_max) ? $CPU : $cpu_max }")
        (( RSS_MB > rss_max )) && rss_max=$RSS_MB
    done
    wall_mean=$(awk "BEGIN { printf \"%.1f\", $wall_sum / $RUNS }")
    echo "| $qty | max / mean | $wall_max / $wall_mean | $cpu_max | $rss_max | | | |"
done
