#!/usr/bin/env bash
# ============================================================================
# Lite-Alert — Docker image build, tag & push to Docker Hub
#
# Usage:
#   ./docker/release.sh                         # dry-run mode
#   ./docker/release.sh --push                   # build + tag + push
#   ./docker/release.sh --version 1.0.0          # explicit version tag
#   ./docker/release.sh --version 1.0.0 --push   # full release pipeline
#
# Prerequisites:
#   - Docker installed & running
#   - Logged in to Docker Hub: docker login
#   - Git working tree clean (or use --skip-git-check)
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- Config ----
DOCKER_HUB_USER="${DOCKER_HUB_USER:-jevonsnotes}"   # change to your Docker Hub username
DOCKER_HUB_REPO="${DOCKER_HUB_REPO:-lite-alert}"
IMAGE_NAME="${DOCKER_HUB_USER}/${DOCKER_HUB_REPO}"

# ---- Defaults ----
ACTION="dry-run"
VERSION=""
SKIP_GIT_CHECK=false

# ---- Parse args ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push)         ACTION="push"; shift ;;
    --version)      VERSION="$2"; shift 2 ;;
    --skip-git-check) SKIP_GIT_CHECK=true; shift ;;
    --help|-h)
      echo "Usage: $0 [--push] [--version <ver>] [--skip-git-check]"
      exit 0
      ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# ---- Resolve version ----
if [[ -z "$VERSION" ]]; then
  # Default: derive from pom.xml <version> tag, strip -SNAPSHOT suffix
  POM_VERSION=$(grep -oP '<version>\K[0-9][^<]+' "$PROJECT_DIR/pom.xml" | head -1)
  VERSION="${POM_VERSION%-SNAPSHOT}"
  # Fallback if SNAPSHOT was already stripped
  [[ -z "$VERSION" ]] && VERSION="0.1.0"
fi

TAG="${IMAGE_NAME}:${VERSION}"
LATEST="${IMAGE_NAME}:latest"

echo "============================================================"
echo "Lite-Alert Docker Release"
echo "============================================================"
echo "Image:     $TAG"
echo "Also tag:  $LATEST"
echo "Action:    $ACTION"
echo "Context:   $PROJECT_DIR"
echo "Dockerfile: docker/Dockerfile"
echo "============================================================"

# ---- Git sanity check ----
if [[ "$SKIP_GIT_CHECK" != "true" ]]; then
  cd "$PROJECT_DIR"
  if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
    echo "ERROR: Working tree has uncommitted changes."
    echo "       Commit or stash changes first, or use --skip-git-check."
    exit 1
  fi
fi

# ---- Build ----
echo ""
echo "[1/3] Building Docker image..."
docker build \
  --progress=plain \
  -f "$SCRIPT_DIR/Dockerfile" \
  -t "$TAG" \
  "$PROJECT_DIR"

# ---- Tag latest ----
echo "[2/3] Tagging latest..."
docker tag "$TAG" "$LATEST"

echo ""
echo "Built images:"
docker images | grep "$DOCKER_HUB_REPO"

# ---- Push ----
if [[ "$ACTION" == "push" ]]; then
  echo ""
  echo "[3/3] Pushing to Docker Hub..."

  # Verify login
  if ! docker info 2>/dev/null | grep -q "Username"; then
    echo "ERROR: Not logged in to Docker Hub."
    echo "       Run: docker login"
    exit 1
  fi

  docker push "$TAG"
  echo "Pushed: $TAG"

  docker push "$LATEST"
  echo "Pushed: $LATEST"

  echo ""
  echo "============================================================"
  echo "Release complete!"
  echo "============================================================"
  echo ""
  echo "Deploy via Docker Hub:"
  echo "  cd docker"
  echo "  IMAGE=$TAG docker compose up -d"
  echo ""
  echo "Deploy via docker run:"
  echo "  docker run -d --name lite-alert -p 8080:8080 $TAG"
  echo ""
else
  echo ""
  echo "============================================================"
  echo "Dry-run complete. Images built locally but NOT pushed."
  echo "============================================================"
  echo ""
  echo "To push, run:"
  echo "  $0 --version $VERSION --push"
  echo ""
fi
