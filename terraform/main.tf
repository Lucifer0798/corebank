terraform {
  required_version = ">= 1.9"

  required_providers {
    kind = {
      source  = "tehcyx/kind"
      version = "~> 0.9"
    }
  }
}

provider "kind" {}

# The cluster itself, replacing `kind create cluster --name corebank`. This is where Terraform
# genuinely earns its place in this phase: the node image, the cluster topology and the
# kubeconfig wiring stop being flags someone has to remember and become a checked-in, versioned
# description that `terraform plan` can diff against reality.
resource "kind_cluster" "corebank" {
  name           = var.cluster_name
  node_image     = var.node_image
  wait_for_ready = true

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    node {
      role = "control-plane"
    }
  }
}

# `kind load docker-image` has no provider resource -- the image is transferred into the node's
# containerd over the Docker socket, which no Terraform provider models. A local-exec is the only
# way to express it, so it is one here rather than a step someone has to remember to run first.
#
# triggers_replace on the cluster id means recreating the cluster reloads the image: a new kind
# node starts with an empty containerd, and k8s/app.yaml pins imagePullPolicy: Never, so without
# this the app pod would sit in ErrImageNeverPull.
resource "terraform_data" "app_image" {
  triggers_replace = [
    kind_cluster.corebank.id,
    var.app_image,
  ]

  provisioner "local-exec" {
    command     = "kind load docker-image ${var.app_image} --name ${kind_cluster.corebank.name}"
    working_dir = path.root
  }
}

# Same reasoning as app_image above: k8s/insights.yaml also pins imagePullPolicy: Never, so
# without this the insights pod sits in ErrImageNeverPull on a freshly created cluster.
resource "terraform_data" "insights_image" {
  triggers_replace = [
    kind_cluster.corebank.id,
    var.insights_image,
  ]

  provisioner "local-exec" {
    command     = "kind load docker-image ${var.insights_image} --name ${kind_cluster.corebank.name}"
    working_dir = path.root
  }
}

# The Keycloak realm ConfigMap, generated from the same keycloak/corebank-realm.json the compose
# stack mounts. kustomize refuses file references outside its own directory (see the comment in
# k8s/kustomization.yaml), so this stays an imperative create the same way k8s/deploy.sh does it
# -- generating it here rather than copying the file keeps one source of truth for the realm.
resource "terraform_data" "realm_configmap" {
  triggers_replace = [
    kind_cluster.corebank.id,
    filesha256("${path.root}/../keycloak/corebank-realm.json"),
  ]

  provisioner "local-exec" {
    command     = "kubectl create namespace corebank --dry-run=client -o yaml | kubectl apply -f -"
    interpreter = var.shell_interpreter
    working_dir = path.root
  }

  provisioner "local-exec" {
    command     = "kubectl create configmap keycloak-realm-import -n corebank --from-file=corebank-realm.json=keycloak/corebank-realm.json --dry-run=client -o yaml | kubectl apply -f -"
    interpreter = var.shell_interpreter
    working_dir = "${path.root}/.."
  }
}

# The application stack. Deliberately `kubectl apply -k` against the existing k8s/ kustomization
# rather than a set of typed kubernetes_* resources or kubernetes_manifest entries:
#
#   - Re-expressing eleven already-working, already-verified manifests as Terraform resources
#     would create a second copy of every Deployment and Service, free to drift from the ones
#     `kubectl apply -k k8s/` still uses. That is the same trap the realm-import ConfigMap is
#     avoiding just above, and the reason Phase 5 fed OpenSearch from the existing Kafka topics
#     rather than adding a parallel write path.
#   - hashicorp/kubernetes's kubernetes_manifest performs a server-side dry run during `plan`,
#     so it cannot describe objects in a cluster the same configuration is still creating --
#     it would force a two-stage `terraform apply -target=...` just to bootstrap.
#
# The honest trade-off: Terraform tracks *that* the manifests are applied, not the state of each
# object inside them. `kubectl diff -k k8s/` remains the tool for the latter.
resource "terraform_data" "manifests" {
  depends_on = [terraform_data.realm_configmap]

  triggers_replace = [
    kind_cluster.corebank.id,
    # Any manifest edit re-applies, without needing each file listed here by hand.
    sha256(join("", [for f in sort(fileset("${path.root}/../k8s", "*.yaml")) : filesha256("${path.root}/../k8s/${f}")])),
  ]

  provisioner "local-exec" {
    command     = "kubectl apply -k k8s/"
    interpreter = var.shell_interpreter
    working_dir = "${path.root}/.."
  }
}

# Rolls out only after the image is actually in the node, otherwise the app pod's
# imagePullPolicy: Never fails before the load lands.
resource "terraform_data" "app_rollout" {
  depends_on = [
    terraform_data.app_image,
    terraform_data.insights_image,
    terraform_data.manifests,
  ]

  triggers_replace = [
    terraform_data.manifests.id,
    terraform_data.app_image.id,
    terraform_data.insights_image.id,
  ]

  # Both deployments, so a failed insights rollout is a failed apply rather than something you
  # only discover later. Waiting on app first is not significant -- they start in parallel.
  provisioner "local-exec" {
    command     = "kubectl rollout status deployment/app -n corebank --timeout=${var.rollout_timeout} && kubectl rollout status deployment/insights -n corebank --timeout=${var.rollout_timeout}"
    interpreter = var.shell_interpreter
    working_dir = "${path.root}/.."
  }
}
