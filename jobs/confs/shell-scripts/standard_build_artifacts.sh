#!/bin/bash -e
echo "shell-scripts/standard_build_artifacts.sh"
# PARAMETERS
#
# project
#     Name of the project it runs on, specifically the dir where the code
#     has been cloned
#
# distro
#     Distribution it should create the rpms for
#     (usually el<version>, fc<version>)
#
# arch
#     Architecture to build the packages for

distro="{distro}"
arch="{arch}"
project="{project}"
WORKSPACE="$PWD"

cd "./$project"
"$WORKSPACE"/jenkins/mock_configs/mock_runner.sh \
    --build-only \
    --mock-confs-dir "$WORKSPACE"/jenkins/mock_configs \
    --try-proxy \
    "$distro.*$arch"
