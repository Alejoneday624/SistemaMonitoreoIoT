# Sistema de Monitoreo de Sensores IoT — Campus Universitario

Empaquetado en Docker del prototipo: **PostgreSQL + Mosquitto (MQTT) + Backend Spring Boot + Frontend React**.

| Servicio | Imagen / Build | Puerto | Descripción |
|---|---|---|---|
| `frontend` | `./frontend` (Vite + nginx) | **http://localhost:8080** | Tablero React con sidebar, nginx hace de proxy inverso (`/api/` → backend) |
| `backend` | `./backend` (Spring Boot, Java 21) | http://localhost:8081 | API REST + pipeline MQTT (recepción → procesamiento → persistencia) |
| `mosquitto` | `eclipse-mosquitto:2` | 1883 | Broker MQTT: estilo Pub/Sus, QoS 1, sesiones persistentes |
| `db` | `postgres:16-alpine` | — (solo red interna) | Base de datos con volumen persistente `pgdata` |

## Requisitos

- Docker Desktop con el motor encendido (`docker version` debe responder).

## Puesta en marcha

```bash
# 1. Levantar todo el stack
docker compose up --build -d

# 2. Verificar el estado (todo debe quedar "healthy")
docker compose ps

# 3. Abrir el tablero
#    http://localhost:8080
```

Comprobación rápida:

```bash
curl http://localhost:8081/api/estado
# {"sensoresTotales":12,"sensoresActivos":12,...}
```

## Mapa de topics MQTT (Tablas 33/35 del documento)

| Topic | Publica | Escucha |
|---|---|---|
| `campus/{sede}/{variable}/{sensorId}` | Simulador | Recepción (`campus/#`) |
| `interno/validadas` | Recepción | Procesamiento |
| `interno/procesadas` | Procesamiento | Persistencia, (Ronda 2: monitoreo, alertas) |
| `interno/logs` | Recepción, Procesamiento, Persistencia | Persistencia (registro de errores) |
| `control/simulador/#` | API REST (botones del panel) | Simulador (órdenes de falla) |

QoS 1 en todas las publicaciones + sesiones `cleanSession=false`: si un
suscriptor se cae, el broker **encola los mensajes** y los entrega al
reconectar (tácticas E9/E11).

Ver el flujo en vivo durante la demo:

```bash
docker exec -it campusiot-mosquitto mosquitto_sub -t 'interno/#' -v
```

## Comandos útiles

```bash
docker compose logs -f backend          # logs del backend (pipeline MQTT)
docker compose logs -f mosquitto        # logs del broker
docker compose down                     # detener (los datos persisten)
docker compose down -v                  # detener y ELIMINAR la base de datos
docker compose up --build -d            # volver a levantar
```

Desarrollo local sin Docker (frontend con hot-reload):

```bash
docker compose up -d db mosquitto       # solo dependencias
cd backend  && ./mvnw spring-boot:run   # backend en :8081
cd frontend && npm run dev              # vite en :5173 (proxea /api al backend)
```

## Estructura

```
SistemaMonitoreoIoT/
├── docker-compose.yml          # orquestacion de los 4 servicios
├── mosquitto/mosquitto.conf    # broker: QoS 1 + persistencia de sesiones
├── backend/
│   ├── Dockerfile              # multi-stage: Maven → JRE 21
│   └── src/main/java/com/campus/monitoring/
│       ├── config/MqttBus.java             # bus MQTT compartido (Pub/Sus)
│       ├── sensor/SensorSimulator.java     # 12 sensores de la Tabla 1
│       ├── recepcion/RecepcionService.java # valida formato (HDU-02/03)
│       ├── procesamiento/                  # STUB — ver tareas Ronda 2
│       ├── persistencia/                   # entidades + unico acceso a BD
│       └── api/PanelApiController.java     # endpoints del tablero
└── frontend/
    ├── nginx.conf              # SPA + proxy inverso al backend
    └── src/
        ├── api.js              # capa de acceso a la API
        ├── App.jsx             # layout con sidebar
        └── components/         # Dashboard, Sensores, ControlFallas
```

## Tareas del equipo — Ronda 2

El flujo completo **simulador → recepción → procesamiento → persistencia →
tablero** ya funciona end-to-end. Cada tarea indica el HDU, el archivo a
tocar y la señal de "listo".

| # | Tarea | HDU / Escenario | Archivo | Cómo verificar que está lista |
|---|---|---|---|---|
| 1 | Validar rangos por variable y detectar duplicados antes de reenviar | HDU-03, E3 | `backend/.../procesamiento/ProcesamientoService.java` (buscar `TODO(Ronda 2)`) | Publicar un valor de 999 °C con `mosquitto_pub` y ver que NO aparece en `/api/mediciones` pero sí en `/api/errores` |
| 2 | Evaluador de umbrales (900 ppm) → publicar en `interno/alertas` | HDU-06, E10 | `ProcesamientoService.java` | Superar 900 ppm y ver la alerta en `/api/errores` o nuevo `/api/alertas` |
| 3 | Servicio de monitoreo: heartbeat cada 30 s, marcar inactivo a los 120 s (`HEARTBEAT_SEGUNDOS`) | HDU-04, E1 | nuevo `backend/.../monitoreo/MonitoreoService.java` | Detener un sensor y ver su badge "Desactualizado" en la tabla |
| 4 | Endpoint `POST /api/demo/panic` y `POST /api/demo/restaurar` (elegir víctima al azar, publicar en `control/simulador/#`) | E1, E11 | nuevo `backend/.../demo/DemoController.java` + descomentar en `frontend/src/api.js` | Los botones del panel "Control de fallas" se activan |
| 5 | Botones del panel con banner "qué falló / qué sigue operativo / cuenta regresiva" | E1 | `frontend/src/components/ControlFallas.jsx` (buscar `TODO(Ronda 2)`) | Click → banner → dashboard sigue moviéndose |
| 6 | Toggle apagar/encender por sensor en la tabla | HDU-05 | `frontend/src/components/Sensores.jsx` (columna `Acción`) | Apagar TMP-SUR-01 y ver su gráfica congelada |
| 7 | Gestión de usuarios y roles (RBAC: admin / comunidad / mantenimiento) | HDU-10, HDU-11, E5/E6 | nuevo `security/` + endpoints `/api/admin/**` | Usuario sin rol admin recibe 403 |
| 8 | Reservas de biblioteca (50 cupos, sin superposiciones) | HDU-09 | nuevo `reservas/` + tabla `reservas` | Crear 51ava reserva y ver el rechazo |
| 9 | Filtros del registro de errores (fecha, tipo, sensor) | HDU-07 | `PanelApiController.java` (`/api/errores` ya acepta `?tipo=`) | Completar el resto de filtros y probarlos |

### Mapa de responsabilidades (quién toca qué)

- **Persona A (pipeline MQTT):** tareas 1 y 2 — solo `ProcesamientoService.java`.
- **Persona B (monitoreo y demo):** tareas 3, 4 y 6 — `MonitoreoService`, `DemoController`, columna de sensores.
- **Persona C (frontend):** tarea 5 — panel de fallas y banner.
- **Persona D (seguridad y reservas):** tareas 7, 8 y 9.

> Regla para no pisarse: cada tarea vive en su propio archivo/paquete; si
> necesitas tocar `MqttBus`, `application.properties` o `docker-compose.yml`,
> avísalo en el grupo antes de editar.

## Notas

- Los tests de Spring (`@SpringBootTest`) requieren la base de datos, por eso
  la imagen se construye con `-DskipTests`.
- `backend/src/main/resources/application.properties` usa variables de entorno
  con valores por defecto locales (`MQTT_URL`, `HEARTBEAT_SEGUNDOS`, `UMBRAL_PPM`).
- Las credenciales se manejan por archivo `.env`, no quedan fijas en el código
  (la plantilla `.env.example` trae valores de ejemplo, no los reales).
- Para usar **Supabase** (u otra base externa) define en `.env`:
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y
  `SPRING_DATASOURCE_PASSWORD` y levanta `docker compose up -d backend`.
  El servicio `db` local sigue arrancando pero queda sin usar. Detalle en
  `.env.example`.
- La validación de duplicados materializa una restricción única
  `(sensor_id, fecha)` en la tabla `mediciones`.

---

**Materia:** Arquitectura de Software · **Grupo:** Jonathan Guzman, Katherine Buitrago,
Juan Jose Mosquera, Jovany Gutierrez Vergara · **Docente:** Juan Pablo Huertas Méndez
Universidad Antonio Nariño — Ingeniería de Software, 2026
