# Prerequisites Checklist

Everything below is free and runs locally. Target: a laptop with **16 GB RAM (8 GB absolute minimum), 4 CPU cores, 30 GB free disk**.

Two environments are used in this project:

- **Windows host** — editor (VS Code), browser for GitHub.
- **WSL2 Ubuntu (recommended working environment)** — git, Java, Maven, Docker CLI, kind, kubectl, Terraform, Ansible, Trivy, Ollama.

> **Keep the Windows clone on a short, plain-ASCII path.**
> Maven/Java and OneDrive both misbehave when the project path contains non-ASCII characters (e.g. `เอกสาร`) or is synced: `mvn` fails with `Cannot create resource output directory: ...\target\classes`. Use something like `C:\Users\<you>\devsecops-agentic-platform` — never a path under `OneDrive\เอกสาร\`.
>
> **Why WSL2 for Phases 3-6?**
> Docker builds, kind clusters, and the monitoring stack are Linux-native. Building from inside WSL2 home (`~/`) is 5-10x faster than building from `/mnt/c/...`, and it avoids CRLF and file-permission problems in Maven/Docker. Clone into `~/devsecops-agentic-platform` inside WSL2 for Phases 3-6.

---

## 1. Required tools

| # | Tool | Version to install | Why we need it | Phase |
|---|------|--------------------|----------------|-------|
| 1 | WSL2 + Ubuntu | Ubuntu 22.04 LTS or 24.04 LTS | Linux environment for all tooling | 1 |
| 2 | Docker Desktop | 4.30+ with WSL2 backend | Build/run containers, kind node runtime | 3 |
| 3 | Docker Compose | v2.20+ (bundled with Docker Desktop) | Local multi-container stack | 3 |
| 4 | Git | 2.40+ | Version control, Git workflow | 1 |
| 5 | JDK | Temurin **17** (LTS) | App + agent compile/run (pinned: Java 17) | 1 |
| 6 | Maven | 3.9+ | Builds, tests, dependency scan plugin | 1 |
| 7 | kubectl | 1.29+ | Talk to the kind cluster | 4 |
| 8 | kind | 0.22+ | Local Kubernetes cluster in Docker | 4 |
| 9 | Helm | 3.14+ | Install trimmed monitoring stack (Phase 6, optional) | 6 |
| 10 | Terraform | 1.6+ | Namespaces + infra as code | 5 |
| 11 | Ansible | ansible-core 2.15+ | Environment configuration / deployment | 5 |
| 12 | Trivy | 0.50+ | Image and filesystem CVE scanning | 7 |
| 13 | gitleaks | 8.18+ | Secret scanning | 7 |
| 14 | GitHub CLI `gh` | 2.40+ | Read failed workflow logs, post PR comments | 8 |
| 15 | jq | 1.6+ | Parse Trivy/JSON reports in scripts | 7-10 |
| 16 | Ollama + a small model | latest, model `llama3.2:3b` | Free local LLM for the agent | 8 |
| 17 | Langfuse | self-hosted via Docker Compose | Agent tracing and cost/latency evals | 10 |
| 18 | VS Code | latest (+ WSL, Java Pack, Docker, YAML extensions) | Editing, debugging | 0 |

Optional / alternative: **Minikube** (instead of kind), **SonarQube** community edition in Docker (instead of OWASP Dependency-Check), **PostgreSQL** in Docker (instead of H2).

Hardware budget check — the full stack in Phase 6+ costs roughly:
`kind cluster 3-4 GB` + `monitoring stack 2 GB` + `Spring services 1.5 GB (6 x 256 MB)` + `Ollama llama3.2:3b 2.5 GB` + `Docker Desktop 2 GB` ≈ **11-12 GB**. Close browser tabs and keep replicas at 1.

---

## 2. Accounts and secrets

| Item | Needed for | Notes |
|------|-----------|-------|
| GitHub account | Repo, Actions, GHCR, PR comments | Free plan is enough |
| GitHub Personal Access Token (classic) | Agent tools `fetchCiLogs`, `postGithubComment` | Scopes: `repo`, `workflow`, `read:packages`. Store as env var only — never commit |
| GHCR access | Push/pull images | Automatic via `GITHUB_TOKEN` in Actions; for local `docker login ghcr.io` use the PAT with `write:packages` |

---

## 3. Install — Windows host

```powershell
winget install --id Microsoft.WSL -e
winget install --id Docker.DockerDesktop -e
winget install --id Git.Git -e
winget install --id Microsoft.VisualStudioCode -e
```

Then, in an elevated PowerShell once: `wsl --install -d Ubuntu-22.04`, reboot, set your Linux username/password, and enable **Settings > Resources > WSL Integration > Ubuntu** in Docker Desktop.

---

## 4. Install — inside WSL2 Ubuntu

```bash
# --- base packages ---
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl unzip zip jq git build-essential ca-certificates gnupg lsb-release apt-transport-https

# --- JDK 17 (Temurin) ---
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-17-jdk

# --- Maven 3.9 ---
sudo apt install -y maven

# --- kubectl ---
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl

# --- kind ---
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.22.0/kind-linux-amd64
chmod +x ./kind && sudo mv ./kind /usr/local/bin/kind

# --- Helm ---
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# --- Terraform ---
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/hashicorp.gpg
echo "deb [signed-by=/etc/apt/keyrings/hashicorp.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt update && sudo apt install -y terraform

# --- Ansible ---
sudo apt install -y ansible

# --- Trivy ---
sudo apt install -y wget
wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo gpg --dearmor -o /etc/apt/keyrings/trivy.gpg
echo "deb [signed-by=/etc/apt/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb generic main" \
  | sudo tee /etc/apt/sources.list.d/trivy.list
sudo apt update && sudo apt install -y trivy

# --- gitleaks ---
GITLEAKS_VERSION=8.18.4
curl -sSL "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz" \
  | sudo tar -xz -C /usr/local/bin gitleaks

# --- GitHub CLI ---
(type -p wget >/dev/null || sudo apt install wget -y) \
  && sudo mkdir -p -m 755 /etc/apt/keyrings \
  && wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
  && sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
  && sudo apt update && sudo apt install gh -y

# --- Ollama (install script) ---
curl -fsSL https://ollama.com/install.sh | sh
ollama pull llama3.2:3b
```

---

## 5. Verification commands

Run all of these inside WSL2. Nothing here should error before you say "next".

```bash
java -version                 # expect: openjdk version "17.x"
mvn -version                  # expect: Apache Maven 3.9.x
git --version                 # expect: git version 2.4x
docker version                # expect: Client + Server sections (Server = WSL2 engine)
docker compose version        # expect: Docker Compose version v2.2x
kubectl version --client      # expect: Client Version v1.29+
kind --version                # expect: kind v0.22+
helm version --short          # expect: v3.14+
terraform -version            # expect: Terraform v1.6+
ansible --version             # expect: ansible [core 2.15+]
trivy --version               # expect: Version: 0.5x
gitleaks version              # expect: 8.18.x
gh --version                  # expect: gh version 2.4x
jq --version                  # expect: jq-1.6+
ollama --version && ollama list   # expect: llama3.2:3b listed
```

Smoke test that Docker + kind actually work end to end:

```bash
docker run --rm hello-world
kind create cluster --name prereq-check --wait 60s
kubectl get nodes            # expect: 1 node, STATUS Ready
kind delete cluster --name prereq-check
```

Ollama smoke test (it must answer, and it must be reachable from containers later):

```bash
# Ollama listens on 127.0.0.1:11434 by default. Expose it for Docker/kind on the host:
sudo mkdir -p /etc/systemd/system/ollama.service.d
sudo tee /etc/systemd/system/ollama.service.d/override.conf >/dev/null <<'EOF'
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
EOF
sudo systemctl daemon-reload && sudo systemctl restart ollama
curl -s http://localhost:11434/api/tags | jq '.models[].name'
curl -s http://localhost:11434/api/generate -d '{"model":"llama3.2:3b","prompt":"Reply with OK","stream":false}' | jq -r '.response'
```

---

## 6. Common errors and fixes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `docker: command not found` in WSL2 | Docker Desktop WSL integration off | Docker Desktop → Settings → Resources → WSL Integration → enable for Ubuntu, then restart the terminal |
| `Cannot connect to the Docker daemon` | Docker Desktop not running, or you're in a WSL distro without integration | Start Docker Desktop; verify with `docker info` |
| `kind create cluster` hangs / nodes `NotReady` | Not enough RAM or WSL memory cap too low | Create `C:\Users\<you>\.wslconfig` with `[wsl2]` `memory=10GB`, `processors=4`, then `wsl --shutdown` and retry |
| Maven build extremely slow, `target/` errors | Project cloned under `/mnt/c/...` (Windows FS) | Clone into `~/` inside WSL2 (`git clone ... ~/devsecops-agentic-platform`) |
| `error: invalid path` / weird filenames after checkout | Windows filename restrictions, CRLF | Clone inside WSL2; `.gitattributes` in this repo forces LF for scripts and YAML |
| `permission denied` running `.sh` scripts | Files on `/mnt/c` are mounted without exec bit | Run from `~/`, or `chmod +x script.sh`, or invoke with `bash script.sh` |
| `gh: command not found` or 401 from the agent | CLI not installed / PAT not exported | Install `gh`; `export GITHUB_TOKEN=...` (never commit it) |
| Trivy DB download fails behind a proxy | Corporate proxy or restricted network | Set `HTTPS_PROXY`, or pre-download the DB (`trivy image --download-db-only`) on an open network |
| Ollama OOM / very slow first answer | Model too large for RAM | Stick to `llama3.2:3b` (or `qwen2.5:3b`); keep `num_ctx` small; first call always warms the model |
| `terraform init` fails verifying provider checksums | Stale plugin cache | `rm -rf ~/.terraform.d/plugins` then `terraform init -upgrade` |
| Ansible refuses to run playbook | Trying to run from inside the repo with wrong cwd | Run playbooks with `-i ansible/inventory/hosts.ini` from the repo root |
| Port already in use (`8080`, `9090`, `3000`) | Another container/service bound | `docker ps`, then change the host port mapping |
| WSL2 disk filling up fast | Docker images + kind + monitoring volumes | `docker system prune -a`, `kind delete cluster`, `docker volume prune` |

---

## 7. Pre-Phase-1 checklist

- [ ] WSL2 Ubuntu installed and Docker Desktop WSL integration enabled
- [ ] All commands in section 5 pass
- [ ] `kind` cluster is created **and deleted** successfully in the smoke test
- [ ] JDK is exactly 17 (`java -version` shows 17.x)
- [ ] GitHub repo created and cloned inside WSL2
- [ ] GitHub PAT created and exported as `GITHUB_TOKEN` (not committed)
- [ ] `llama3.2:3b` pulled and answered a prompt
- [ ] At least 15 GB free disk space
