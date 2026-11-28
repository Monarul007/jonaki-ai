#!/bin/sh
# Reject any commit message that attributes the work to an agent, bot or tool.
#
# The rule is in AGENTS.md; this makes it impossible to break by accident. Installed as
# .git/hooks/commit-msg by tools/install-hooks.sh.
#
# Written in POSIX sh using BUILT-INS (read, case) rather than external tools. Not stylistic: on this
# machine a hook invoked without a sane PATH could not find grep, cat or ls, the checks silently
# "passed", and the hook looked installed while enforcing nothing.
#
# Rules, deliberately narrow:
#   - only the FIRST line of a contiguous attribution block is judged, and only if it is at the END of
#     the message. A commit whose body DISCUSSES attribution (quoting the forbidden line while
#     explaining why it is banned) must not be blocked — that happened to the commit that introduced
#     this hook, which is how the false positive was found.
#   - a HUMAN co-author is still allowed; only agent/bot/tool names are rejected.

msg_file="$1"
[ -f "$msg_file" ] || exit 0

# Read the last non-empty line. An attribution trailer is the last thing in a message; text earlier in
# the body is prose.
last=""
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    *[![:space:]]*) last="$line" ;;
  esac
done < "$msg_file"

lower=$(printf '%s' "$last" | tr '[:upper:]' '[:lower:]' 2>/dev/null)
[ -n "$lower" ] || lower="$last"

status=0
case "$lower" in
  *co-authored-by:*commandcode*|*co-authored-by:*noreply*|*co-authored-by:*bot*|*co-authored-by:*agent*|\
  *co-authored-by:*copilot*|*co-authored-by:*cursor*|*co-authored-by:*claude*|\
  *co-authored-by:*gpt*|*co-authored-by:*gemini*)
    status=1
    ;;
  *noreply@commandcode*|*noreply@anthropic*)
    status=1
    ;;
  "generated with "*|"made with "*|"created with "*|"written with "*)
    case "$lower" in
      *commandcode*|*claude*|*copilot*|*cursor*|*chatgpt*|*agent*|*gpt*) status=1 ;;
    esac
    ;;
esac

if [ "$status" -ne 0 ]; then
  echo "commit-msg: the last line of this message attributes the work to a tool:" >&2
  echo "  $last" >&2
  echo "" >&2
  echo "The commit belongs to the repository owner. See AGENTS.md, 'Commit messages: NO agent attribution'." >&2
  echo "Remove that line and commit again." >&2
fi
exit "$status"
