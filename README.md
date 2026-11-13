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

### URLs del sistema

| URL | Qué es |
|---|---|
| **http://localhost:8080** | **Tablero React (entrada al sistema)**: sidebar, gráficas, control de fallas |
| `http://localhost:8081/api/...` | API REST directa, para `curl` / Postman |
| `http://localhost:8081/` | Redirige al tablero (ya no aparece la página Whitelabel de error) |

Una ruta inexistente responde **404** con el formato que pida el cliente:

- `curl` / `fetch` (`Accept: */*`) → JSON:
  `{"timestamp":"…","status":404,"error":"Not Found","path":"/api/noexiste"}`
- Navegador (`Accept: text/html`) → página legible con la ruta pedida y los
  endpoints disponibles.

Para diagnosticar qué se pidió (y desde cuándo), el access log del backend:

```bash
docker exec campusiot-backend sh -c "tail -f /tmp/access_log.*.log"
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

## Persistencia de colas por servicio (E9/E11)

Nada se pierde aunque se caiga un componente: cada servicio guarda en
**PostgreSQL** (tabla `cola_mensajes`) antes de confiar en la red.

| Cola | Se activa cuando... | Qué garantiza |
|---|---|---|
| `salida` (outbox) | **se cae el broker** | `MqttBus.publicar()` guarda el mensaje primero y publica esperando el PUBACK; sin confirmación el mensaje espera en cola y se entrega en orden (FIFO) al reconectar. Si cae el **backend**, la cola vive en la BD y al volver se vacía. |
| `entrada:{cliente}/{topic}` (inbox) | **se cae un servicio** | El mensaje se registra **antes** de que Paho mande el PUBACK; si el proceso muere después, al arrancar reprocesa lo que quede `PENDIENTE`. (El broker por su parte reentrega lo no confirmado: doble seguro.) |

Detalles:

- Si una cola llega al tope (`COLA_MAX_PENDIENTES`, 5000) se descartan los
  mensajes **más viejos** (para telemetria lo valioso es lo reciente).
- Un manejador que falle reintenta `COLA_REINTENTOS_MAX` (5) veces y luego
  queda `FALLIDO` sin bloquear a los mensajes que vienen detrás; los FALLIDO
  se purgan tras `COLA_FALLIDOS_DIAS` (7).
- Están en la base de datos y no en archivos porque en PaaS (Render) el disco
  es efímero: así la cola sobrevive a redeploys completos.

Demo manual de la caida del broker:

```bash
docker compose stop mosquitto      # se cae el broker
# las publicaciones se acumulan en la cola en vez de perderse:
docker compose exec db psql -U postgres -d campusiot \
  -c "select count(*) from cola_mensajes where estado='PENDIENTE';"
docker compose start mosquitto     # vuelve el broker
# la cola baja a 0 y el tablero recupera el flujo solo
```

## Tests (requieren Docker encendido)

```bash
cd backend && ./mvnw test      # en Windows: mvnw.cmd
```

La suite levanta **Postgres y Mosquitto reales** (Testcontainers) y prueba el
comportamiento en averias:

| Test | Qué comprueba |
|---|---|
| `ColaServiceTest` | orden FIFO, tope de la cola, reintentos → `FALLIDO` |
| `MqttBusColasTest` | **para el broker → publica 5 → quedan en cola → arranca el broker → las 5 llegan al pipeline sin perderse**; mensaje huérfano del inbox se reprocesa al drenar; mensaje tóxico no bloquea la cola |
| `MonitoringApplicationTests` | la aplicación completa arranca con el stack real (ya no hace falta una BD local) |

> En la imagen de Docker se sigue construyendo con `-DskipTests` (no hay
> Docker dentro del build); los tests se corren en local o en CI. En GitHub
> Actions (`ubuntu-latest`) Docker viene listo: basta un paso
> `mvn -f backend/pom.xml test`.

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
│       ├── config/MqttBus.java             # bus MQTT + colas salida/entrada
│       ├── cola/                           # colas duraderas (outbox/inbox)
│       ├── sensor/SensorSimulator.java     # 12 sensores de la Tabla 1
│       ├── recepcion/RecepcionService.java # valida formato (HDU-02/03)
│       ├── procesamiento/                  # STUB — ver tareas Ronda 2
│       ├── persistencia/                   # entidades + unico acceso a BD
│       └── api/PanelApiController.java     # endpoints del tablero
└── frontend/
    ├── nginx.conf              # SPA + proxy inverso al backend
    ├── vercel.json             # rewrites /api -> Render (PLACEHOLDER)
    └── src/
        ├── api.js              # capa de acceso a la API
        ├── App.jsx             # layout con sidebar
        └── components/         # Dashboard, Sensores, ControlFallas
```

## Despliegue en AWS (EC2 — gratis 12 meses)

El mismo `docker-compose.yml` de local corre **tal cual** en una instancia
EC2 del plan gratuito: Postgres (con la cola `cola_mensajes`), Mosquitto,
backend y nginx sirviendo el tablero, todo en UNA instancia.

```
Internet ──▶ EC2 t2.micro (Security Group: puertos 22 y 80)
             ├── frontend  nginx :80  ──▶ /api y /actuator (red interna)
             ├── backend   Spring Boot ──▶ db:5432 y mosquitto:1883 (interno)
             ├── db        PostgreSQL  ──▶ volumen EBS (las colas sobreviven)
             └── mosquitto QoS 1       ──▶ volumen EBS (sesiones persisten)
```

**Costo:** gratis 12 meses (t2.micro + 30 GB EBS + 100 GB de salida de
datos/mes). Pasado ese plazo, si se deja encendida cuesta ~US$8-10/mes:
terminado el curso, **Stop instance** (no cobra y conserva todo) o
**Terminate** (borra todo). AWS pide tarjeta para crear la cuenta aunque
sea free tier.

### Pasos en la consola

**0. Requisitos previos** (una sola vez):

- El repositorio en GitHub debe estar **público** (la instancia lo clona sin
  credenciales): GitHub → `SistemaMonitoreoIoT` → *Settings → Danger Zone →
  Change visibility → Public*.
- Los cambios subidos (`git push`) — si no, la instancia clonará código viejo.

**1. Lanzar la instancia**

1. Consola AWS → **EC2 → Launch instance**.
2. Name: `campusiot`.
3. AMI: **Ubuntu Server 24.04 LTS** (aparece el badge *Free tier eligible*).
4. Instance type: **t2.micro** (*Free tier eligible*).
5. *Key pair* → **Create new key pair** (`campusiot`, formato `.pem`;
   se descarga solo — guárdalo, es la única forma de entrar).
6. *Storage*: sube el tamaño a **20 GB** (el free tier permite 30 GB).
7. *Advanced details* → **User data**: pega TODO el contenido de
   [`deploy/ec2-user-data.sh`](deploy/ec2-user-data.sh).
8. **Launch instance**.

**2. Security Group** (en *Launch instance* → *Network settings → Edit*, o
después desde la pestaña *Security*) — reglas de entrada:

| Tipo | Puerto | Origen | Para qué |
|---|---|---|---|
| SSH | 22 | Mi IP | administración |
| HTTP | 80 | 0.0.0.0/0 | el tablero |
| TCP | 1883 | Mi IP *(opcional)* | MQTT externo, solo para demo (el broker no tiene autenticación: no lo dejes abierto al mundo) |

**3. Esperar ~5-10 minutos** — el *user data* instala Docker, clona el
repo y compila (el primer build Maven es el más lento). Verificar por SSH:

```bash
ssh -i campusiot.pem ubuntu@<IP-PUBLICA>
cd /opt/sistemamonitoreoiot
docker compose ps          # todo "running" / "healthy"
docker compose logs backend   # pipeline MQTT activo
```

Si algo falló, el detalle está en `/var/log/cloud-init-output.log`.

**4. ¡Listo!** Abrir **`http://<IP-PUBLICA>`** en el navegador → el tablero.

### Actualizaciones

```bash
ssh -i campusiot.pem ubuntu@<IP-PUBLICA>
cd /opt/sistemamonitoreoiot
git pull && docker compose up -d --build
```

### Por qué nada se pierde en AWS

- `pgdata` y `mosquitto-data` son volúmenes Docker sobre **EBS**: sobreviven
  reinicios, `docker compose down` e incluso *Stop instance*.
- Con `restart: unless-stopped` y el demonio Docker habilitado, un reinicio
  de la instancia levanta todo solo.
- Mientras el backend está parado, Mosquitto conserva los mensajes QoS 1 de
  las sesiones persistentes y la cola de salida de PostgreSQL los entrega al
  volver: exactamente el mecanismo E9/E11 documentado arriba.

## Alternativa: despliegue en Render + Vercel (gratis)

| Pieza | Servicio | Plan |
|---|---|---|
| Frontend | **Vercel** | Hobby ($0) |
| Backend | **Render** (Web Service) | Free ($0, 512 MB; duerme a los ~15 min de inactividad) |
| Base de datos | **Render** Postgres | Free ($0, 256 MB) — alternativas gratis: Supabase, Neon |
| Broker MQTT | **EMQX Cloud Serverless** | Free ($0: 1 M session-min/mes, 1 GB trafico) — alternativa gratis: HiveMQ MQTT Cloud Free / broker propio |

> ¿Por que estas colas sobreviven al sleep de Render? Mientras el backend esta
> dormido, el broker conserva los mensajes QoS 1 de las sesiones persistentes;
> al despertar, el backend vacia su cola de salida (`salida`) y su cola de
> entrada (`entrada:...`) desde PostgreSQL. Nada se pierde por el apagado.

### Pasos

1. **Base de datos (Render):** crear un *Postgres Free* y copiar
   `External Database URL` + usuario + contrasena.
2. **Backend (Render):** *New + Web Service* apuntando al repo, raiz
   `./backend`, build `./mvnw -DskipTests clean package`, start
   `java -jar target/*.jar`. Variables de entorno:
   - `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
     `SPRING_DATASOURCE_PASSWORD` (del paso 1)
   - `MQTT_URL` = endpoint del broker (ej. `tcp://xyz.s1.eu.emqx.io:1883`
     de EMQX Serverless)
   - `PORT` **no se toca**: la app ya lo lee sola (`server.port=${PORT:...}`).
   - Opcionales: `HEARTBEAT_SEGUNDOS`, `UMBRAL_PPM`, `COLA_MAX_PENDIENTES`,
     `COLA_REINTENTOS_MAX`, `CORS_ORIGINS`.
   - Render crea el esquema solo (`spring.jpa.hibernate.ddl-auto=update`).
3. **Broker (EMQX Cloud):** plan Serverless gratis → copiar el endpoint a
   `MQTT_URL` del paso 2 (sin credenciales: `allow_anonymous`).
4. **Frontend (Vercel):** importar el repo en Vercel, raiz `./frontend`
   (framework Vite, build `npm run build`, output `dist`). Luego **pegar la
   URL de Render en `frontend/vercel.json`** en el campo `destination`,
   reemplazando el placeholder `https://TU-BACKEND-EN-RENDER.onrender.com`.
   - Los rewrites hacen que `/api/*` pase por el mismo origen del tablero:
     **no hace falta CORS** ni configurar nada mas.
   - Alternativa: definir `VITE_API_URL=https://TU-BACKEND.onrender.com` en
     las variables de Vercel (ver `frontend/.env.example`) y habilitar los
     origenes en `CORS_ORIGINS` del backend.

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

- Los tests (`./mvnw test`) levantan Postgres y Mosquitto **reales en Docker**
  con Testcontainers: tener Docker encendido. Por eso la imagen se sigue
  construyendo con `-DskipTests`.
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
