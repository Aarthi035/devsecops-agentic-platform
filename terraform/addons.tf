# The two cluster add-ons the Phase 4 manifests assume are already installed.
#
# Phase 4 installed them with kubectl (an ingress manifest plus a metrics-server
# manifest and a JSON patch). Phase 5 replaces that with Helm releases so the
# version, the kind-specific settings and the upgrade path are all declared
# once. cd.yml keeps using the kubectl route for its throwaway cluster - the
# user asked for the green CD workflow to stay untouched; both routes install
# the same controller and the same metrics-server.

resource "helm_release" "ingress_nginx" {
  name      = "ingress-nginx"
  namespace = "ingress-nginx"
  chart     = "ingress-nginx"
  version   = var.ingress_nginx_version

  repository = "https://kubernetes.github.io/ingress-nginx"

  # The controller's own namespace; the platform namespace is Terraform's, this
  # one only exists to hold the add-on.
  create_namespace = true

  # Helm chart 4.11.3 creates the admission webhook with two Jobs
  # (admission-create / admission-patch). wait_for_jobs makes the release block
  # until the webhook is actually serving, which is what "condition met" on the
  # Deployment alone does not guarantee.
  wait          = true
  wait_for_jobs = true
  timeout       = 600

  values = [yamlencode({
    controller = {
      # kind publishes host ports 80/443 (8090/8443 on the host) from the node
      # container, so the controller has to bind those ports on the node itself
      # rather than advertise a Service. This is exactly what the kind provider
      # manifest does, and why ../kind/kind-config.yaml maps those ports.
      hostPort = {
        enabled = true
      }

      # The single kind node carries this label (set in kind-config.yaml via
      # kubeadmConfigPatches) and is also the control plane, which is tainted.
      nodeSelector = {
        "ingress-ready" = "true"
      }
      tolerations = [
        {
          key      = "node-role.kubernetes.io/control-plane"
          operator = "Equal"
          effect   = "NoSchedule"
        }
      ]

      # A LoadBalancer Service would sit Pending forever: kind has no cloud
      # provider. NodePort plus the host ports above is the working setup.
      service = {
        type = "NodePort"
      }

      # Keep the laptop footprint predictable.
      replicaCount = 1

      # The controller publishes the node's host ports, so only one Pod can ever
      # run on the single kind node. The chart's default RollingUpdate strategy
      # surges a second Pod before the old one goes away, and that Pod stays
      # Pending forever with "didn't have free ports for the requested pod
      # ports" - which also wedges every future "helm upgrade"/"terraform
      # apply". Recreate stops the old Pod before starting the new one, which is
      # the only strategy that converges on one node. A short ingest gap during
      # the swap is expected.
      updateStrategy = {
        type = "Recreate"
      }

      # The controller only ever talks to the API server and the pods it
      # routes to; it never needs a writable root filesystem except for /tmp.
      # (readOnlyRootFilesystem is left to the chart's own defaults here - the
      # chart 4.11.3 does not mount every path the controller writes.)
      resources = {
        requests = { cpu = "100m", memory = "128Mi" }
        limits   = { cpu = "500m", memory = "512Mi" }
      }
    }

    # The default certificate is generated locally by the chart's Job.
    defaultBackend = {
      enabled = true
      resources = {
        requests = { cpu = "10m", memory = "32Mi" }
        limits   = { cpu = "100m", memory = "128Mi" }
      }
    }
  })]
}

resource "helm_release" "metrics_server" {
  name      = "metrics-server"
  namespace = "kube-system"
  chart     = "metrics-server"
  version   = var.metrics_server_version

  repository = "https://kubernetes-sigs.github.io/metrics-server/"

  # kube-system already exists on any cluster; asking Helm to create it would
  # fight with the API server owning it.
  create_namespace = false

  wait    = true
  timeout = 300

  values = [yamlencode({
    # The one setting that matters on kind: kubelet serves a self-signed
    # certificate, so metrics-server must be told not to verify it. Without
    # this every HPA in ../k8s/30-hpa.yaml reports "cpu: <unknown>" forever.
    #
    # The chart keeps its own defaults in "defaultArgs" and appends "args",
    # so this adds the flag instead of replacing the rest.
    args = ["--kubelet-insecure-tls"]

    resources = {
      requests = { cpu = "50m", memory = "64Mi" }
      limits   = { cpu = "200m", memory = "256Mi" }
    }
  })]
}
