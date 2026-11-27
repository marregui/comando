#!/bin/sh
# Stands in for `codex app-server`: newline JSON-RPC without the jsonrpc field. Appends every line it reads to
# $FAKE_CODEX_LOG, answers initialize, thread/start, thread/resume, thread/fork and turn/start, replays two item lines of the real
# transcript at $FAKE_CODEX_REPLAY (codex/turn.jsonl) in each turn, then completes it. $FAKE_CODEX_MODE changes that:
#   request  asks the client for an approval before it completes the turn, which it does on the client's answer
#   hold     completes the turn only on turn/interrupt
#   error    answers thread/start and thread/resume with an error
#   crash    writes to stderr and exits 3 on turn/start
log=${FAKE_CODEX_LOG:-/dev/null}
mode=${FAKE_CODEX_MODE:-}
thread=thr-1
n=0
turn=
completed() {
  printf '{"method":"turn/completed","params":{"threadId":"%s","turn":{"id":"%s","status":"%s"}}}\n' \
    "$thread" "$turn" "$1"
}
while IFS= read -r line; do
  printf '%s\n' "$line" >> "$log"
  id=$(printf '%s\n' "$line" | sed -n 's/^{"id":\([0-9][0-9]*\),.*/\1/p')
  case "$line" in
    *'"method":"initialize"'*)
      printf '{"id":%s,"result":{"userAgent":"comando/0.172.0 (fake; 0)","platformOs":"macos"}}\n' "$id" ;;
    *'"method":"thread/start"'*|*'"method":"thread/resume"'*|*'"method":"thread/fork"'*)
      if [ "$mode" = error ]; then
        printf '{"id":%s,"error":{"code":-32600,"message":"no such thread"}}\n' "$id"
      else
        printf '{"id":%s,"result":{"thread":{"id":"%s"},"model":"gpt-6.2-sol","cwd":"%s",%s}}\n' \
          "$id" "$thread" "$PWD" '"approvalPolicy":"never","reasoningEffort":"medium"'
      fi ;;
    *'"method":"turn/start"'*)
      if [ "$mode" = crash ]; then
        echo "fake codex crashed" >&2
        exit 3
      fi
      n=$((n+1))
      turn="turn-$n"
      printf '{"id":%s,"result":{"turn":{"id":"%s","status":"inProgress"}}}\n' "$id" "$turn"
      printf '{"method":"turn/started","params":{"threadId":"%s","turn":{"id":"%s"}}}\n' "$thread" "$turn"
      if [ -n "$FAKE_CODEX_REPLAY" ]; then
        sed -n '15,16p' "$FAKE_CODEX_REPLAY"
      fi
      case "$mode" in
        request)
          printf '{"id":"srv-%s","method":"item/commandExecution/requestApproval",%s}\n' "$n" \
            '"params":{"threadId":"'"$thread"'","turnId":"'"$turn"'","command":"touch /tmp/x"}' ;;
        hold) ;;
        *) completed completed ;;
      esac ;;
    *'"method":"turn/interrupt"'*)
      printf '{"id":%s,"result":{}}\n' "$id"
      completed interrupted ;;
    *'"id":"srv-'*)
      completed completed ;;
  esac
done
exit 0
