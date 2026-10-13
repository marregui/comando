#!/bin/sh
# Stands in for `claude -p` in stream-JSON mode: answers each user line with init, one text block and a result.
n=0
while IFS= read -r line; do
  case "$line" in
    *'"control_request"'*)
      printf '%s\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
    *'"type":"user"'*)
      n=$((n+1))
      printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-1","model":"fake-model","cwd":"'"$PWD"'","claude_code_version":"0.0.1","permissionMode":"default","capabilities":["interrupt_receipt_v1"]}'
      printf '%s\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"text","text":"pong '$n'"}],"usage":{"input_tokens":10,"cache_read_input_tokens":90}}}'
      printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01,"duration_ms":5,"num_turns":1,"queued_turn_count":0,"usage":{"input_tokens":10,"cache_read_input_tokens":90}}'
      ;;
  esac
done
exit 0
