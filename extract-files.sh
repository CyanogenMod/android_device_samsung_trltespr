#!/bin/sh

set -e

export VENDOR=samsung
export DEVICE=trltespr
./../../$VENDOR/trlte-common/extract-files.sh $@
