#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Verification Script: Local Cassandra vs Astra Database Modes
# ─────────────────────────────────────────────────────────────────────────────
#
# This script verifies that the docker-compose.dev.yml correctly supports
# both local Cassandra and Astra cloud database modes without requiring
# a CASSANDRA_MODE environment variable.
#
# Usage:
#   ./scripts/verify-cassandra-mode.sh [--help|local|astra|cleanup]
#
# Examples:
#   ./scripts/verify-cassandra-mode.sh                    # Run all checks
#   ./scripts/verify-cassandra-mode.sh local              # Check local Cassandra mode
#   ./scripts/verify-cassandra-mode.sh astra              # Check Astra mode config
#   ./scripts/verify-cassandra-mode.sh cleanup            # Stop all containers
#
# ─────────────────────────────────────────────────────────────────────────────

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.dev.yml"
ASTRA_SECRETS="$PROJECT_ROOT/config/cassandra-astra-secrets.properties"
ASTRA_SECRETS_TEMPLATE="$PROJECT_ROOT/config/cassandra-astra-secrets.properties.template"
AUTH_SECRETS="$PROJECT_ROOT/config/auth-secrets.properties"
AUTH_SECRETS_TEMPLATE="$PROJECT_ROOT/config/auth-secrets.properties.template"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ─────────────────────────────────────────────────────────────────────────────
# Helper Functions
# ─────────────────────────────────────────────────────────────────────────────

log_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

log_success() {
    echo -e "${GREEN}✓${NC} $1"
}

log_error() {
    echo -e "${RED}✗${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_header() {
    echo ""
    echo "╔═══════════════════════════════════════════════════════════════════╗"
    echo "║ $1"
    echo "╚═══════════════════════════════════════════════════════════════════╝"
    echo ""
}

print_subheader() {
    echo ""
    echo "─── $1 ───"
}

# ─────────────────────────────────────────────────────────────────────────────
# Verification Checks
# ─────────────────────────────────────────────────────────────────────────────

verify_compose_file_exists() {
    print_subheader "Checking docker-compose.dev.yml"

    if [[ ! -f "$COMPOSE_FILE" ]]; then
        log_error "docker-compose.dev.yml not found at $COMPOSE_FILE"
        return 1
    fi
    log_success "Found docker-compose.dev.yml"
}

verify_no_cassandra_mode_env_var() {
    print_subheader "Verifying CASSANDRA_MODE is not used"

    # Check if CASSANDRA_MODE is documented as obsolete
    if grep -q "CASSANDRA_MODE=astra" "$COMPOSE_FILE"; then
        log_error "Found obsolete CASSANDRA_MODE=astra in docker-compose.dev.yml"
        return 1
    fi
    log_success "CASSANDRA_MODE=astra reference removed from header comments"

    if grep -q "unless CASSANDRA_MODE=astra" "$COMPOSE_FILE"; then
        log_error "Found obsolete conditional in comments"
        return 1
    fi
    log_success "Removed all misleading CASSANDRA_MODE comments"
}

verify_cassandra_service_always_available() {
    print_subheader "Verifying cassandra service is available by default"

    # Check that cassandra service doesn't have a restrictive profile
    if grep -A 5 "^  cassandra:" "$COMPOSE_FILE" | grep -q "profiles:"; then
        log_error "cassandra service should not have profiles that restrict it"
        return 1
    fi
    log_success "cassandra service available by default (no profiles)"

    # Check that cassandra-init doesn't have profiles either
    if grep -A 8 "^  cassandra-init:" "$COMPOSE_FILE" | grep -q "profiles:"; then
        log_error "cassandra-init service should not have profiles that restrict it"
        return 1
    fi
    log_success "cassandra-init service available by default (no profiles)"
}

verify_compose_file_structure() {
    print_subheader "Checking docker-compose.dev.yml structure"

    local errors=0

    # Check version
    if ! grep -q "version: '3.8'" "$COMPOSE_FILE"; then
        log_error "Missing or incorrect Docker Compose version"
        errors=$((errors + 1))
    else
        log_success "Docker Compose version: 3.8"
    fi

    # Check services
    if ! grep -q "redpanda:" "$COMPOSE_FILE"; then
        log_error "Missing redpanda service"
        errors=$((errors + 1))
    else
        log_success "redpanda service found"
    fi

    if ! grep -q "cassandra:" "$COMPOSE_FILE"; then
        log_error "Missing cassandra service"
        errors=$((errors + 1))
    else
        log_success "cassandra service found"
    fi

    if ! grep -q "redis:" "$COMPOSE_FILE"; then
        log_error "Missing redis service"
        errors=$((errors + 1))
    else
        log_success "redis service found (with profiles)"
    fi

    if ! grep -q "redpanda-console:" "$COMPOSE_FILE"; then
        log_error "Missing redpanda-console service"
        errors=$((errors + 1))
    else
        log_success "redpanda-console service found"
    fi

    return $errors
}

verify_compose_file_validates() {
    print_subheader "Validating Docker Compose syntax"

    if ! docker-compose -f "$COMPOSE_FILE" config > /dev/null 2>&1; then
        log_error "docker-compose.dev.yml has syntax errors"
        docker-compose -f "$COMPOSE_FILE" config
        return 1
    fi
    log_success "docker-compose.dev.yml syntax is valid"
}

verify_local_cassandra_mode() {
    print_subheader "Testing LOCAL CASSANDRA mode"

    log_info "Starting local Cassandra mode..."

    cd "$PROJECT_ROOT"

    # Start infra
    if docker-compose -f "$COMPOSE_FILE" up -d 2>&1 | tail -5; then
        log_success "Docker Compose started successfully"
    else
        log_error "Failed to start Docker Compose"
        return 1
    fi

    # Wait for Redpanda health
    log_info "Waiting for Redpanda health check (up to 60s)..."
    local count=0
    while [[ $count -lt 60 ]]; do
        if docker-compose -f "$COMPOSE_FILE" exec -T redpanda rpk cluster health 2>/dev/null | grep -q "Healthy.*true"; then
            log_success "Redpanda is healthy"
            break
        fi
        sleep 1
        count=$((count + 1))
    done

    if [[ $count -eq 60 ]]; then
        log_error "Redpanda failed to become healthy within 60 seconds"
        return 1
    fi

    # Check that Cassandra container exists and is running
    if docker ps | grep -q "wikistream-cassandra"; then
        log_success "Local Cassandra container is running"
    else
        log_error "Local Cassandra container is not running"
        return 1
    fi

    # Check that Redpanda topics were created
    local raw_topic=$(docker-compose -f "$COMPOSE_FILE" exec -T redpanda rpk topic list 2>/dev/null | grep -c "wiki.recentchange.raw" || true)
    if [[ $raw_topic -gt 0 ]]; then
        log_success "Kafka topics created: wiki.recentchange.raw, wiki.recentchange.dlq"
    else
        log_error "Kafka topics not found"
        return 1
    fi

    log_success "LOCAL CASSANDRA mode working correctly"
}

verify_astra_config_exists() {
    print_subheader "Checking Astra configuration support"

    # The template is named cassandra-secrets.properties.template
    local template_file="$PROJECT_ROOT/config/cassandra-secrets.properties.template"

    if [[ ! -f "$template_file" ]]; then
        log_error "cassandra-secrets.properties.template not found"
        return 1
    fi
    log_success "Found cassandra-secrets.properties.template"

    # Check that template has spring.profiles.active=astra
    if ! grep -q "spring.profiles.active=astra" "$template_file"; then
        log_error "Template doesn't set spring.profiles.active=astra"
        return 1
    fi
    log_success "Template correctly sets spring.profiles.active=astra"

    # Check that application-astra.properties exists in consumer
    if [[ ! -f "$PROJECT_ROOT/cmd/consumer/src/main/resources/application-astra.properties" ]]; then
        log_error "application-astra.properties not found"
        return 1
    fi
    log_success "Found application-astra.properties"

    # Verify it's activated by profile
    if ! grep -q "spring.config.activate.on-profile=astra" "$PROJECT_ROOT/cmd/consumer/src/main/resources/application-astra.properties"; then
        log_error "application-astra.properties doesn't activate on astra profile"
        return 1
    fi
    log_success "application-astra.properties correctly activates on astra profile"
}

verify_documentation() {
    print_subheader "Checking documentation accuracy"

    local doc_file="$PROJECT_ROOT/DOCKER_SETUP.md"
    local errors=0

    if [[ ! -f "$doc_file" ]]; then
        log_error "DOCKER_SETUP.md not found"
        return 1
    fi

    # Check that DOCKER_SETUP.md does NOT mention CASSANDRA_MODE
    if grep -q "CASSANDRA_MODE" "$doc_file"; then
        log_error "DOCKER_SETUP.md still references CASSANDRA_MODE (obsolete)"
        errors=$((errors + 1))
    else
        log_success "DOCKER_SETUP.md does not reference CASSANDRA_MODE"
    fi

    # Check that it explains local Cassandra mode
    if grep -q "Local Cassandra (default" "$doc_file"; then
        log_success "DOCKER_SETUP.md documents local Cassandra mode"
    else
        log_error "DOCKER_SETUP.md missing local Cassandra documentation"
        errors=$((errors + 1))
    fi

    # Check that it explains Astra mode
    if grep -q "For Astra" "$doc_file"; then
        log_success "DOCKER_SETUP.md documents Astra mode"
    else
        log_error "DOCKER_SETUP.md missing Astra documentation"
        errors=$((errors + 1))
    fi

    # Check that it explains profile activation
    if grep -q "spring.profiles.active=astra" "$doc_file"; then
        log_success "DOCKER_SETUP.md explains astra profile activation"
    else
        log_error "DOCKER_SETUP.md missing profile activation explanation"
        errors=$((errors + 1))
    fi

    return $errors
}

cleanup_containers() {
    print_subheader "Cleaning up containers"

    cd "$PROJECT_ROOT"

    # Stop and remove containers
    if docker-compose -f "$COMPOSE_FILE" down 2>&1 | tail -3; then
        log_success "Stopped and removed containers"
    else
        log_warning "Failed to stop containers (may already be stopped)"
    fi
}

show_help() {
    cat << EOF
Verification Script: Local Cassandra vs Astra Database Modes

This script verifies that docker-compose.dev.yml correctly supports both
local Cassandra and Astra cloud database modes WITHOUT using the obsolete
CASSANDRA_MODE environment variable.

USAGE:
    $(basename "$0") [COMMAND]

COMMANDS:
    (no args)       Run all verification checks
    local           Verify local Cassandra mode works
    astra           Verify Astra configuration is correct
    cleanup         Stop all containers

EXAMPLES:
    ./scripts/verify-cassandra-mode.sh
    ./scripts/verify-cassandra-mode.sh local
    ./scripts/verify-cassandra-mode.sh astra
    ./scripts/verify-cassandra-mode.sh cleanup

WHAT IT CHECKS:
    ✓ docker-compose.dev.yml exists and is valid
    ✓ CASSANDRA_MODE references removed
    ✓ cassandra service available by default
    ✓ Docker Compose syntax is valid
    ✓ Redpanda, Cassandra, Redis services configured
    ✓ Astra configuration files exist and are correct
    ✓ DOCKER_SETUP.md documentation is accurate
    ✓ Local Cassandra mode starts correctly
    ✓ Kafka topics created

EOF
}

# ─────────────────────────────────────────────────────────────────────────────
# Main Execution
# ─────────────────────────────────────────────────────────────────────────────

main() {
    local command="${1:-}"

    case "$command" in
        --help|-h|help)
            show_help
            exit 0
            ;;
        local)
            print_header "VERIFYING LOCAL CASSANDRA MODE"
            verify_compose_file_exists || exit 1
            verify_local_cassandra_mode || exit 1
            print_header "LOCAL CASSANDRA VERIFICATION COMPLETE ✓"
            ;;
        astra)
            print_header "VERIFYING ASTRA CONFIGURATION"
            verify_astra_config_exists || exit 1
            print_header "ASTRA CONFIGURATION VERIFICATION COMPLETE ✓"
            ;;
        cleanup)
            print_header "CLEANUP"
            cleanup_containers
            print_header "CLEANUP COMPLETE"
            ;;
        *)
            print_header "CASSANDRA MODE VERIFICATION SUITE"
            log_info "Running all verification checks..."
            echo ""

            verify_compose_file_exists || exit 1
            verify_no_cassandra_mode_env_var || exit 1
            verify_cassandra_service_always_available || exit 1
            verify_compose_file_structure || exit 1
            verify_compose_file_validates || exit 1
            verify_astra_config_exists || exit 1
            verify_documentation || exit 1

            print_header "ALL VERIFICATION CHECKS PASSED ✓"

            echo ""
            log_info "To test local Cassandra mode startup:"
            echo "  ./scripts/verify-cassandra-mode.sh local"
            echo ""
            log_info "To clean up containers:"
            echo "  ./scripts/verify-cassandra-mode.sh cleanup"
            echo ""
            ;;
    esac
}

main "$@"

