variable "cluster_name" {
  description = "kind cluster name. Matches what k8s/deploy.sh used, so an existing cluster is adopted rather than duplicated."
  type        = string
  default     = "corebank"
}

variable "node_image" {
  description = "kind node image, which pins the Kubernetes version. Left explicit rather than defaulted by kind, so an upgrade is a reviewable diff."
  type        = string
  default     = "kindest/node:v1.35.0"
}

variable "app_image" {
  description = "Locally built application image, loaded into the node rather than pulled (k8s/app.yaml sets imagePullPolicy: Never). Build it first with `docker compose build app`."
  type        = string
  default     = "corebank-app:latest"
}

variable "rollout_timeout" {
  description = "How long to wait for the app Deployment. Generous because a cold start pays for Postgres, Kafka and Keycloak coming up behind the init containers in k8s/app.yaml."
  type        = string
  default     = "6m"
}

variable "shell_interpreter" {
  description = <<-EOT
    Interpreter for the local-exec provisioners that pipe (`kubectl create ... | kubectl apply -f -`).
    Terraform on Windows defaults to cmd.exe, which parses pipes differently and cannot run the
    heredoc-free one-liners here reliably; Git Bash ships with the toolchain this project already
    depends on. Override to ["/bin/sh", "-c"] on Linux or macOS.
  EOT
  type        = list(string)
  default     = ["C:/Program Files/Git/bin/bash.exe", "-c"]
}
