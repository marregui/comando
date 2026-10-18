#!/bin/sh
# Stands in for `claude -p` in stream-JSON mode with sub-agents: a prompt holding one command emits the events
# Claude Code 2.1.302 sends for it, then a result. agent:<id> starts an Agent call, done:<id> gives it its result,
# launch:<id> gives it the result of a launch into the background, notify:<id> ends that background task.
n=0
id_of() {
  printf '%s' "$1" | sed -n "s/.*$2:\([A-Za-z0-9_]*\).*/\1/p"
}
while IFS= read -r line; do
  case "$line" in
    *'"control_request"'*)
      printf '%s\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
    *'"type":"user"'*)
      n=$((n+1))
      printf '%s\n' '{"type":"system","subtype":"init","session_id":"fake-1","model":"fake-model","cwd":"'"$PWD"'","claude_code_version":"0.0.1","permissionMode":"default","capabilities":[]}'
      case "$line" in
        *'agent:'*)
          id=$(id_of "$line" agent)
          printf '%s\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"tool_use","id":"'"$id"'","name":"Agent","input":{"description":"d","prompt":"p"}}]}}' ;;
        *'done:'*)
          id=$(id_of "$line" done)
          printf '%s\n' '{"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"'"$id"'","content":[{"type":"text","text":"report"}]}]}}' ;;
        *'launch:'*)
          id=$(id_of "$line" launch)
          printf '%s\n' '{"type":"user","parent_tool_use_id":null,"message":{"content":[{"type":"tool_result","tool_use_id":"'"$id"'","content":[{"type":"text","text":"Async agent launched successfully.\nagentId: a1 (internal ID)\nThe agent is working in the background."}]}]}}' ;;
        *'notify:'*)
          id=$(id_of "$line" notify)
          printf '%s\n' '{"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"'"$id"'","status":"completed","output_file":"","summary":"d"}' ;;
      esac
      printf '%s\n' '{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0,"duration_ms":5,"num_turns":1,"queued_turn_count":0,"usage":{"input_tokens":10}}'
      ;;
  esac
done
exit 0
