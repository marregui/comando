#!/bin/sh
# Stands in for `claude -p` that started a child which ignores SIGTERM (an MCP server with a trap): the child's pid
# goes to child.pid in the working directory, then the script answers like fake-claude.sh and dies on SIGTERM.
(trap '' TERM HUP; exec sleep 61) &
echo $! > child.pid.tmp && mv child.pid.tmp child.pid
while IFS= read -r line; do
  :
done
exit 0
