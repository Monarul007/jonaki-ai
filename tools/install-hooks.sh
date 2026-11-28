#!/bin/sh
# Install the repo's git hooks. Run once after cloning:  sh tools/install-hooks.sh
#
# .git/hooks is not versioned, so the hook itself has to live in the repository and be copied into
# place. Everything here is idempotent.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
hooks="$root/.git/hooks"

mkdir -p "$hooks"
cp "$here/commit-msg-no-attribution.sh" "$hooks/commit-msg"
chmod +x "$hooks/commit-msg"
echo "installed: .git/hooks/commit-msg (rejects agent attribution in commit messages)"
