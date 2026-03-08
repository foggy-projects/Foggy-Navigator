@echo off
title Code-Server-WSL
wsl -d Ubuntu-24.04 -- bash -c "export XDG_DATA_HOME=/mnt/d/foggy-tools/code-server-data; /mnt/d/foggy-tools/code-server/bin/code-server --config /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/code-server/config.yaml /mnt/d/foggy-projects/Foggy-Navigator-wt-qd-win11-dev > /mnt/d/foggy-tools/code-server-data/code-server.log 2>&1"
