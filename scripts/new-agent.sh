#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <kebab-case-directory> <agent-id> <display-name>" >&2
  exit 2
fi

directory="$1"
agent_id="$2"
display_name="$3"

if [[ ! "$directory" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "directory must use kebab-case: $directory" >&2
  exit 2
fi
if [[ ! "$agent_id" =~ ^agent\.[a-z0-9_-]+$ ]]; then
  echo "agent-id must start with agent. and be explicit: $agent_id" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/agents/$directory"
if [[ -e "$target" ]]; then
  echo "agent already exists: $target" >&2
  exit 1
fi

cp -R "$root/agent-template" "$target"
sed -i.bak -e "s/agent\.example/$agent_id/" \
  -e "s/示例助手/$display_name/" \
  -e "s/\[example\]/[$directory]/" \
  -e "s/namespace: example/namespace: $directory/" "$target/agent.yaml"
rm "$target/agent.yaml.bak"
echo "created $target"

