#!/usr/bin/env bash
# Moves old capture folders into the Nocturne layout (docs/PLAN.md, "File layout rule"):
#
#   <drive>/Nocturne/<Target>/<YYYY-MM-DD>/<FrameType>/<Filter>/<file>.fits
#
# Dry run by default: prints what would move where. Nothing changes without --run.
#
#   ./migrate.sh                      dry run over $HOME
#   ./migrate.sh --run                do it
#   ./migrate.sh --src /mnt/astro/images --run
#   ./migrate.sh --only M_45 --run    one folder, even if it has no Light/Dark/Flat/Bias subfolder
#
# Old folders are named <Target>_j<N>_<yyyymmdd>_<hhmmss> (a stamp in Pi local time); a folder
# without a stamp takes the date of its newest file. The night is the local date the night began:
# the time minus 12 hours, so frames after midnight stay with the evening before. Several jobs of
# one target on one night merge into one folder. File names are never changed and nothing is
# overwritten: a name that already exists skips the rest of that folder.
#
# Data never changes drive: a folder is renamed into the Nocturne folder of the drive it is on
# (instant, nothing is copied, so nothing can be half-written). A symlink left by the old
# move-images.sh is followed to the real folder on the stick, and the symlink is removed once the
# folder is empty. Moving a night to another drive is the file manager's job (copy, then delete).
set -uo pipefail

ROOT_NAME=Nocturne
SRC=$HOME
DEST=${NOCTURNE_ROOT:-$HOME/$ROOT_NAME}
RUN=0
ONLY=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --run) RUN=1 ;;
        --src) SRC=${2:?--src needs a folder}; shift ;;
        --dest) DEST=${2:?--dest needs a folder}; shift ;;
        --only) ONLY+=("${2:?--only needs a folder name}"); shift ;;
        -h|--help) sed -n '2,/^set /p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

[[ $(basename "$DEST") == "$ROOT_NAME" ]] || { echo "--dest must end in /$ROOT_NAME" >&2; exit 2; }
[[ -d $SRC ]] || { echo "no such folder: $SRC" >&2; exit 2; }
mkdir -p "$(dirname "$DEST")"
DEST=$(readlink -f "$(dirname "$DEST")")/$ROOT_NAME

dev_of() { # device id of the nearest existing ancestor
    local p=$1
    while [[ ! -e $p ]]; do p=$(dirname "$p"); done
    stat -c %d "$p"
}
mount_of() { df --output=target "$1" | tail -n1; }
human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 B"; }

moved=0 skipped=0 failed=0

migrate_one() {
    local entry=$1 name real link=0 target night root destdir
    name=$(basename "$entry")
    real=$(readlink -f "$entry")
    [[ -L $entry ]] && link=1
    [[ -d $real ]] || return 0
    [[ $name == "$ROOT_NAME" || $real == "$DEST" || $real == "$DEST"/* ]] && return 0

    if [[ ${#ONLY[@]} -gt 0 ]]; then
        local hit=0 o
        for o in "${ONLY[@]}"; do [[ $o == "$name" ]] && hit=1; done
        ((hit)) || return 0
    else
        # Only folders that look like Ekos capture output; --only overrides this.
        local sub ok=0
        for sub in Light Dark Flat Bias; do [[ -d $real/$sub ]] && ok=1; done
        ((ok)) || return 0
    fi

    if [[ $name =~ ^(.+)_j[0-9]+_([0-9]{8})_([0-9]{6})$ ]]; then
        local d=${BASH_REMATCH[2]} t=${BASH_REMATCH[3]}
        target=${BASH_REMATCH[1]}
        night=$(date -d "${d:0:4}-${d:4:2}-${d:6:2} ${t:0:2}:${t:2:2}:${t:4:2} 12 hours ago" +%F)
    else
        local newest
        newest=$(find "$real" -type f -printf '%T@\n' | sort -n | tail -n1)
        [[ -n $newest ]] || { echo "SKIP  $name: no files"; ((skipped++)); return 0; }
        target=$name
        night=$(date -d "@$((${newest%.*} - 43200))" +%F)
    fi
    [[ -n $target && -n $night ]] || { echo "SKIP  $name: cannot read target or night"; ((skipped++)); return 0; }

    # Data on another drive than $DEST stays on its own drive, in that drive's Nocturne folder.
    root=$DEST
    if [[ $(dev_of "$real") != "$(dev_of "$DEST")" ]]; then
        root=$(mount_of "$real"); root=${root%/}/$ROOT_NAME
    fi
    destdir=$root/$target/$night

    local files bytes
    files=$(find "$real" -type f | wc -l)
    bytes=$(find "$real" -type f -printf '%s\n' | awk '{s+=$1} END{printf "%.0f", s}')
    printf '%s%s\n      -> %s   (%s files, %s)\n' "$name" "$( ((link)) && echo ' [symlink]')" "$destdir" "$files" "$(human "${bytes:-0}")"
    ((RUN)) || return 0

    local f rel out bad=0
    while IFS= read -r -d '' f; do
        rel=${f#"$real"/}
        out=$destdir/$rel
        if [[ -e $out ]]; then
            echo "      EXISTS $rel, rest of this folder skipped"
            bad=1; break
        fi
        mkdir -p "$(dirname "$out")" || { bad=1; break; }
        mv -n -- "$f" "$out" || { bad=1; break; }
    done < <(find "$real" -type f -print0)

    if ((bad)); then ((failed++)); return 0; fi
    find "$real" -depth -type d -empty -delete 2>/dev/null
    if [[ -d $real ]]; then
        echo "      left behind: $real still has something that is not a file"
    elif ((link)); then
        rm -- "$entry"
    fi
    ((moved++))
}

echo "source $SRC   destination $DEST   $( ((RUN)) && echo RUN || echo 'dry run (add --run to move)')"
shopt -s nullglob
for entry in "$SRC"/*; do
    migrate_one "$entry"
done
sync
echo "done: $moved moved, $skipped skipped, $failed failed$( ((RUN)) || echo ' (dry run, nothing changed)')"
((failed == 0))
