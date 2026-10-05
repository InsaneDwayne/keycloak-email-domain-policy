# List available recipes
default:
    @just --list

# One-time setup: install the git hooks (run automatically when the dev container is created)
setup:
    pre-commit install --install-hooks

# Run all pre-commit hooks against the whole repository
lint:
    pre-commit run --all-files

# Update pre-commit hook versions
update-hooks:
    pre-commit autoupdate

# Build the provider JAR and run the unit tests
build:
    mvn -B verify

# Refresh the bundled disposable email domain list
update-domain-list:
    scripts/update-domain-list.sh
