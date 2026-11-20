#!/bin/bash
# Stops the Elco poller started with start.sh
cd "$(dirname "$0")"
if [ -f poller.pid ] && kill -0 "$(cat poller.pid)" 2>/dev/null; then
  kill "$(cat poller.pid)" && echo "Stopped (PID $(cat poller.pid))"
else
  echo "Not running"
fi
rm -f poller.pid
