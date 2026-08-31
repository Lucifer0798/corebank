output "cluster_name" {
  description = "The kind cluster this configuration manages."
  value       = kind_cluster.corebank.name
}

output "kubectl_context" {
  description = "kubectl context name kind registers for this cluster."
  value       = "kind-${kind_cluster.corebank.name}"
}

output "port_forward_commands" {
  description = "How to reach the deployed stack. Services are ClusterIP, so nothing is published on the host until one of these runs."
  value = {
    api      = "kubectl port-forward svc/app -n corebank 8080:8080"
    grpc     = "kubectl port-forward svc/app -n corebank 9091:9091"
    keycloak = "kubectl port-forward svc/keycloak -n corebank 8081:8080"
  }
}
