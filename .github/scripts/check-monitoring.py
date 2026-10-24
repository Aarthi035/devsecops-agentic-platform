#!/usr/bin/env python3
"""Checks the Phase 6 monitoring manifests without a cluster.

The workflows/ci.yml "Monitoring config" job runs this. It is deliberately a
script rather than a heredoc in the workflow so that the same check can be run
locally:

    python3 .github/scripts/check-monitoring.py

Nothing here connects to Kubernetes or pulls an image. It parses every
monitoring/*.yaml and asserts the invariants the Phase 6 work depends on, so a
manifest that would only fail at apply time fails the build instead.

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
MONITORING_DIR = os.path.join(REPO_ROOT, "monitoring")

# Terraform owns the namespaces (terraform/namespace.tf). If one of them were
# also declared here the two tools would fight over the same object, and
# "terraform plan" would report a diff nobody could reconcile.
NAMESPACES_OWNED_BY_TERRAFORM = {"monitoring", "devsecops"}

WORKLOAD_KINDS = {"Deployment", "DaemonSet", "StatefulSet"}

SECRET_ISH_ENV = re.compile(r"PASSWORD|SECRET|TOKEN", re.IGNORECASE)

SEVERITIES = {"critical", "warning", "info", "none"}

failures: list[str] = []
checked = {"files": 0, "documents": 0, "workloads": 0, "rules": 0}
alert_names: set[str] = set()


def fail(message: str) -> None:
    failures.append(message)


def load_documents() -> list[tuple[str, dict]]:
    """Every YAML document under monitoring/, with the file it came from."""
    documents: list[tuple[str, dict]] = []

    paths = sorted(glob.glob(os.path.join(MONITORING_DIR, "**", "*.yaml"), recursive=True))
    if not paths:
        fail(f"no manifests found under {MONITORING_DIR}")

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


def check_namespace_ownership(relative: str, document: dict) -> None:
    if document.get("kind") != "Namespace":
        return

    name = (document.get("metadata") or {}).get("name")
    if name in NAMESPACES_OWNED_BY_TERRAFORM:
        fail(
            f"{relative}: declares Namespace '{name}', which terraform/namespace.tf owns. "
            "Deploy into it, do not create it."
        )


def check_workload(relative: str, document: dict) -> None:
    kind = document["kind"]
    metadata = document.get("metadata") or {}
    name = metadata.get("name", "<unnamed>")

    if metadata.get("namespace") != "monitoring":
        fail(f"{relative}: {kind}/{name} is not in the 'monitoring' namespace")

    pod_spec = ((document.get("spec") or {}).get("template") or {}).get("spec") or {}
    containers = (pod_spec.get("containers") or []) + (pod_spec.get("initContainers") or [])
    if not containers:
        fail(f"{relative}: {kind}/{name} declares no containers")
        return

    for container in containers:
        check_container(relative, kind, name, container)


def check_container(relative: str, kind: str, workload: str, container: dict) -> None:
    container_name = container.get("name", "<unnamed>")
    where = f"{relative}: {kind}/{workload} container '{container_name}'"

    # Images must be pinned. ":latest" would make the running stack
    # unreproducible, and the whole point of the pinned versions in the README
    # is that the same manifests give the same stack.
    image = container.get("image")
    if not image:
        fail(f"{where} has no image")
    else:
        without_digest = image.split("@", 1)[0]
        tag = without_digest.rsplit(":", 1)[1] if ":" in without_digest.rsplit("/", 1)[-1] else ""
        if not tag:
            fail(f"{where} image '{image}' has no tag")
        elif tag == "latest":
            fail(f"{where} image '{image}' uses the mutable ':latest' tag")

    # Requests and limits, which on a laptop is the difference between a stack
    # that fits and a stack that evicts the services it is watching.
    resources = container.get("resources") or {}
    for section in ("requests", "limits"):
        values = resources.get(section) or {}
        for dimension in ("cpu", "memory"):
            if dimension not in values:
                fail(f"{where} declares no resources.{section}.{dimension}")

    # A container with no readiness probe cannot be part of a rollout gate.
    if "readinessProbe" not in container:
        fail(f"{where} has no readinessProbe")

    # Long-running containers should not be able to become root.
    security_context = container.get("securityContext") or {}
    if security_context.get("allowPrivilegeEscalation") is not False:
        fail(f"{where} does not set allowPrivilegeEscalation: false")

    # No credentials in the manifests - they belong in a Secret. Phase 7's
    # gitleaks scan exists for the same reason.
    for entry in container.get("env") or []:
        env_name = entry.get("name", "")
        if SECRET_ISH_ENV.search(env_name) and entry.get("value"):
            fail(f"{where} sets '{env_name}' to a literal value; use valueFrom.secretKeyRef")


def check_prometheus_rules(relative: str, document: dict) -> None:
    """A minimal stand-in for promtool, which is not installed on the runner.

    A ConfigMap counts as a rules file when one of its keys parses to a body
    with a top-level "groups" list - not merely because the key ends in .yml,
    which every other config in monitoring/ also does.
    """
    if document.get("kind") != "ConfigMap":
        return

    for key, body in (document.get("data") or {}).items():
        if not isinstance(body, str) or not key.endswith((".yml", ".yaml")):
            continue

        try:
            parsed = yaml.safe_load(body)
        except yaml.YAMLError as error:
            fail(f"{relative}: '{key}' is not valid YAML: {error}")
            continue

        if not isinstance(parsed, dict) or not parsed.get("groups"):
            continue

        for group in parsed["groups"]:
            if not group.get("name"):
                fail(f"{relative}: a rule group in '{key}' has no name")

            for rule in group.get("rules") or []:
                checked["rules"] += 1
                alert = rule.get("alert")
                if not alert:
                    fail(f"{relative}: a rule in group '{group.get('name')}' has no 'alert' name")
                    continue
                if alert in alert_names:
                    fail(f"{relative}: alert '{alert}' is defined more than once")
                alert_names.add(alert)

                if not rule.get("expr"):
                    fail(f"{relative}: alert '{alert}' has no 'expr'")

                severity = (rule.get("labels") or {}).get("severity")
                if severity not in SEVERITIES:
                    fail(
                        f"{relative}: alert '{alert}' has severity '{severity}', "
                        f"expected one of {sorted(SEVERITIES)}"
                    )

                if not (rule.get("annotations") or {}).get("summary"):
                    fail(f"{relative}: alert '{alert}' has no annotations.summary")


def main() -> int:
    documents = load_documents()

    for relative, document in documents:
        check_namespace_ownership(relative, document)

        if document.get("kind") in WORKLOAD_KINDS:
            checked["workloads"] += 1
            check_workload(relative, document)

        check_prometheus_rules(relative, document)

    # A phase whose whole point is alerting should not be able to lose every
    # rule in a refactor without CI noticing.
    if checked["rules"] == 0:
        fail("no Prometheus alert rules found anywhere under monitoring/")

    if failures:
        print(f"FAILED: {len(failures)} problem(s)\n", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(
        "OK: {files} file(s), {documents} document(s), {workloads} workload(s), "
        "{rules} alert rule(s)".format(**checked)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
