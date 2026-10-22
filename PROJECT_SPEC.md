# PROJECT SPEC: Agentic AI-Driven DevSecOps Pipeline for a Containerized Microservices Application

## 1. Overview
Build an end-to-end DevOps platform for a Java microservices application, plus an agentic AI assistant that automatically investigates CI/CD failures, monitoring alerts, and security scan results, finds the root cause, and recommends fixes. Risky actions (rollback, restart, deploy) require human approval.

This is a university project. It must run fully on a local laptop using free tools (no paid cloud).

## 2. Problem statement
Manual build, deployment, and monitoring is slow and error-prone. When a pipeline fails, a vulnerability is flagged, or a service misbehaves in production, developers waste time reading long logs and switching between CI, monitoring, and security tools. This delays fixes, increases downtime, and lets vulnerabilities slip through.

## 3. Solution
An automated pipeline: code pushed to GitHub is built, tested, scanned, containerized, and deployed to Kubernetes. Infrastructure is defined as code. Metrics and logs are collected and visualized. An AI agent is triggered by failures/alerts, investigates using tools, and returns a diagnosis and recommended action, with human approval for anything risky.

## 4. Course outcomes the project MUST satisfy
- CO1: Implement DevOps principles (Git workflow, automation, feedback loops)
- CO2: Set up and manage CI/CD pipelines
- CO3: Demonstrate containerization and orchestration tools
- CO4: Illustrate automated infrastructure and configuration management
- CO5: Construct monitoring and logging solutions
- CO6: Apply security practices in DevOps

## 5. Tech stack
- Application: 3 Spring Boot microservices (Java 17, Maven): user-service, product-service, order-service. REST APIs, Spring Data JPA, H2 (or PostgreSQL), JUnit 5, Spring Actuator + Micrometer Prometheus metrics. order-service calls the other two via REST.
- Version control and CI/CD: Git, GitHub, GitHub Actions
- Containers: Docker (multi-stage builds), Docker Compose, Kubernetes via kind (or Minikube)
- IaC and configuration: Terraform, Ansible
- Monitoring and logging: Prometheus, Grafana, Loki, Promtail, Alertmanager
- Security: Trivy (image scan), OWASP Dependency-Check or SonarQube, gitleaks (secret scan), GitHub Secrets, Kubernetes RBAC, NetworkPolicies, non-root containers
- Agent: Spring Boot service using Spring AI or LangChain4j (Java), LLM via Ollama (small local model) or a free-tier API, Langfuse for tracing
- Environment: Linux/WSL2, Docker Desktop

## 6. System architecture (flow)
1. Developer pushes code to GitHub
2. GitHub Actions: build, unit tests, dependency/code scan, Docker build, Trivy scan, push image to GitHub Container Registry
3. Deploy to Kubernetes (kind). Namespaces and infra created by Terraform, environment configured by Ansible
4. Prometheus scrapes service metrics, Promtail ships logs to Loki, Grafana shows dashboards, Alertmanager fires alerts
5. On pipeline failure, alert, or vulnerability report, a webhook calls the Agent Service
6. Agent investigates with tools and produces a diagnosis and suggested action
7. Human approves via an approval endpoint; approved action executes (rollback, restart, PR comment)
8. Outcomes feed the agent's evaluation set

## 7. Agent design
Name: DevOps Assistant Agent (separate Spring Boot service, containerized and deployed on Kubernetes)

Triggers:
- Failed GitHub Actions run (final job with `if: failure()` calls POST /analyze-ci)
- Alertmanager webhook (POST /alert)
- Trivy/SonarQube report (POST /analyze-security)

Tools the agent can call:
- fetchCiLogs: GitHub API, gets logs of the failed workflow run
- queryPrometheus: runs PromQL queries over HTTP
- queryLoki: runs LogQL queries over HTTP
- kubectlReadOnly: get/describe/logs for pods, deployments, events (read-only)
- readSecurityReport: parses Trivy JSON output
- postGithubComment: comments diagnosis on the PR/issue
- proposeAction: creates a pending action (rollback/restart/scale) awaiting approval

Agent loop: plan -> call tool -> observe result -> reason -> repeat until confident -> output final structured JSON {root_cause, evidence, suggested_fix, recommended_action, confidence}.

Guardrails:
- Read-only by default
- Human approval required before any state-changing action (POST /approve/{actionId})
- Max steps per run and max token limit
- Dedicated Kubernetes ServiceAccount with least-privilege RBAC
- Audit log of every tool call and decision
- Secrets only via environment variables/Kubernetes Secrets, never in code or prompts
- Truncate/sanitize logs before sending to the LLM

## 8. Modules
1. Microservices application
2. CI/CD pipeline
3. Containerization and Kubernetes deployment
4. Infrastructure as Code and configuration management
5. Monitoring and logging
6. DevSecOps (scanning, secrets, RBAC)
7. Agentic AI incident and pipeline assistant
8. Agent evaluation and tracing

## 9. Suggested repository structure
devsecops-agentic-platform/
- services/ (user-service, product-service, order-service, agent-service)
- k8s/ (base manifests per service, monitoring, rbac, network-policies)
- terraform/
- ansible/ (playbooks, inventory)
- monitoring/ (prometheus rules, grafana dashboards, loki and promtail config, alertmanager config)
- .github/workflows/ (ci.yml, cd.yml, security.yml)
- evals/ (failure scenarios, expected root causes, runner script)
- docs/ (architecture diagram in Mermaid, report outline, demo scripts)
- docker-compose.yml
- README.md and PROJECT_SPEC.md

## 10. Implementation phases (build ONE at a time)
Phase 1: Microservices app + Git workflow (main/dev/feature branches, PR template, .gitignore)
Phase 2: CI pipeline (build matrix, tests, caching, artifacts, README badge)
Phase 3: Dockerfiles (multi-stage, non-root), docker-compose, push images to GHCR
Phase 4: Kubernetes manifests (Deployment, Service, ConfigMap, Secret, probes, resource limits, HPA, Ingress) on kind, plus CD and rollback steps
Phase 5: Terraform (namespaces and infra) and Ansible (environment config and deployment)
Phase 6: Monitoring and logging (Prometheus, Grafana dashboards, Loki, Promtail, Alertmanager, alert rules)
Phase 7: DevSecOps (dependency/code scan, Trivy failing on HIGH/CRITICAL, gitleaks, secrets handling, RBAC, NetworkPolicies)
Phase 8: Agent v1 (CI log analysis tool, /analyze-ci endpoint, PR comment, Dockerize, deploy)
Phase 9: Agent v2 (Prometheus, Loki, kubectl, Trivy tools, /alert webhook, approval gate, audit log, scoped RBAC)
Phase 10: Evals, Langfuse tracing, Grafana panel for agent latency/cost, demo scripts, README, architecture diagram, report

## 11. Demo scenarios
- Break a unit test: agent explains the failure and suggests the fix
- Add a vulnerable dependency: pipeline fails, agent summarizes Trivy findings with upgrade advice
- Crash a pod or spike CPU/memory: Alertmanager triggers the agent, it reads metrics/logs and recommends restart or scale-up
- Agent proposes a rollback; human approves; rollback executes

## 12. Evaluation
Create 12 failure scenarios (failing test, compile error, bad dependency, OOMKilled pod, crash loop, high latency, vulnerable image, misconfigured env var, failed health probe, etc.), each with the expected root cause. A script runs the agent on each and scores correct diagnosis, correct tool use, step count, and cost. Run it as a CI job.

## 13. Constraints
- Everything must run locally on a laptop with free tools. Keep resource usage low (small LLM, minimal replicas, trimmed monitoring stack)
- Java is my strongest language; keep the app and agent in Java
- Keep the agent scope small but solid, with proper guardrails

## 14. How to work with me
- Do ONE phase at a time and wait for me to say "next"
- For each phase give: what we build and why, final folder structure, complete file contents (no placeholders or "..."), exact commands, how to verify it works, common errors and fixes, and which CO it satisfies
- Provide complete corrected code blocks
- Keep explanations short and practical
- Commit after each phase with a clear message
- If something fails, I will paste the error and you fix it
