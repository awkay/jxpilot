# Shared helpers for the xpilot-ref host scripts.  Sourced, not executed.

XPREF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XPILOT_SRC="${XPILOT_SRC:-/Users/tonykay/Downloads/xpilot-4.5.5}"
XPREF_IMAGE="${XPREF_IMAGE:-xpilot-ref:4.5.5}"

if [ -z "${DOCKER:-}" ]; then
    if command -v docker > /dev/null 2>&1; then
        DOCKER=docker
    elif [ -x /Users/tonykay/.docker/bin/docker ]; then
        DOCKER=/Users/tonykay/.docker/bin/docker
    elif [ -x /usr/local/bin/docker ]; then
        DOCKER=/usr/local/bin/docker
    else
        echo "docker CLI not found; set DOCKER=/path/to/docker" >&2
        exit 1
    fi
fi

die() { echo "$(basename "$0"): $*" >&2; exit 1; }

xpref_require_image() {
    "$DOCKER" image inspect "$XPREF_IMAGE" > /dev/null 2>&1 \
        || die "image $XPREF_IMAGE not found; run $XPREF_DIR/build.sh first"
}

# Unique container name, always with the xpilot-ref prefix.
xpref_container_name() {
    echo "xpilot-ref-$1-$$-$RANDOM"
}

# xpref_run_deadline SECONDS CONTAINER_NAME docker-run-args...
#
# Runs `docker run --rm --name CONTAINER_NAME ...` in the background and
# waits at most SECONDS.  On expiry it stops exactly that container (by its
# name) and the docker CLI process this function started (by PID).  Nothing
# else is ever killed.  Returns the docker exit code, or 124 on timeout.
xpref_run_deadline() {
    local secs="$1" cname="$2"
    shift 2
    case "$cname" in
        xpilot-ref-*) ;;
        *) die "refusing to run container without xpilot-ref prefix: $cname";;
    esac
    "$DOCKER" run --rm --name "$cname" "$@" &
    local pid=$! waited=0
    while kill -0 "$pid" 2> /dev/null; do
        if [ "$waited" -ge "$((secs * 10))" ]; then
            echo "$(basename "$0"): host deadline ${secs}s reached; stopping $cname" >&2
            "$DOCKER" stop -t 2 "$cname" > /dev/null 2>&1
            sleep 1
            kill "$pid" 2> /dev/null
            wait "$pid" 2> /dev/null
            return 124
        fi
        sleep 0.1
        waited=$((waited + 1))
    done
    wait "$pid"
}

# Host temp dir for container output (bind mounted at /out).
xpref_tmpdir() {
    local d
    d="$(mktemp -d "${TMPDIR:-/tmp}/xpilot-ref.XXXXXX")" || die "mktemp failed"
    chmod 777 "$d"
    echo "$d"
}
