# The namespace and the guardrails around it.
#
# Before Phase 5 the namespace came from ../k8s/00-namespace.yaml (kubectl
# apply). Terraform now owns it, and the file is only kept for the plain
# "kubectl apply -f k8s/" path used by cd.yml. Adopting an object that already
# exists is what "terraform import" is for - see the README, "Adopting the
# existing cluster".

resource "kubernetes_namespace_v1" "platform" {
  metadata {
    name = var.namespace
    labels = {
      "app.kubernetes.io/part-of"    = "devsecops-platform"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }
}

# A ceiling for the whole namespace. The three services peaking at
# HPA maxReplicas (3 pods x 100m/320Mi requests, 500m/640Mi limits) need about
# 900m CPU and 3Gi of requests, so the defaults leave plenty of room and still
# stop a runaway scale-out from eating the laptop.
#
# Because this quota constrains requests.cpu/limits.cpu, every container that
# lands in this namespace MUST declare its own requests and limits - Kubernetes
# rejects the pod otherwise. The LimitRange below covers anything that forgets.
resource "kubernetes_resource_quota_v1" "platform" {
  metadata {
    name      = "platform-quota"
    namespace = kubernetes_namespace_v1.platform.metadata[0].name
  }

  spec {
    hard = {
      "requests.cpu"           = var.quota.requests_cpu
      "requests.memory"        = var.quota.requests_memory
      "limits.cpu"             = var.quota.limits_cpu
      "limits.memory"          = var.quota.limits_memory
      "pods"                   = var.quota.pods
      "services"               = "20"
      "configmaps"             = "20"
      "secrets"                = "20"
      "persistentvolumeclaims" = "4"
    }
  }
}

# Defaults for containers that do not ask for anything. The three service
# manifests all set their own numbers, so this changes nothing today; it is
# what keeps a future manifest (a sidecar, a job) from being admitted
# unbounded. "max" is the per-container ceiling.
resource "kubernetes_limit_range_v1" "platform" {
  metadata {
    name      = "platform-limits"
    namespace = kubernetes_namespace_v1.platform.metadata[0].name
  }

  spec {
    limit {
      type = "Container"

      default = {
        cpu    = var.container_defaults.default_cpu
        memory = var.container_defaults.default_memory
      }

      default_request = {
        cpu    = var.container_defaults.default_request_cpu
        memory = var.container_defaults.default_request_memory
      }

      max = {
        cpu    = var.container_defaults.max_cpu
        memory = var.container_defaults.max_memory
      }
    }
  }
}
