#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# WikiStream Kubernetes — Full Deployment (Minikube)
# ─────────────────────────────────────────────────────────────────────────────
#
# Deploys the entire stack to Minikube in dependency order:
#   cluster + namespace + images → stateful deps → monitoring → apps
#
# Usage:
#   bash scripts/k8s-deploy.sh
#
# Requirements: minikube, kubectl, helm, docker on PATH.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

K8S_DIR="k8s"
NAMESPACE="wikistream"

# Apply a manifest only if it exists; warn (don't fail) when it doesn't.
apply_if_exists() {
    local file="$1"
    if [ -f "$file" ]; then
        echo "  ▪ kubectl apply -f $file"
        kubectl apply -f "$file"
    else
        echo "  ⚠ Skipping $file (not found)"
        return 1
    fi
}

echo "🚀 Deploying WikiStream to Kubernetes (Minikube)..."
echo ""

# ── 0. Cluster + namespace + images ─────────────────────────────────────────
echo "🧱 Step 0: Cluster, namespace, and images..."
minikube start --cpus=4 --memory=8192 --driver=docker
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl config set-context --current --namespace="$NAMESPACE"

echo "  ▪ Building images inside Minikube's Docker daemon..."
eval "$(minikube docker-env)"
docker build -t wikistream-producer:latest -f Dockerfile.producer .
docker build -t wikistream-consumer:latest -f Dockerfile.consumer .
echo ""

# ── 1. Stateful dependencies ────────────────────────────────────────────────
echo "🗄  Step 1: Stateful dependencies (Redpanda, Redis, Cassandra)..."

apply_if_exists "$K8S_DIR/redpanda.yaml"
kubectl rollout status statefulset/redpanda

if [ -f "$K8S_DIR/redpanda-init-job.yaml" ]; then
    # Jobs are immutable on spec.template, so a previous run must be removed
    # before re-applying; --ignore-not-found makes this safe on a clean cluster.
    kubectl delete job redpanda-init --ignore-not-found
    echo "  ▪ kubectl apply -f $K8S_DIR/redpanda-init-job.yaml"
    kubectl apply -f "$K8S_DIR/redpanda-init-job.yaml"
    kubectl wait --for=condition=complete job/redpanda-init --timeout=120s
else
    echo "  ⚠ Skipping $K8S_DIR/redpanda-init-job.yaml (not found)"
fi

apply_if_exists "$K8S_DIR/redis.yaml" || true

if apply_if_exists "$K8S_DIR/cassandra.yaml"; then
    kubectl rollout status statefulset/cassandra --timeout=600s
    echo "  ▪ Creating keyspace..."
    kubectl exec -i cassandra-0 -- cqlsh -e \
      "CREATE KEYSPACE IF NOT EXISTS wikistream WITH replication = {'class':'NetworkTopologyStrategy','datacenter1':3};"
fi
echo ""

# ── 2. Monitoring (Prometheus + Grafana) ────────────────────────────────────
echo "📈 Step 2: Monitoring (kube-prometheus-stack)..."
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack -n "$NAMESPACE" \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
  --set grafana.adminPassword=admin
apply_if_exists "$K8S_DIR/servicemonitors.yaml" || true
echo ""

# ── 3. Applications ─────────────────────────────────────────────────────────
echo "🧩 Step 3: Applications (producer, consumer)..."
if apply_if_exists "$K8S_DIR/producer.yaml"; then
    kubectl rollout status deployment/producer
fi
if apply_if_exists "$K8S_DIR/consumer.yaml"; then
    kubectl rollout status deployment/consumer
fi
echo ""

echo "✅ Deployment complete!"
echo "   Inspect with: kubectl get pods -n $NAMESPACE"
