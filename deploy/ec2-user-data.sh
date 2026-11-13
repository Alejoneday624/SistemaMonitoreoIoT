#!/bin/bash
# ============================================================================
# SistemaMonitoreoIoT — arranque automatico en EC2 (Ubuntu/Debian)
#
# COMO USARLO: en "Launch instance > Advanced details > User data"
# pegue TODO este archivo. Se ejecuta UNA vez al crear la instancia:
# instala Docker, clona el repositorio y levanta el stack completo.
#
# El codigo llega de GitHub, asi que los cambios deben estar SUBIDOS
# (git push) y el repositorio PUEBLICO antes de crear la instancia.
#
# Es idempotente: si vuelve a ejecutarse no rompe nada (solo actualiza).
# ============================================================================
set -euo pipefail

REPO="https://github.com/Alejoneday624/SistemaMonitoreoIoT.git"
APP_DIR="/opt/sistemamonitoreoiot"

# --- 1) Swap de 2 GB -------------------------------------------------------
# La instancia gratis (t2.micro) tiene 1 GB de RAM y el build Maven del
# backend dentro de Docker necesita mas: sin swap el OOM lo mata a mitad.
if ! swapon --show | grep -q '/swapfile'; then
  fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab   # persiste tras reinicios
fi

# --- 2) git/curl (la imagen Ubuntu minima NO los trae) ---------------------
if ! command -v git >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y git curl ca-certificates
fi

# --- 3) Docker + plugin de compose -----------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

# --- 4) Codigo desde GitHub ------------------------------------------------
mkdir -p "$APP_DIR"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" pull --ff-only
else
  git clone "$REPO" "$APP_DIR"
fi

# --- 5) Variables de entorno -----------------------------------------------
# WEB_PORT=80 -> el tablero queda en http://IP-PUBLICA (sin :8080)
printf 'WEB_PORT=80\n' > "$APP_DIR/.env"

# --- 6) Levantar todo ------------------------------------------------------
# --build compila el backend (puede tardar 5-10 min en t2.micro la 1a vez;
# despues el cache de capas lo hace casi instantaneo).
cd "$APP_DIR"
docker compose up -d --build

# Todo lo que pase queda en /var/log/cloud-init-output.log (lo revisa AWS).
echo "Despliegue terminado: $(date)"
docker compose ps
