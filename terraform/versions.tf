# Phase 5 - Terraform.
#
# Terraform owns the *infrastructure* of the platform: the namespace the
# services live in, the guardrails that namespace is held to (ResourceQuota,
# LimitRange) and the two cluster add-ons the manifests in ../k8s depend on
# (ingress-nginx and metrics-server). The application environment and the
# workloads themselves are Ansible's job - see ../ansible.
#
# No backend block on purpose: state is local (terraform.tfstate, gitignored).
# A team setup would point this at a remote backend; a laptop demo does not
# need one, and the spec asks for free local tooling only.

terraform {
  # 1.5 introduced the import blocks and check blocks used below.
  required_version = ">= 1.5.0"

  required_providers {
    kubernetes = {
      source = "hashicorp/kubernetes"
      # Pinned to the 2.x line: 3.x moves the provider to the plugin framework
      # and changes some attribute types, which is not worth the churn here.
      version = "~> 2.30"
    }
    helm = {
      source = "hashicorp/helm"
      # Same reasoning as the kubernetes provider - stay on 2.x.
      version = "~> 2.14"
    }
  }
}

# Both providers read the kubeconfig the same way: var.kubeconfig_path, else the
# KUBE_CONFIG_PATH environment variable, else ~/.kube/config. Note that the
# provider does NOT read KUBECONFIG - a machine that only sets that variable
# (kubectl works, Terraform silently falls back to http://localhost) has to set
# KUBE_CONFIG_PATH or pass -var="kubeconfig_path=...".
provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}
