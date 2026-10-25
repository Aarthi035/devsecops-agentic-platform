# Ansible control node for this repository.
#
# WHY A CONTAINER
# kind publishes the Kubernetes API server on the host's loopback interface
# only:
#
#   $ docker port devsecops-control-plane
#   6443/tcp -> 127.0.0.1:52084
#
# Ansible has no control node on Windows, and a control node running inside WSL
# reaches the Windows host through the WSL gateway IP - which is not
# 127.0.0.1, so the API is unreachable from there (the port is not bound to any
# other interface). Joining the kind Docker network sidesteps the problem
# entirely: inside it the node answers to its own name, so the admin.conf server
# URL "https://devsecops-control-plane:6443" resolves and the certificate in
# that file verifies against it.
#
# BUILD AND RUN (from the repository root)
#
#   docker build -t devsecops/ansible:latest ansible
#
# Mount the *repository root* at /workspace, not just ansible/: the deploy role
# applies the manifests in k8s/, which is ansible/'s sibling, and it addresses
# them as "two directories up from the playbook" so the same path works on the
# host.
#
#   # admin.conf is the node's own kubeconfig - it contains a client
#   # certificate, so it stays outside the repository:
#   docker run --rm --network kind \
#     -v "C:/Users/<you>/devsecops-agentic-platform:/workspace" \
#     -v "C:/Users/<you>/AppData/Local/Temp/ds-iac/admin.conf:/root/.kube/config:ro" \
#     --workdir /workspace/ansible \
#     devsecops/ansible:latest -i inventory/hosts.ini playbooks/site.yml
#
# A Windows bind mount is world-writable, and Ansible skips ansible.cfg in a
# world-writable directory - so the inventory is passed with -i rather than
# relying on the config file. Nothing else in the run needs it.
#
# Debian slim rather than Alpine: musl-based images fail to exec on this
# Docker Desktop with "exec format error" - the same reason the service images
# are Debian-based (see services/*/Dockerfile).

FROM python:3.12-slim-bookworm

# openssh-client only stops ansible-core warning about a missing ssh binary;
# the playbooks use the local connection exclusively.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt requirements.yml /tmp/

# The collections go to /usr/share/ansible/collections rather than the default
# ~/.ansible/collections: that default is /root's home, and a collection tree
# under /root is invisible to the non-root user the final stage switches to.
# /usr/share/ansible/collections is the other directory ansible searches by
# default (COLLECTIONS_PATHS), so nothing has to be configured to find it.
RUN pip install --no-cache-dir "ansible-core>=2.16,<2.19" -r /tmp/requirements.txt \
    && ansible-galaxy collection install -r /tmp/requirements.yml \
         --collections-path /usr/share/ansible/collections

WORKDIR /workspace/ansible

# Phase 7: nothing in a playbook run needs root - the API credential is mounted
# read-only and the manifests are read from a bind mount - so the control node
# drops to its own unprivileged user. Trivy's DS-0002 check fails the security
# workflow on any image whose final stage has no USER, and "run the automation
# as root because it is convenient" is exactly the habit that check exists to
# catch. HOME is set explicitly because ansible keeps its temp trees under
# ~/.ansible/tmp and the local connection writes into them on every task.
RUN useradd --create-home --uid 1001 ansible
ENV HOME=/home/ansible

USER 1001

ENTRYPOINT ["ansible-playbook"]
CMD ["--help"]
