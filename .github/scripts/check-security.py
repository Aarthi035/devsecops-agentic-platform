#!/usr/bin/env python3
"""Checks the Phase 7 security posture of the manifests without a cluster.

The workflows/ci.yml "Security config" job runs this. It is deliberately a
script rather than a heredoc in the workflow so that the same check can be run
locally:

    python3 .github/scripts/check-security.py

Nothing here connects to Kubernetes. It parses every manifest under k8s/ and
asserts the invariants Phase 7 depends on, so a mistake that would only show up
as a Pod that will not start - or, worse, as a Pod that starts with more
permission than it needs - fails the build instead.

What it checks, and why each one is here:

  * RBAC    every workload runs under its own ServiceAccount, no token is
            mounted, and the three service accounts in k8s/rbac/ are bound to
            nothing at all. A ServiceAccount with no Role has no permissions,
            which is the correct amount for a service that never calls the API.
  * Pods    the Pod-level hardening Phase 4 introduced is still there
            (runAsNonRoot, no privilege escalation, read-only root filesystem,
            all capabilities dropped, a readiness probe) - a security phase
            that quietly lost these would be worse than none.
  * Network the namespace starts closed, every policy selects something that
            actually exists, and no rule is accidentally a "select everything"
            wildcard.
  * Secrets no ConfigMap or Secret in k8s/ carries a literal credential, and
            every envFrom reference points at an object that exists.

Exit code 0 means every check passed; 1 means at least one failure, and every
failure is printed (not just the first) so one push shows the whole picture.
"""

from __future__ import annotations

import glob
import os
import re
import sys

import yaml

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
K8S_DIR = os.path.join(REPO_ROOT, "k8s")

# The one namespace the Phase 4 manifests deploy into, and the file that holds
# the Phase 7 workload identities. Only the accounts in that file are asserted
# to be powerless: a later phase's agent needs a read-only Role of its own, and
# it will live next to its own manifests rather than in this file.
NAMESPACE = "devsecops"
SERVICE_ACCOUNTS_FILE = os.path.join("k8s", "rbac", "serviceaccounts.yaml")

WORKLOAD_KINDS = {"Deployment", "StatefulSet", "DaemonSet"}

# A key like SPRING_DATASOURCE_PASSWORD or GRAFANA_ADMIN_TOKEN must never hold
# a literal value in the repository - it belongs in a Secret. Phase 7's
# gitleaks scan is the other half of this.
#
# The exception is the plural "tokenS", which is a *count* rather than a
# credential: AGENT_LLM_MAX_OUTPUT_TOKENS ("700") bounds how much a model may
# generate. Matching the singular and leaving the plural alone keeps the rule
# about credentials instead of about spelling, and nothing is lost at the value
# level - gitleaks still scans the literal text of every one of these files.
SECRET_ISH_KEY = re.compile(r"PASSWORD|SECRET|CREDENTIAL|API_KEY|TOKEN(?!S)", re.IGNORECASE)

PROTOCOLS = {"TCP", "UDP", "SCTP"}

failures: list[str] = []
checked = {"files": 0, "documents": 0, "accounts": 0, "workloads": 0, "policies": 0}

# Filled in by the first pass, because a Deployment can be applied before the
# file that declares the ServiceAccount or ConfigMap it references.
service_accounts: dict[str, str] = {}
config_objects: set[str] = set()
pod_label_sets: list[frozenset[str]] = []
default_deny_found = False


def fail(message: str) -> None:
    failures.append(message)


def load_documents() -> list[tuple[str, dict]]:
    """Every YAML document under k8s/, with the file it came from.

    Recursive on purpose: the Phase 7 objects live in k8s/rbac/ and
    k8s/network-policies/, and a non-recursive glob is exactly the mistake that
    let them sit unapplied in the first place.
    """
    documents: list[tuple[str, dict]] = []

    paths = sorted(glob.glob(os.path.join(K8S_DIR, "**", "*.yaml"), recursive=True))
    if not paths:
        fail(f"no manifests found under {K8S_DIR}")

    for path in paths:
        relative = os.path.relpath(path, REPO_ROOT)
        checked["files"] += 1
        try:
            with open(path, encoding="utf-8") as handle:
                parsed = [doc for doc in yaml.safe_load_all(handle) if doc is not None]
        except yaml.YAMLError as error:
            fail(f"{relative}: not valid YAML: {error}")
            continue

        if not parsed:
            fail(f"{relative}: contains no YAML documents")

        for document in parsed:
            checked["documents"] += 1
            documents.append((relative, document))

    return documents


def collect(relative: str, document: dict) -> None:
    """The facts a single document cannot answer on its own."""
    kind = document.get("kind")
    metadata = document.get("metadata") or {}

    if kind == "ServiceAccount":
        service_accounts[metadata.get("name", "")] = relative

    if kind in {"ConfigMap", "Secret"}:
        config_objects.add(metadata.get("name", ""))

    if kind in WORKLOAD_KINDS:
        template = (document.get("spec") or {}).get("template") or {}
        labels = (template.get("metadata") or {}).get("labels") or {}
        pod_label_sets.append(frozenset(f"{key}={value}" for key, value in labels.items()))


def check_service_account(relative: str, document: dict) -> None:
    metadata = document.get("metadata") or {}
    name = metadata.get("name", "<unnamed>")
    checked["accounts"] += 1

    if metadata.get("namespace") != NAMESPACE:
        fail(f"{relative}: ServiceAccount/{name} is not in the '{NAMESPACE}' namespace")

    if not (metadata.get("labels") or {}).get("app.kubernetes.io/name"):
        fail(f"{relative}: ServiceAccount/{name} has no app.kubernetes.io/name label")

    # Without this the kubelet mounts the account's token into the container,
    # which is the liability the whole least-privilege arrangement removes.
    if document.get("automountServiceAccountToken") is not False:
        fail(f"{relative}: ServiceAccount/{name} does not set automountServiceAccountToken: false")


def check_workload(relative: str, document: dict) -> None:
    kind = document["kind"]
    metadata = document.get("metadata") or {}
    name = metadata.get("name", "<unnamed>")
    checked["workloads"] += 1

    if metadata.get("namespace") != NAMESPACE:
        fail(f"{relative}: {kind}/{name} is not in the '{NAMESPACE}' namespace")

    pod_spec = ((document.get("spec") or {}).get("template") or {}).get("spec") or {}
    where = f"{relative}: {kind}/{name}"

    account = pod_spec.get("serviceAccountName")
    if not account:
        fail(f"{where} sets no serviceAccountName, so it runs as 'default'")
    elif account == "default":
        fail(f"{where} runs as the 'default' ServiceAccount")
    elif account not in service_accounts:
        fail(f"{where} names ServiceAccount '{account}', which no manifest under k8s/ declares")

    # Belt and braces with the ServiceAccount's own flag: a Pod that forgot
    # this would re-introduce the mount even if the account forbids it.
    if pod_spec.get("automountServiceAccountToken") is not False:
        fail(f"{where} does not set automountServiceAccountToken: false on the Pod spec")

    pod_security = pod_spec.get("securityContext") or {}
    if pod_security.get("runAsNonRoot") is not True:
        fail(f"{where} does not set securityContext.runAsNonRoot: true")

    for entry in pod_spec.get("envFrom") or []:
        for field, kind_name in (("configMapRef", "ConfigMap"), ("secretRef", "Secret")):
            reference = entry.get(field) or {}
            ref_name = reference.get("name")
            if ref_name and ref_name not in config_objects:
                fail(f"{where} reads {kind_name}/{ref_name}, which no manifest under k8s/ declares")

    containers = (pod_spec.get("containers") or []) + (pod_spec.get("initContainers") or [])
    if not containers:
        fail(f"{where} declares no containers")
        return

    for container in containers:
        check_container(where, container)


def check_container(where: str, container: dict) -> None:
    container_name = container.get("name", "<unnamed>")
    location = f"{where} container '{container_name}'"

    # A moving tag would make "which revision is running" unanswerable, and the
    # security scans would be scanning something other than what is deployed.
    image = container.get("image")
    if not image:
        fail(f"{location} has no image")
    else:
        without_digest = image.split("@", 1)[0]
        tag = without_digest.rsplit(":", 1)[1] if ":" in without_digest.rsplit("/", 1)[-1] else ""
        if not tag:
            fail(f"{location} image '{image}' has no tag")
        elif tag == "latest":
            fail(f"{location} image '{image}' uses the mutable ':latest' tag")

    if "readinessProbe" not in container:
        fail(f"{location} has no readinessProbe")

    security_context = container.get("securityContext") or {}
    if security_context.get("allowPrivilegeEscalation") is not False:
        fail(f"{location} does not set allowPrivilegeEscalation: false")
    if security_context.get("readOnlyRootFilesystem") is not True:
        fail(f"{location} does not set readOnlyRootFilesystem: true")

    dropped = ((security_context.get("capabilities") or {}).get("drop") or [])
    if "ALL" not in [str(entry).upper() for entry in dropped]:
        fail(f"{location} does not drop ALL capabilities")

    for entry in container.get("env") or []:
        env_name = entry.get("name", "")
        if SECRET_ISH_KEY.search(env_name) and entry.get("value"):
            fail(f"{location} sets '{env_name}' to a literal value; use valueFrom.secretKeyRef")


def check_network_policy(relative: str, document: dict) -> None:
    metadata = document.get("metadata") or {}
    name = metadata.get("name", "<unnamed>")
    checked["policies"] += 1

    if metadata.get("namespace") != NAMESPACE:
        fail(f"{relative}: NetworkPolicy/{name} is not in the '{NAMESPACE}' namespace")

    spec = document.get("spec") or {}
    policy_types = spec.get("policyTypes") or []
    if not policy_types:
        fail(f"{relative}: NetworkPolicy/{name} declares no policyTypes")
    for unknown in sorted(set(policy_types) - {"Ingress", "Egress"}):
        fail(f"{relative}: NetworkPolicy/{name} has unknown policyType '{unknown}'")

    selector = spec.get("podSelector")
    if selector is None:
        fail(f"{relative}: NetworkPolicy/{name} has no podSelector")
    elif selector:
        match_labels = selector.get("matchLabels") or {}
        if not match_labels:
            fail(f"{relative}: NetworkPolicy/{name} has a podSelector with no matchLabels")
        else:
            wanted = frozenset(f"{key}={value}" for key, value in match_labels.items())
            if not any(wanted <= labels for labels in pod_label_sets):
                fail(
                    f"{relative}: NetworkPolicy/{name} selects no workload in {NAMESPACE} "
                    f"(matchLabels {dict(match_labels)})"
                )

    # A policy that lists a direction in policyTypes but no rules is a deny for
    # that direction, which is intentional here - so only a *missing* policyType
    # with rules present is wrong.
    for direction, peer_key in (("Ingress", "from"), ("Egress", "to")):
        rules = spec.get(direction.lower()) or []
        if rules and direction not in policy_types:
            fail(f"{relative}: NetworkPolicy/{name} has {direction.lower()} rules but omits '{direction}' from policyTypes")
        for rule in rules:
            check_rule(relative, name, direction.lower(), peer_key, rule)


def check_rule(relative: str, policy: str, direction: str, peer_key: str, rule: dict) -> None:
    location = f"{relative}: NetworkPolicy/{policy} {direction} rule"

    peers = rule.get(peer_key)
    if not peers:
        fail(f"{location} has no '{peer_key}' peer")
        peers = []

    for peer in peers:
        namespace_selector = peer.get("namespaceSelector")
        pod_selector = peer.get("podSelector")
        ip_block = peer.get("ipBlock")

        if namespace_selector is None and pod_selector is None and ip_block is None:
            fail(f"{location} has a peer with none of namespaceSelector, podSelector or ipBlock")
            continue

        if ip_block is not None and not ip_block.get("cidr"):
            fail(f"{location} has an ipBlock peer with no cidr")

        # {} matches everything. That is correct in the default-deny policy's
        # podSelector and wrong everywhere else, which is why it is a failure
        # here rather than a warning.
        for label, selector in (("namespaceSelector", namespace_selector), ("podSelector", pod_selector)):
            if selector is not None and not selector.get("matchLabels") and not selector.get("matchExpressions"):
                fail(f"{location} has an empty {label}, which selects every namespace/pod")

    for port in rule.get("ports") or []:
        number = port.get("port")
        if number is None:
            fail(f"{location} has a port entry with no 'port'")
        elif not isinstance(number, int) and not (isinstance(number, str) and number):
            fail(f"{location} has a non-numeric, non-named port '{number}'")
        protocol = port.get("protocol", "TCP")
        if protocol not in PROTOCOLS:
            fail(f"{location} has protocol '{protocol}', expected one of {sorted(PROTOCOLS)}")


def check_default_deny(document: dict) -> None:
    """The closed namespace is the thing every other rule is an exception to."""
    global default_deny_found

    spec = document.get("spec") or {}
    if spec.get("podSelector") or set(spec.get("policyTypes") or []) != {"Ingress", "Egress"}:
        return
    default_deny_found = True


def check_role_binding(relative: str, document: dict) -> None:
    """The Phase 7 service accounts must be bound to nothing.

    "No Role" is the grant. If a RoleBinding ever appears for one of them, the
    service has acquired permissions nobody asked for - which is the exact
    failure mode least-privilege RBAC exists to prevent.
    """
    declarers = {name for name, path in service_accounts.items() if os.path.normpath(path) == os.path.normpath(SERVICE_ACCOUNTS_FILE)}

    for subject in document.get("subjects") or []:
        if subject.get("kind") != "ServiceAccount":
            continue
        if subject.get("name") in declarers:
            fail(
                f"{relative}: {document['kind']}/{(document.get('metadata') or {}).get('name', '<unnamed>')} "
                f"grants permissions to ServiceAccount '{subject.get('name')}', which is supposed to have none"
            )


def check_no_literal_secrets(relative: str, document: dict) -> None:
    metadata = document.get("metadata") or {}
    name = metadata.get("name", "<unnamed>")

    for field in ("data", "stringData"):
        for key, value in (document.get(field) or {}).items():
            if not value:
                continue
            if SECRET_ISH_KEY.search(key):
                fail(f"{relative}: {document['kind']}/{name} sets '{key}' to a literal value; keep it out of the repository")


def main() -> int:
    documents = load_documents()

    for relative, document in documents:
        collect(relative, document)

    for relative, document in documents:
        kind = document.get("kind")
        if kind == "ServiceAccount":
            check_service_account(relative, document)
        elif kind in WORKLOAD_KINDS:
            check_workload(relative, document)
        elif kind == "NetworkPolicy":
            check_network_policy(relative, document)
            check_default_deny(document)
        elif kind == "RoleBinding":
            check_role_binding(relative, document)
        elif kind in {"ConfigMap", "Secret"}:
            check_no_literal_secrets(relative, document)

    # A phase whose whole point is a closed namespace and powerless identities
    # should not be able to lose either in a refactor without CI noticing.
    if checked["accounts"] == 0:
        fail("no ServiceAccount found anywhere under k8s/")
    if checked["policies"] == 0:
        fail("no NetworkPolicy found anywhere under k8s/")
    if not default_deny_found:
        fail(f"no default-deny NetworkPolicy found under k8s/ (podSelector: {{}} with both policyTypes)")

    if failures:
        print(f"FAILED: {len(failures)} problem(s)\n", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(
        "OK: {files} file(s), {documents} document(s), {accounts} service account(s), "
        "{workloads} workload(s), {policies} network policy(ies)".format(**checked)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
