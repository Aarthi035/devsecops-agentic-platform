# What the rest of the tooling needs to know after an apply.

output "namespace" {
  description = "Namespace holding the platform services."
  value       = kubernetes_namespace_v1.platform.metadata[0].name
}

output "monitoring_namespace" {
  description = "Namespace holding the Phase 6 observability stack."
  value       = kubernetes_namespace_v1.monitoring.metadata[0].name
}

output "resource_quota" {
  description = "Name of the namespace ResourceQuota."
  value       = kubernetes_resource_quota_v1.platform.metadata[0].name
}

output "limit_range" {
  description = "Name of the namespace LimitRange."
  value       = kubernetes_limit_range_v1.platform.metadata[0].name
}

output "ingress_nginx_version" {
  description = "Installed ingress-nginx chart version."
  value       = helm_release.ingress_nginx.version
}

output "metrics_server_version" {
  description = "Installed metrics-server chart version."
  value       = helm_release.metrics_server.version
}

output "cluster_checks" {
  description = "The add-on facts the Phase 4 manifests depend on, so a failed apply is visible at the end of the run."
  value = {
    ingress_controller  = "${helm_release.ingress_nginx.name}/ingress-nginx-controller in namespace ingress-nginx"
    ingress_class       = "nginx (created by the chart, referenced by ../k8s/40-ingress.yaml)"
    metrics_kubelet_tls = "--kubelet-insecure-tls is passed, which is what makes ../k8s/30-hpa.yaml report CPU instead of <unknown>"
  }
}
