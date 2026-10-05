# Development

The [README](../README.md) covers building. This page describes the dev
container.

## Dev container

The repository includes a [dev container](../.devcontainer/devcontainer.json)
with Java, Maven and `just`. It mounts these from your home directory:

| Path                  | Used for                                         |
| --------------------- | ------------------------------------------------ |
| `~/.gitconfig`        | Your Git identity and settings.                  |
| `~/.gitconfig-github` | Optional. Settings for GitHub repositories only. |
| `~/.ssh`              | SSH keys for Git remotes, mounted read-only.     |

You don't need any of them. Before the container starts, missing ones are
created empty on your machine, so the container starts either way. An empty
file changes nothing. This step needs a POSIX shell on the host: Linux,
macOS or WSL.

## A separate commit email for GitHub

You can commit with your GitHub noreply address in GitHub repositories and
with your normal address everywhere else. This needs Git 2.36 or newer.

Add this to `~/.gitconfig`:

```ini
[includeIf "hasconfig:remote.*.url:https://github.com/**"]
	path = ~/.gitconfig-github
[includeIf "hasconfig:remote.*.url:git@github.com:*/**"]
	path = ~/.gitconfig-github
```

Then put your noreply address in `~/.gitconfig-github`. You find it under
_GitHub → Settings → Emails_.

```ini
[user]
	email = <id>+<username>@users.noreply.github.com
```

To check which address a repository uses and where it comes from, run this
inside it:

```sh
git config --show-origin user.email
```

The include only applies once the repository has a GitHub remote, so add the
remote before the first commit. Existing commits keep their address.
