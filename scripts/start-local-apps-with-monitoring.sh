#!/bin/bash
# Start WikiStream Consumer and Producer for Development Mode with Monitoring
#
# This script starts both apps in the background with proper port configuration
# for Prometheus scraping in development mode.
#
# Usage:
#   bash scripts/start-local-apps-with-monitoring.sh

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "🚀 Starting WikiStream apps for development mode with monitoring..."
echo ""

# Kill any existing instances
echo "🧹 Cleaning up existing processes..."
pkill -f 'gradle-wrapper.jar :cmd:consumer:bootRun' 2>/dev/null || true
pkill -f 'gradle-wrapper.jar :cmd:producer:bootRun' 2>/dev/null || true
sleep 2

# Create log directory
mkdir -p logs

# Start Consumer on port 7001
echo "📊 Starting Consumer on port 7001..."
SERVER_PORT=7001 \
APP_JWT_ISSUER=wikistream-local \
APP_JWT_SECRET=local-jwt-secret-at-least-32-characters-long \
APP_JWT_ACCESS_TOKEN_TTL_SECONDS=3600 \
APP_AUTH_ENABLED=true \
APP_SESSION_BACKEND=in-memory \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
CASSANDRA_CONTACT_POINTS=127.0.0.1 \
CASSANDRA_PORT=19042 \
CASSANDRA_KEYSPACE_NAME=wikistream \
CASSANDRA_LOCAL_DATACENTER=datacenter1 \
./gradlew :cmd:consumer:bootRun > logs/consumer.log 2>&1 &

CONSUMER_PID=$!
echo "   Consumer PID: $CONSUMER_PID"

# Start Producer on port 7002
echo "📡 Starting Producer on port 7002..."
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :cmd:producer:bootRun > logs/producer.log 2>&1 &

PRODUCER_PID=$!
echo "   Producer PID: $PRODUCER_PID"

echo ""
echo "⏳ Waiting for apps to start..."
sleep 10

# Check if apps are running
echo ""
echo "🔍 Verifying services..."

if lsof -i:7001 -sTCP:LISTEN > /dev/null 2>&1; then
  echo "   ✅ Consumer is running on port 7001"
else
  echo "   ❌ Consumer failed to start. Check logs/consumer.log"
fi

if lsof -i:7002 -sTCP:LISTEN > /dev/null 2>&1; then
  echo "   ✅ Producer is running on port 7002"
else
  echo "   ❌ Producer failed to start. Check logs/producer.log"
fi

echo ""
echo "📊 Metrics endpoints:"
echo "   Consumer: http://localhost:7001/actuator/prometheus"
echo "   Producer: http://localhost:7002/actuator/prometheus"
echo ""
echo "📈 Monitoring dashboards:"
echo "   Prometheus: http://localhost:9090/targets"
echo "   Grafana: http://localhost:3000 (admin/admin)"
echo ""
echo "📋 View logs:"
echo "   tail -f logs/consumer.log"
echo "   tail -f logs/producer.log"
echo ""
echo "🛑 To stop apps:"
echo "   bash scripts/stop-local-apps.sh"

