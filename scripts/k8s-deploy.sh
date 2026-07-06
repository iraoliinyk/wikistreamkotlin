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

# Overridable via env, e.g. MINIKUBE_MEMORY=10240 bash scripts/k8s-deploy.sh
# 12Gi is needed for the FULL stack: 3-node Cassandra (~4.5Gi) + Redpanda + Redis +
# kube-prometheus-stack (~2.5Gi) + producer + 2 consumers. It was measured peaking at
# ~9.2Gi, and a node too small OOM-kills Cassandra (exit 137), which then loses quorum.
# Your Docker Desktop VM must be larger than this (Settings → Resources → Memory).
MINIKUBE_CPUS="${MINIKUBE_CPUS:-4}"
MINIKUBE_MEMORY="${MINIKUBE_MEMORY:-12288}"
# Pin the Kubernetes version so re-runs are deterministic and never drift from a
# cluster created by an older minikube (version drift leaves a half-initialised
# control plane whose kubelet crash-loops on a missing bootstrap-kubelet.conf).
K8S_VERSION="${K8S_VERSION:-v1.33.1}"
# metrics-server is OPT-IN. Enabling it during `minikube start` bootstrap registers
# the metrics.k8s.io APIService before its pod is ready, which corrupts the API
# server's aggregated /openapi/v2 and makes EVERY other addon apply fail with
# "failed to download openapi ... connection refused". We keep it out of the
# bootstrap and enable it (if requested) only after the cluster is healthy.
ENABLE_METRICS_SERVER="${ENABLE_METRICS_SERVER:-false}"

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

# Fail fast with a clear message if a required CLI is missing.
require_tools() {
    local missing=0 t
    for t in "$@"; do
        if ! command -v "$t" >/dev/null 2>&1; then
            echo "  ✗ Required tool not found on PATH: $t" >&2
            missing=1
        fi
    done
    [ "$missing" -eq 0 ] || { echo "Install the missing tools and re-run." >&2; exit 1; }
}

start_minikube() {
    minikube start \
        --cpus="$MINIKUBE_CPUS" \
        --memory="$MINIKUBE_MEMORY" \
        --driver=docker \
        --kubernetes-version="$K8S_VERSION" \
        --wait=all
}

# Block until the API server answers /healthz, so nothing (addons, manifests) is
# applied against a control plane that is still coming up — the root cause of the
# "dial tcp 127.0.0.1:8443: connect: connection refused" addon failures.
wait_for_apiserver() {
    local i
    for i in $(seq 1 30); do
        if kubectl get --raw='/healthz' >/dev/null 2>&1; then
            echo "  ▪ API server is healthy."
            return 0
        fi
        sleep 4
    done
    echo "  ✗ API server did not become healthy within ~2m." >&2
    return 1
}

# Enable an addon with retries once the API server is confirmed ready. Enabling
# here (rather than relying on 'minikube start' to apply them mid-bootstrap)
# avoids the start-time race that makes addons fail to download the OpenAPI schema.
enable_addon() {
    local addon="$1" i
    for i in 1 2 3; do
        if minikube addons enable "$addon" >/dev/null 2>&1; then
            echo "  ▪ addon enabled: $addon"
            return 0
        fi
        sleep 5
    done
    echo "  ⚠ Could not enable addon '$addon' after retries (continuing)."
    return 1
}

# Bring the cluster up and return only when the API server is actually healthy.
# We do NOT treat 'minikube start' non-zero exit as fatal (addon warnings can
# make it non-zero even when the control plane is fine) — health is decided by
# wait_for_apiserver, the single source of truth.
ensure_cluster_up() {
    start_minikube || true
    wait_for_apiserver
}

echo "🚀 Deploying WikiStream to Kubernetes (Minikube)..."
echo ""

# ── 0. Cluster + namespace + images ─────────────────────────────────────────
echo "🧱 Step 0: Cluster, namespace, and images..."
require_tools minikube kubectl helm docker

# If a cluster already exists but its API server is not Running, it is wedged
# (e.g. a crash-looping kubelet from a prior corrupt/interrupted start). It
# cannot be repaired in place — delete it for a clean start.
if minikube status >/dev/null 2>&1; then
    # An existing node's memory cannot be grown in place — 'minikube start --memory'
    # is a silent no-op on an already-created cluster. If the running node is smaller
    # than requested, recreate it, otherwise Cassandra will OOM on a too-small node.
    node_mem_bytes="$(docker inspect minikube --format '{{.HostConfig.Memory}}' 2>/dev/null || echo 0)"
    want_mem_bytes=$(( MINIKUBE_MEMORY * 1024 * 1024 ))
    if [ "$node_mem_bytes" -gt 0 ] && [ "$node_mem_bytes" -lt "$want_mem_bytes" ]; then
        echo "  ⚠ Existing node has $(( node_mem_bytes / 1048576 ))Mi < requested ${MINIKUBE_MEMORY}Mi — recreating to resize."
        minikube delete || true
    fi
fi

if minikube status >/dev/null 2>&1; then
    if ! minikube status 2>/dev/null | grep -qi 'apiserver: Running'; then
        echo "  ⚠ Existing cluster is not healthy (API server down) — deleting for a clean start."
        minikube delete || true
    elif [ "$ENABLE_METRICS_SERVER" != "true" ]; then
        # Cluster is healthy but may carry metrics-server in its addon profile from
        # a previous run. Left enabled, the next 'minikube start' re-applies it mid-
        # bootstrap and poisons /openapi/v2. Remove it now (best effort) so a restart
        # stays clean.
        minikube addons disable metrics-server >/dev/null 2>&1 || true
    fi
fi

# Start; if the API server does not come up, reset once from scratch before giving up.
if ! ensure_cluster_up; then
    echo "  ⚠ API server unhealthy after start — deleting and retrying once from scratch."
    minikube delete || true
    ensure_cluster_up
fi

# Enable addons AFTER the API server is confirmed healthy. storage-provisioner /
# default-storageclass are safe (no APIService). metrics-server is opt-in and, when
# requested, is given time to roll out so its APIService becomes available.
echo "  ▪ Enabling addons (post-bootstrap)..."
enable_addon storage-provisioner || true    # dynamic PVCs for Cassandra / Redpanda / Redis
enable_addon default-storageclass || true
if [ "$ENABLE_METRICS_SERVER" = "true" ]; then
    if enable_addon metrics-server; then
        kubectl -n kube-system rollout status deployment/metrics-server --timeout=120s || true
    fi
fi

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

    # Apply the FULL schema — keyspace AND tables. Creating only the keyspace leaves
    # the app's tables (stats_counters, server_url_events, tracked_user_sets, ...)
    # missing, so GET /v1/stats returns 500 "table ... does not exist". We strip the
    # dev-only leading DROP statements (they run before the keyspace exists and would
    # both error and wipe data); every CREATE is `IF NOT EXISTS`, so this is idempotent.
    SCHEMA_CQL="cmd/consumer/src/main/resources/db/cassandra/schema.cql"
    echo "  ▪ Applying schema (keyspace + tables) from $SCHEMA_CQL..."
    grep -viE '^[[:space:]]*DROP ' "$SCHEMA_CQL" | kubectl exec -i cassandra-0 -- cqlsh || true

    # Verify the critical table exists; fail loudly if the schema didn't land.
    echo "  ▪ Verifying schema..."
    for attempt in 1 2 3 4 5; do
        if kubectl exec -i cassandra-0 -- cqlsh -e \
             "SELECT table_name FROM system_schema.tables WHERE keyspace_name='wikistream';" \
             2>/dev/null | grep -q 'stats_counters'; then
            echo "  ▪ Schema OK (stats_counters present)."
            break
        fi
        if [ "$attempt" -eq 5 ]; then
            echo "  ✗ Schema verification failed: stats_counters missing after retries." >&2
            exit 1
        fi
        echo "    …schema not settled yet, retrying ($attempt/5)"
        sleep 5
    done
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
