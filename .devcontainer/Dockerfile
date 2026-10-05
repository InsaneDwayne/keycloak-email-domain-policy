# Base image is kept up to date by Dependabot (docker ecosystem).
# Java 21 matches the runtime of the official Keycloak images.
FROM eclipse-temurin:24-jdk-noble

# Install Maven, git, SSH client, pre-commit and just
RUN apt-get update && \
    apt-get install -y --no-install-recommends git openssh-client maven pipx sudo curl ca-certificates nano vim&& \
    rm -rf /var/lib/apt/lists/* && \
    PIPX_HOME=/opt/pipx PIPX_BIN_DIR=/usr/local/bin pipx install pre-commit && \
    curl -fsSL https://just.systems/install.sh | bash -s -- --to /usr/local/bin

# Replace the image's default 'ubuntu' user (UID 1000) with a non-root 'vscode' user
RUN userdel -r ubuntu && \
    useradd -m -s /bin/bash -u 1000 vscode && \
    echo "vscode ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/vscode && \
    chmod 0440 /etc/sudoers.d/vscode && \
    mkdir -p /home/vscode/.m2 && \
    chown vscode:vscode /home/vscode/.m2

USER vscode
