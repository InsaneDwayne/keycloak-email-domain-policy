# Stock Keycloak image plus the release JAR. Replace <sha> with the value from
# the release's .sha256 file.
FROM quay.io/keycloak/keycloak:26.2.5
ADD --chmod=644 --checksum=sha256:<sha> \
  https://github.com/InsaneDwayne/keycloak-email-domain-policy/releases/download/v0.1.0/keycloak-email-domain-policy-0.1.0.jar \
  /opt/keycloak/providers/
