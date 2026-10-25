# Project Report Outline

## 1. Executive summary
- Problem, proposed platform, and measurable result.
- Local-only constraint and free-tool choice.

## 2. Requirements and course outcomes
- Map CO1-CO6 to repository evidence.
- State the human-approval constraint for risky actions.

## 3. Architecture
- Include `docs/architecture.md` and explain trust boundaries.
- Describe the application, CI/CD, Kubernetes, IaC, monitoring, security, and agent paths.

## 4. Implementation by phase
- Phase 1: microservices and Git workflow.
- Phase 2: CI and artifacts.
- Phase 3: containers and Compose.
- Phase 4: kind, Ingress, HPA, rollout and rollback.
- Phase 5: Terraform and Ansible ownership.
- Phase 6: Prometheus, Grafana, Loki, Promtail and Alertmanager.
- Phase 7: Trivy, Dependency-Check, gitleaks, RBAC and NetworkPolicies.
- Phase 8: CI diagnosis and pull-request comments.
- Phase 9: incident investigation, security reports and approval gate.
- Phase 10: evaluation, telemetry, traces and demonstrations.

## 5. Agent design and guardrails
- Tool contracts and bounded investigation loop.
- Input sanitization and prompt-injection handling.
- Secrets, RBAC, approval lifecycle, audit records, step/token limits.

## 6. Evaluation method
- Describe the 12 scenarios in `evals/scenarios.json`.
- Explain diagnosis, evidence, action, tool-count, and estimated-cost scoring.
- Report the JSON output from `python evals/run_evals.py`.

## 7. Results
- Maven test counts and CI results.
- Kubernetes smoke tests and monitoring evidence.
- Grafana agent panel screenshots.
- Langfuse trace screenshots when configured.
- Include failures and limitations, not only green results.

## 8. Demonstration
- Follow `docs/demo-scripts.md`.
- Record one CI failure, one alert, one security finding, and one approved action.

## 9. Limitations and future work
- Local kind CNI enforcement, ephemeral storage, heuristic baseline, and Ollama quality.
- Persistent trace storage, remote cluster support, richer evaluation metrics, and model comparison.

## 10. Conclusion
- Summarize the feedback loop and how human approval limits operational risk.

## Appendix
- Commands, manifests, selected test output, evaluation JSON, and environment versions.
