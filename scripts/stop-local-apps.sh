#!/bin/bash
# Stop both consumer and producer gradle processes

set -e

echo "🛑 Stopping local gradle processes..."

# Try killing by process pattern (most reliable)
if pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun' 2>/dev/null; then
    echo "  ✓ Stopped consumer"
else
    echo "  ℹ Consumer was not running (via process pattern)"
fi

if pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun' 2>/dev/null; then
    echo "  ✓ Stopped producer"
else
    echo "  ℹ Producer was not running (via process pattern)"
fi

# Also try killing by port to catch any lingering processes
if kill $(lsof -tiTCP:7000 -sTCP:LISTEN) 2>/dev/null; then
    echo "  ✓ Freed port 7000"
else
    echo "  ℹ Port 7000 was already free"
fi

if kill $(lsof -tiTCP:7001 -sTCP:LISTEN) 2>/dev/null; then
    echo "  ✓ Freed port 7001"
else
    echo "  ℹ Port 7001 was already free"
fi
if kill $(lsof -tiTCP:7002 -sTCP:LISTEN) 2>/dev/null; then
    echo "  ✓ Freed port 7002"
else
    echo "  ℹ Port 7002 was already free"
fi

echo ""
echo "✓ Done! Apps stopped successfully"
echo ""
echo "Next: docker compose -f docker-compose.dev.yml down"

