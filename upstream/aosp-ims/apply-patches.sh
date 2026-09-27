#!/bin/sh
# Apply the Android 15 backport to the Android 17 ImsStack and ImsMedia that
# local_manifests/aosp-ims.xml syncs into a LineageOS 22.2 tree.
#
# Usage: upstream/aosp-ims/apply-patches.sh <LineageOS tree>
#
# The patches are aosp-ims/patches/, the same ones the flashable zip is
# built from. Each becomes a commit in its project (git am), so `repo
# status` shows them. Safe to run again, for example after a `repo sync`
# reset the projects: a patch whose subject is already in the project's
# history is skipped.
set -eu
KIT=$(cd "$(dirname "$0")/../.." && pwd)
TREE=$(cd "${1:?usage: $0 <LineageOS 22.2 tree>}" && pwd)

apply() { # apply <project path in the tree> <patch directory>
    dir=$TREE/$1
    if ! git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
        echo "$1: not synced. Add upstream/aosp-ims/local_manifests/aosp-ims.xml" \
             "to .repo/local_manifests/ and repo sync first." >&2
        exit 1
    fi
    for p in "$2"/*.patch; do
        subject=$(git mailinfo /dev/null /dev/null < "$p" | sed -n 's/^Subject: //p')
        if git -C "$dir" log --format=%s -n 200 | grep -qxF "$subject"; then
            echo "$1: already applied: $subject"
            continue
        fi
        git -C "$dir" am -q --3way "$p"
        echo "$1: applied: $subject"
    done
}

apply packages/modules/ImsStack "$KIT/aosp-ims/patches/ImsStack"
apply packages/modules/ImsMedia "$KIT/aosp-ims/patches/ImsMedia"
