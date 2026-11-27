#!/bin/sh
# Stands in for `codex app-server`: newline JSON-RPC without the jsonrpc field. Appends every line it reads to
# $FAKE_CODEX_LOG, answers initialize, skills/extraRoots/set, thread/start (thr-1, then thr-2, ...), thread/resume,
# thread/fork, turn/start, thread/compact/start (a contextCompaction item and thread/compacted follow) and thread/read
# (one stored turn: a prompt, a command, an answer); replays two item lines of the real transcript at
# $FAKE_CODEX_REPLAY (codex/turn.jsonl) in each turn, then completes it. $FAKE_CODEX_MODE changes that:
#   request  asks the client for an approval before it completes the turn, which it does on the client's answer
#   hold     completes the turn only on turn/interrupt
#   error    answers thread/start and thread/resume with an error
#   crash    writes to stderr and exits 3 on turn/start
#   noskills answers skills/extraRoots/set with an error
#   nocompact answers thread/compact/start with an error
#   noread   answers thread/read with an error
log=${FAKE_CODEX_LOG:-/dev/null}
mode=${FAKE_CODEX_MODE:-}
thread=thr-1
threads=0
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
    *'"method":"skills/extraRoots/set"'*)
      if [ "$mode" = noskills ]; then
        printf '{"id":%s,"error":{"code":-32601,"message":"unknown method"}}\n' "$id"
      else
        printf '{"id":%s,"result":{}}\n' "$id"
      fi ;;
    *'"method":"thread/start"'*|*'"method":"thread/resume"'*|*'"method":"thread/fork"'*)
      case "$line" in
        *'"method":"thread/start"'*) threads=$((threads+1)); thread="thr-$threads" ;;
      esac
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
        # the recorded items, on this fake's thread
        sed -n '15,16p' "$FAKE_CODEX_REPLAY" | sed 's/"threadId":"[^"]*"/"threadId":"'"$thread"'"/g'
      fi
      case "$mode" in
        request)
          printf '{"id":"srv-%s","method":"item/commandExecution/requestApproval",%s}\n' "$n" \
            '"params":{"threadId":"'"$thread"'","turnId":"'"$turn"'","command":"touch /tmp/x"}' ;;
        hold) ;;
        spawn|spawnhold)
          spawned='{"type":"subAgentActivity","id":"call-1","kind":"started","agentThreadId":"thr-child","agentPath":"/root/pong","model":"gpt-6.2-sol"}'
          printf '{"method":"item/started","params":{"threadId":"%s","item":%s}}\n' "$thread" "$spawned"
          printf '{"method":"item/completed","params":{"threadId":"%s","item":%s}}\n' "$thread" "$spawned"
          printf '{"method":"turn/started","params":{"threadId":"thr-child","turn":{"id":"ct-1"}}}\n'
          printf '{"method":"item/completed","params":{"threadId":"thr-child","item":%s}}\n' \
            '{"type":"agentMessage","id":"cm-1","text":"PONG"}'
          printf '{"method":"turn/completed","params":{"threadId":"thr-child","turn":{"id":"ct-1","status":"completed"}}}\n'
          if [ "$mode" = spawn ]; then
            printf '{"method":"item/completed","params":{"threadId":"%s","item":%s}}\n' "$thread" \
              '{"type":"subAgentActivity","id":"done-1","kind":"completed","agentThreadId":"thr-child","agentPath":"/root/pong"}'
          fi
          completed completed ;;
        foreign)
          printf '{"method":"item/completed","params":{"threadId":"thr-gone","item":%s}}\n' \
            '{"type":"contextCompaction","id":"cmp-old"}'
          completed completed ;;
        *) completed completed ;;
      esac ;;
    *'"method":"thread/compact/start"'*)
      if [ "$mode" = nocompact ]; then
        printf '{"id":%s,"error":{"code":-32600,"message":"thread not found: %s"}}\n' "$id" "$thread"
      else
        printf '{"id":%s,"result":{}}\n' "$id"
        printf '{"method":"item/started","params":{"threadId":"%s","item":%s}}\n' "$thread" \
          '{"type":"contextCompaction","id":"cmp-1"}'
        printf '{"method":"item/completed","params":{"threadId":"%s","item":%s}}\n' "$thread" \
          '{"type":"contextCompaction","id":"cmp-1"}'
        printf '{"method":"thread/compacted","params":{"threadId":"%s","turnId":"cmp"}}\n' "$thread"
      fi ;;
    *'"method":"thread/read"'*)
      if [ "$mode" = noread ]; then
        printf '{"id":%s,"error":{"code":-32600,"message":"thread not found"}}\n' "$id"
      elif [ "$mode" = spawnread ]; then
        case "$line" in
          *thr-child*)
            printf '{"id":%s,"result":{"thread":{"id":"thr-child","turns":[{"id":"ct-1","status":"completed","items":[%s,%s]}]}}}\n' \
              "$id" '{"type":"userMessage","id":"cu-1","content":[{"type":"text","text":"say PONG"}]}' \
              '{"type":"agentMessage","id":"cm-1","text":"PONG stored"}' ;;
          *)
            printf '{"id":%s,"result":{"thread":{"id":"thr-old","turns":[{"id":"t-1","status":"completed","items":[%s]}]}}}\n' \
              "$id" '{"type":"subAgentActivity","id":"call-1","kind":"started","agentThreadId":"thr-child","agentPath":"/root/pong"}' ;;
        esac
      else
        printf '{"id":%s,"result":{"thread":{"id":"thr-old","turns":[{"id":"t-1","status":"completed",%s,"items":[%s,%s,%s]}]}}}\n' \
          "$id" '"durationMs":1200,"completedAt":1795761749' \
          '{"type":"userMessage","id":"u-1","content":[{"type":"text","text":"list the files"}]}' \
          '{"type":"commandExecution","id":"c-1","command":"ls","cwd":"/w","status":"completed","aggregatedOutput":"a\nb","exitCode":0}' \
          '{"type":"agentMessage","id":"m-1","text":"Two files."}'
      fi ;;
    *'"method":"turn/interrupt"'*)
      printf '{"id":%s,"result":{}}\n' "$id"
      completed interrupted ;;
    *'"id":"srv-'*)
      completed completed ;;
  esac
done
exit 0
