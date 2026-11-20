#!/bin/bash
# Starts the Elco poller + dashboard in the background (only one instance).
# Log: poller.log   PID: poller.pid
cd "$(dirname "$0")"
if [ -f poller.pid ] && kill -0 "$(cat poller.pid)" 2>/dev/null; then
  echo "Already running (PID $(cat poller.pid)). Use ./stop.sh first."
  exit 1
fi
nohup java -Xmx256m -jar elco-poller.jar elco.properties >> poller.log 2>&1 &
echo $! > poller.pid
sleep 3
if kill -0 "$(cat poller.pid)" 2>/dev/null; then
  echo "Started (PID $(cat poller.pid)). Log: $(pwd)/poller.log"
  grep -m1 "Dashboard:" <(tail -n 20 poller.log) || true
else
  echo "Failed to start - see poller.log:"; tail -n 20 poller.log; rm -f poller.pid; exit 1
fi
