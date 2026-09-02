#!/usr/bin/env bash
# Deploys CoreBank to a local `kind` cluster. Prerequisite: the cluster exists
# (`kind create cluster --name corebank`) and both images are built
# (`docker compose build app insights`).
set -euo pipefail
cd "$(dirname "$0")/.."

kind load docker-image corebank-app:latest --name corebank
kind load docker-image corebank-insights:latest --name corebank

kubectl create namespace corebank --dry-run=client -o yaml | kubectl apply -f -

# The one piece kustomize can't generate itself -- see the comment in kustomization.yaml.
kubectl create configmap keycloak-realm-import -n corebank \
  --from-file=corebank-realm.json=keycloak/corebank-realm.json \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -k k8s/

echo
echo "Waiting for the app deployment to become available (this includes Postgres/Redis/Kafka/Keycloak startup)..."
kubectl rollout status deployment/app -n corebank --timeout=5m

cat <<'EOF'

Deployed. Reach it with:
  kubectl port-forward svc/app -n corebank 8080:8080
  kubectl port-forward svc/keycloak -n corebank 8081:8080   # needed too: issuer-uri points here
EOF
