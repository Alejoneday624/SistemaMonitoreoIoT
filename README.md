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
| `interno/alertas` | Monitoreo (heartbeat) | Persistencia (registro de alertas) |
| `interno/logs` | Recepción, Procesamiento, Persistencia | Persistencia (registro de errores) |
| `control/simulador/#` | API REST (toggle T6 y ruleta T4) | Simulador (órdenes apagar/encender) |

QoS 1 en todas las publicaciones + sesiones `cleanSession=false`: si un
suscriptor se cae, el broker **encola los mensajes** y los entrega al
reconectar (tácticas E9/E11).

## Heartbeat de sensores (T3 · HDU-04)

No hay un *ping* aparte: **la propia medición es el latido**. Cuando
Persistencia guarda una medición actualiza `ultimaMedicionEn` y marca el
sensor `ACTIVO` (también si la medición era un duplicado rechazado por la
restricción única). El `MonitoreoService` revisa el catálogo cada 10 s
(`HEARTBEAT_COMPROBACION_MS`) y:

- Si un sensor **encendido** lleva más de **120 s** (`HEARTBEAT_SEGUNDOS`)
  sin una medición nueva, lo marca `INACTIVO` y publica una sola alerta
  `SENSOR_INACTIVO` en `interno/alertas` con sensor, ubicación, fecha/hora y
  segundos sin datos.
- Si vuelve a transmitir, lo marca `ACTIVO` y publica `SENSOR_RECUPERADO`.
- Si el administrador lo apagó (T6), lo marca `APAGADO` y **no alerta**.

Las alertas se archivan en la tabla de errores (Persistencia se suscribe a
`interno/alertas`) y el tablero las muestra en el dashboard y en la tabla de
sensores. La comparación es **solo hacia adelante** (una reentrega QoS 1
antigua no retrocede el latido) y al arrancar hay una gracia de 30 s para no
alertar por datos históricos mientras el simulador retoma el flujo.

```bash
curl http://localhost:8081/api/monitoreo            # umbral, tick y estado por sensor
curl http://localhost:8081/api/monitoreo/alertas    # historial SENSOR_INACTIVO
```

Para verlo rápido en la demo se puede bajar el umbral sin rebuild:

```bash
echo 'HEARTBEAT_SEGUNDOS=20' >> .env
sudo docker compose up -d backend   # recrea solo el backend
```

## Gestión de la comunicación MQTT

El panel **Control de fallas → Comunicación MQTT** y el endpoint
`GET /api/mqtt/estado` muestran en vivo:

- broker configurado, estado del cliente emisor y si la ruleta lo tiene caído;
- clientes MQTT (publicador y suscriptores) con su estado de conexión;
- suscripciones activas y la cola durable (inbox) de cada una;
- mensajes esperando PUBACK en la cola de salida (outbox);
- mapa de topics: quién publica, quién escucha y para qué.

```bash
curl http://localhost:8081/api/mqtt/estado
```

## Demostración guiada (T3 · T4 · T6 · MQTT)

Para no esperar los 120 s reales del heartbeat durante la demo, se puede
bajar el umbral sin rebuild:

```bash
echo 'HEARTBEAT_SEGUNDOS=20' >> .env
docker compose up -d backend        # recrea solo el backend
```

**1. Heartbeat de un sensor (T3 · HDU-04 · E1).** La ruleta elige un sensor
encendido y le envía por MQTT la orden `fallar`: el simulador deja de
publicarlo **pero el sensor sigue encendido**. A los `HEARTBEAT_SEGUNDOS`
(120 s por defecto) el monitor lo marca `INACTIVO`, publica
`SENSOR_INACTIVO` en `interno/alertas` y Persistencia lo archiva.

```bash
curl -X POST "http://localhost:8081/api/demo/panic?tipo=SENSOR"
curl http://localhost:8081/api/monitoreo            # estado y segundos sin datos
curl http://localhost:8081/api/monitoreo/alertas    # alerta archivada
curl -X POST http://localhost:8081/api/demo/restaurar
# al volver a publicar: ACTIVO + SENSOR_RECUPERADO en interno/alertas
```

En el tablero se ve el banner **“Sensor caído: … — sin datos hace X s”**, la
tabla de sensores pasa a **Inactivo (heartbeat)** y el dashboard muestra el
aviso con la ubicación.

**2. Falla de un servicio (T4).** Fuerza la víctima para una demo
reproducible (`RECEPCION`, `PROCESAMIENTO`, `PERSISTENCIA` o `MQTT`); los
mensajes quedan `PENDIENTES` en la cola y se drenan al restaurar.

```bash
curl -X POST "http://localhost:8081/api/demo/panic?tipo=PROCESAMIENTO"
curl http://localhost:8081/api/demo/estado
curl -X POST http://localhost:8081/api/demo/restaurar
```

**3. Apagado manual de un sensor (T6 · HDU-05).** Es distinto de la falla:
la orden `apagar` viaja por `control/simulador/orden`, el simulador marca
`encendido=false` y el heartbeat lo deja en `APAGADO` **sin generar
alertas**. El estado sobrevive a reinicios porque vive en PostgreSQL.

```bash
curl -X POST http://localhost:8081/api/sensores/TMP-SUR-01/apagar
curl http://localhost:8081/api/sensores
curl -X POST http://localhost:8081/api/sensores/TMP-SUR-01/encender
```

**4. Comunicación MQTT.** `GET /api/mqtt/estado` (y el panel del tablero)
muestra broker, clientes, suscripciones, topics y el tamaño de la cola de
salida. Si la ruleta tumba el broker, esa cola crece y se vacía al restaurar.

```bash
curl http://localhost:8081/api/mqtt/estado
```


## Demo de fallas — la ruleta

En el tablero, sección **Control de fallas**, el botón *Girar ruleta* elige
**al azar** la víctima entre un sensor del catálogo y **cualquier servicio**
del flujo (recepción, procesamiento, persistencia/base de datos, broker MQTT):

- **Sensor** → la orden `fallar` viaja por MQTT (`control/simulador/#`, Pub/Sus puro)
  y el simulador deja de publicarlo: sus tarjetas muestran "sin datos", a los
 90 s aparece "Desactualizado" y el banner global muestra
  **"Sensor caído: … — reintentando"**. El resto del sistema sigue moviéndose.
- **Recepción / Procesamiento / Base de datos** → el manejador lanza
  `ComponenteCaidoException`: los mensajes **no se procesan pero tampoco se
  descartan ni agotan reintentos**, quedan `PENDIENTES` en su cola de entrada
  y el banner muestra **"Caido: … — cola: N mensajes — reintentando"**.
- **Broker MQTT** → `MqttBus` deja de confirmar publicaciones: todo se
  acumula en la cola de **salida** (outbox) y nada fluye de punta a punta.

En cualquiera de los casos, la **gráfica se corta** en el periodo caído
(eje temporal proporcional + banda sombreada con el nombre de la víctima) y
las tarjetas marcan "sin datos hace X s". *Restaurar* reactiva todo y drena
las colas al instante (FIFO, sin duplicados). Solo hay una falla activa a la
vez.

```bash
curl -X POST http://localhost:8081/api/demo/panic      # gira la ruleta
curl http://localhost:8081/api/demo/estado             # qué cayó + cola
curl -X POST http://localhost:8081/api/demo/restaurar  # todo a la normalidad
```

## Modificabilidad — CRUD de sensores

Agregar, modificar o eliminar un sensor **no requiere tocar código ni
reiniciar**: el simulador lee el catálogo de la base en cada ciclo (5 s).

| Endpoint | Acción |
|---|---|
| `POST /api/sensores` | Crear (`{"id","variable","sede","salon","valorBase"}`) |
| `PUT /api/sensores/{id}` | Modificar variable/sede/salón/valor base (el id es la clave, no cambia) |
| `DELETE /api/sensores/{id}` | Eliminar (deja de publicarse; las mediciones históricas se conservan) |
| `POST /api/sensores/{id}/apagar` · `/encender` | Toggle individual (HDU-05, columna Acción) |

En la UI (sección **Sensores**): botón *+ Nuevo sensor*, *Editar* y *Eliminar*
por fila. Los 12 sensores de la Tabla 1 se siembran **solo si la tabla está
vacía**, así un sensor eliminado no resucita al reiniciar.

```bash
curl -X POST http://localhost:8081/api/sensores -H "Content-Type: application/json" \
  -d '{"id":"TMP-SUR-06","variable":"temperatura","sede":"Sur","salon":"Salon 104","valorBase":23.0}'
curl -X DELETE http://localhost:8081/api/sensores/TMP-SUR-06
```


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
│       ├── sensor/SensorSimulator.java     # catalogo desde BD + ordenes de falla
│       ├── recepcion/RecepcionService.java # valida formato (HDU-02/03)
│       ├── procesamiento/                  # STUB + caida simulada (demo)
│       ├── persistencia/                   # entidades + unico acceso a BD
│       └── api/                            # Panel, Demo (ruleta) y CRUD sensores
└── frontend/
    ├── nginx.conf              # SPA + proxy inverso al backend
    ├── vercel.json             # rewrites /api -> Render (PLACEHOLDER)
    └── src/
        ├── api.js              # capa de acceso a la API
        ├── App.jsx             # layout con sidebar + banner de fallas
        └── components/         # Dashboard, Sensores, ControlFallas, BannerFallas
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

Un solo comando — baja los últimos cambios del repo y reconstruye **solo lo
que cambió** (los datos nunca se tocan):

```bash
ssh -i campusiot.pem ubuntu@<IP-PUBLICA>
curl -fsSL https://raw.githubusercontent.com/Alejoneday624/SistemaMonitoreoIoT/main/deploy/actualizar.sh | sudo bash
```

Equivalente manual:

```bash
cd /opt/sistemamonitoreoiot
sudo git pull --ff-only && sudo docker compose up -d --build
```

### Frecuencia del simulador

- Por defecto el simulador genera datos **cada 5 segundos**
  (`SENSOR_FRECUENCIA_MS=5000`): el tablero siempre se ve vivo. La retención
  de 30 días acota el disco (~40-60 MB por día encendido, techo ~1-2 GB) y
  **apagar la instancia (Stop) corta la generación por completo**: cuando
  se vuelve a encender, todo sigue donde estaba (los datos viven en
  volúmenes EBS).
- Para frenarlo en cualquier momento (sin rebuild), en la instancia:

```bash
cd /opt/sistemamonitoreoiot
echo 'SENSOR_FRECUENCIA_MS=3600000' >> .env
sudo docker compose up -d    # recrea solo el backend, ~10 s
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
| 4 | ~~Endpoint `POST /api/demo/panic` y `POST /api/demo/restaurar`~~ ✅ hecho | E1, E11 | `backend/.../api/DemoController.java` + `frontend/src/api.js` | Los botones del panel "Control de fallas" están activos |
| 5 | ~~Botones del panel con banner "qué falló / qué sigue operativo / cuenta regresiva"~~ ✅ hecho | E1 | `frontend/src/components/ControlFallas.jsx` + `BannerFallas.jsx` | Click → banner global → dashboard sigue moviéndose |
| 6 | ~~Toggle apagar/encender por sensor en la tabla~~ ✅ hecho (más CRUD) | HDU-05 | `frontend/src/components/Sensores.jsx` + `SensoresController.java` | Apagar TMP-SUR-01 y ver su gráfica congelada |
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
