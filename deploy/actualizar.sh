#!/bin/bash
# ============================================================================
# SistemaMonitoreoIoT — actualizacion rapida en la instancia (EC2)
#
# COMO USARLO (desde la instancia, con el stack ya desplegado):
#   curl -fsSL https://raw.githubusercontent.com/Alejoneday624/SistemaMonitoreoIoT/main/deploy/actualizar.sh | sudo bash
# o, con la copia local:
#   sudo bash /opt/sistemamonitoreoiot/deploy/actualizar.sh
#
# Baja los ultimos cambios del repo, reconstruye SOLO las imagenes que
# cambiaron (cache de Docker) y levanta el stack. Los datos (pgdata,
# mosquitto-data) se conservan siempre: no hay perdida al actualizar.
#
# Requisitos: repo publico y el despliegue inicial ya ejecutado.
# ============================================================================
set -euo pipefail

APP_DIR="/opt/sistemamonitoreoiot"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: ejecutar con sudo (la copia en $APP_DIR es de root)" >&2
  exit 1
fi

if [ ! -d "$APP_DIR/.git" ]; then
  echo "ERROR: no existe $APP_DIR (ejecuta antes el despliegue inicial)" >&2
  exit 1
fi

cd "$APP_DIR"

echo "==> Actualizando codigo (git pull)..."
git pull --ff-only

echo "==> Liberando espacio (cache de build e imagenes huerfanas)..."
# En el disco de8 GB la caché de builds acumulada puede llenarlo y romper
# el rebuild (lección real: "no space left on device"). Se limpia antes.
docker builder prune -f --keep-storage 500M >/dev/null 2>&1 || true
docker image prune -f >/dev/null 2>&1 || true

echo "==> Reconstruyendo y levantando solo lo cambiado..."
docker compose up -d --build

echo "==> Estado del stack:"
docker compose ps

echo "Actualizacion terminada: $(date)"
