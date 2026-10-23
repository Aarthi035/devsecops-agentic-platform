# Inputs. Every default matches what ../k8s and ../kind/kind-config.yaml already
# assume, so "terraform apply" with no tfvars file reproduces the local setup.

variable "kubeconfig_path" {
  description = "Path to the kubeconfig file. Null means: use $KUBE_CONFIG_PATH, then ~/.kube/config."
  type        = string
  default     = null
}

variable "kube_context" {
  description = "kubeconfig context to use. Null means: use the file's current-context."
  type        = string
  default     = null
}

variable "namespace" {
  description = "Namespace that holds the three platform services."
  type        = string
  default     = "devsecops"
}

variable "ingress_nginx_version" {
  description = <<-EOT
    ingress-nginx Helm chart version. Keep this in step with
    INGRESS_NGINX_VERSION in .github/workflows/cd.yml: the chart and the
    "kind" provider manifest from that tag install the same controller, and
    drifting apart would mean the laptop and CI run different ingresses.

    cd.yml pins controller-v1.11.3, and chart 4.11.3 is the chart whose
    controller.image.tag is v1.11.3 (chart 4.11.4 already ships v1.11.4), so
    4.11.3 is the match. Verified against charts/ingress-nginx/values.yaml at
    each helm-chart-* tag.
  EOT
  type        = string
  default     = "4.11.3"
}

variable "metrics_server_version" {
  description = <<-EOT
    metrics-server Helm chart version. cd.yml installs the unpinned
    releases/latest components.yaml; chart 3.14.0 is the chart whose image tag
    is v0.9.0 - the version that manifest currently resolves to - so the laptop
    and CI run the same metrics-server while this side stays pinned to a
    reproducible chart version.
  EOT
  type        = string
  default     = "3.14.0"
}

variable "quota" {
  description = "ResourceQuota for the namespace. Deliberately generous: it is a guardrail against a runaway demo, not a packing exercise."
  type = object({
    requests_cpu    = string
    requests_memory = string
    limits_cpu      = string
    limits_memory   = string
    pods            = string
  })
  default = {
    requests_cpu    = "4"
    requests_memory = "8Gi"
    limits_cpu      = "8"
    limits_memory   = "12Gi"
    pods            = "20"
  }
}

variable "container_defaults" {
  description = "LimitRange defaults applied to containers that do not declare their own requests and limits."
  type = object({
    default_cpu            = string
    default_memory         = string
    default_request_cpu    = string
    default_request_memory = string
    max_cpu                = string
    max_memory             = string
  })
  default = {
    default_cpu            = "500m"
    default_memory         = "512Mi"
    default_request_cpu    = "100m"
    default_request_memory = "256Mi"
    max_cpu                = "2"
    max_memory             = "2Gi"
  }
}
