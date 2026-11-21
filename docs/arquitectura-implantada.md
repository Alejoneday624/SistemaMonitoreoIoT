# Arquitectura implantada del Sistema de Monitoreo de Sensores IoT — Campus Universitario

**Materia:** Arquitectura de Software
**Grupo:** Jonathan Guzman · Katherine Buitrago · Juan José Mosquera · Jovany Gutierrez Vergara
**Docente:** Juan Pablo Huertas Méndez
**Universidad Antonio Nariño** — Programa Ingeniería de Software, Facultad de Ingeniería de Sistemas — Ciudad, Colombia, 2026

> **Propósito de este documento.** Ajustar la arquitectura descrita en el documento principal a lo que realmente se construyó y desplegó en el prototipo, respondiendo a la retroalimentación del docente: *(i)* concretar el despliegue en la nube, *(ii)* actualizar el "monitoreo de sensores" por el tablero (dashboard) con el que efectivamente se aplicó y *(iii)* mostrar la integración con metodologías ágiles y los cambios iterativos permitidos.

---

## Tabla de contenidos

1. Arquitectura real en funcionamiento
   - 1.1 Topología desplegada (AWS EC2 + Docker Compose)
   - 1.2 Flujo de datos (Publicar/Suscribir sobre MQTT)
   - 1.3 Aplicación de los estilos: Capas y MVC
2. APIs a las que se conecta el sistema
3. Servicios añadidos respecto a la propuesta inicial
4. Atributos de calidad: cómo se sustentan en el prototipo
   - 4.1 Disponibilidad
   - 4.2 Modificabilidad
   - 4.3 Rendimiento
   - 4.4 Seguridad
   - 4.5 Visualización: cómo funcionan las gráficas del tablero
5. Frecuencias, heartbeat y tiempos del sistema
   - 5.1 Cómo funciona el heartbeat (línea de tiempo)
6. Colas y MQTT
   - 6.1 Topics
   - 6.2 Colas duraderas en PostgreSQL
   - 6.3 ¿Son FIFO? Quién publica y quién recibe
7. Qué pasa cuando falla un componente o un sensor
8. Integración con metodología ágil y cambios iterativos
9. Tabla "previsto vs. implementado" (ajustes al documento)

---

# 1. Arquitectura real en funcionamiento

## 1.1 Topología desplegada (ajuste: "cloud" → AWS EC2)

El prototipo se desplegó en una instancia **AWS EC2 t3.micro (8 GB de disco)** orquestada con **Docker Compose**, y no en la plataforma PaaS que se había mencionado inicialmente. El stack está formado por cuatro contenedores:

| Contenedor | Tecnología | Puerto | Rol arquitectónico |
|---|---|---|---|
| `frontend` | React + Vite + **nginx** | 80 (HTTP) | Tablero web; nginx hace de proxy inverso `/api/` → backend |
| `backend` | Spring Boot (Java 21) | 8081 (interno) | API REST + pipeline MQTT (recepción → procesamiento → persistencia) |
| `mosquitto` | eclipse-mosquitto:2 | 1883 | Broker Pub/Sus, QoS 1, sesiones persistentes |
| `db` | postgres:16-alpine | solo red interna | PostgreSQL: mediciones, sensores, colas, usuarios, errores, reservas |

- **Actualización:** `deploy/actualizar.sh` (git pull → build → restart) permite desplegar cada iteración sin intervención manual compleja.
- **Frontera externa:** el navegador se conecta por **HTTP/JSON** a la API REST; los sensores hablan **MQTT** con el broker; **solo el backend toca PostgreSQL**.
- El archivo `vercel.json` documenta una alternativa Vercel (frontend) + Render (backend), pero **el despliegue verificado y en uso es EC2 + Docker Compose**.

## 1.2 Flujo de datos (estilo Publicar/Suscribir, validado)

```
Simulador de sensores (12 sensores, cada 5 s)
   │  publica  QoS 1
   ▼
campus/{sede}/{variable}/{sensorId}          ←── broker Mosquitto
   │  suscripción campus/#
   ▼
RECEPCIÓN ── valida formato/campos ──▶ interno/validadas
   │ (JSON inválido → interno/logs)              │
   │                                             ▼
   │                              PROCESAMIENTO (passthrough)
   ▼                                             │
   └──────────▶ interno/procesadas ◀─────────────┘
                     ▼
               PERSISTENCIA ──▶ PostgreSQL
                     │
interno/logs ────────┴──▶ tabla de registro de errores
```

Estilo arquitectónico elegido y confirmado en el prototipo: **Publicar/Suscribir (MQTT)** como eje, complementado con **Capas** dentro del backend y **MVC** en el tablero (se detallan en 1.3).

## 1.3 Aplicación de los estilos: Capas y MVC

| Estilo | Dónde vive en el prototipo | Atributo que sirve |
|---|---|---|
| **Publicar/Suscribir** | Sensores ↔ servicios, vía MQTT (`campus/#`, `interno/*`) | Disponibilidad, interoperabilidad |
| **Capas** | Dentro del backend: paquetes con dependencias unidireccionales | Modificabilidad |
| **MVC** | Servidor (Spring MVC) y tablero (`api.js` / componentes / manejadores) | Modificabilidad |

### Capas (backend)

| Capa | Paquetes / clases | Qué hace | Qué NO hace |
|---|---|---|---|
| **1. Transporte HTTP** | `api/` (`PanelApiController`, `SensoresController`, `DemoController`, `PortadaController`, `ErrorHandlerAdvice`), `security/AdminUsuarioController`, `reservas/ReservaController` | Recibe JSON, valida la entrada y devuelve JSON | No toca la base de datos ni las reglas de negocio |
| **2. Lógica de negocio** | `recepcion/RecepcionService`, `procesamiento/ProcesamientoService`, `persistencia/PersistenciaService`, `cola/ColaService`, `sensor/SensorSimulator`, `reservas/ReservaService`, `security/UsuarioService` | Validar, calcular, reservar cupos, autenticar, encolar | No habla HTTP |
| **3. Infraestructura / puertos** | `config/MqttBus`, `config/WebConfig` (CORS), `security/RbacFilter` | Broker MQTT, CORS, autorización | — |
| **4. Datos** | Entidades `@Entity` + `JpaRepository` (`Medicion`, `Sensor`, `ErrorRegistro`, `Reserva`, `Usuario`, `ColaMensajes`) | Único punto de acceso a PostgreSQL | — |

Tres propiedades que conviene destacar:

1. **Dependencias unidireccionales:** HTTP → servicios → repos/bus; el frontend jamás toca la base de datos (solo llega hasta la capa 1).
2. **La cadena del pipeline está desacoplada:** entre los eslabones no hay llamadas directas sino mensajes MQTT (`interno/validadas` → `interno/procesadas`): *capas dentro de cada módulo + Publicar/Suscribir entre módulos*. Por eso una capa caída no tumba a las demás (§7).
3. **Cortes transversales:** seguridad (`RbacFilter`), errores (`ErrorHandlerAdvice`) y configuración (`WebConfig`) atraviesan las capas sin pertenecer a ninguna.

### MVC (servidor y tablero)

| Rol MVC | Backend (Spring MVC) | Frontend (SPA) |
|---|---|---|
| **Model** | `@Entity` + `@Service` + `JpaRepository` | `src/api.js`: toda la capa de datos (~35 funciones) y la sesión en `localStorage` |
| **Controller** | 6 `@RestController` + `@RestControllerAdvice` (`ErrorHandlerAdvice`) | Manejadores de los componentes (`useEffect` cada 5 s, botones, filtros) y el enrutador `App.jsx` |
| **View** | Respuesta JSON para el SPA y páginas HTML (`PortadaController`, errores) | `components/*.jsx` + CSS (`Dashboard`, `Sensores`, `Reservas`, `Errores`, `Usuarios`, `Login`, `ControlFallas`) |
| **Front controller** | `DispatcherServlet` de Spring + filtro `RbacFilter` | — |

> Nota de honestidad: en React la vista y el controlador viven juntos en el componente (no hay plantillas separadas), por eso se habla de **MVC inspirado**, no MVC puro — coherente con la Tabla 34 del documento principal ("útil para organizar el tablero web", no como estilo principal).

*Ejemplo que lo demuestra:* cambiar el CRUD de sensores no toca ni al pipeline MQTT ni al tablero, y reemplazar la vista de reservas no obliga a tocar `api.js` ni el backend.

---

# 2. APIs a las que se conecta el sistema

| Método + Ruta | Qué hace | Acceso |
|---|---|---|
| `GET /api/estado` | Resumen operativo: sensores totales/activos, mediciones totales y última hora | público |
| `GET /api/sensores` | Estado y vigencia de cada sensor (`apagado`, `desactualizado`, `antiguedadSegundos`) | público |
| `GET /api/mediciones?variable=&limite=` | Últimas mediciones para las gráficas | público |
| `GET /api/errores?tipo=&sensorId=&fecha=&desde=&hasta=` | Registro de errores con filtros combinables (HDU-07) | público |
| `GET /api/reservas` · `GET /api/reservas/disponibilidad` | Consulta de reservas (filtros `usuarioId`, `usuario`, `soloActivas`) y cupos por franja | público |
| `POST /api/reservas` · `DELETE /api/reservas/{id}` | Crear y cancelar reservas (HDU-09) | público |
| `POST /api/sensores` · `PUT /api/sensores/{id}` · `DELETE /api/sensores/{id}` | CRUD de sensores (HDU-05) | **ADMIN** |
| `POST /api/sensores/{id}/apagar` · `POST /api/sensores/{id}/encender` | Apagar/encender un sensor individual | **ADMIN** |
| `GET /api/admin/usuarios` · `POST` · `PUT /{id}/rol` · `PUT /{id}/estado` · `DELETE /{id}` · `GET /api/admin/roles` | Usuarios, roles y permisos (HDU-10/11) | **ADMIN** |
| `POST /api/auth/login` | Validación de credenciales contra la base de datos | público |
| `POST /api/demo/panic` · `POST /api/demo/restaurar` · `GET /api/demo/estado` | Ruleta de fallas y restauración (herramienta de validación de escenarios) | **ADMIN** |
| `GET /actuator/health` | Salud del contenedor | — |

**Interacción real del tablero:** el frontend React consume las tres consultas de lectura (`/api/estado`, `/api/sensores`, `/api/mediciones`) cada 5 segundos y las de gestión (sensores, usuarios, reservas, errores, demo) bajo demanda, siempre con cabecera `Authorization: Basic …` en las rutas administrativas.

---

# 3. Servicios añadidos respecto a la propuesta inicial

| Servicio añadido | Para qué responde | Historia de usuario |
|---|---|---|
| **Autenticación y autorización (RBAC)** | `RbacFilter` exige autenticación Basic y rol `ADMIN` sobre `/api/admin/**`; roles `ADMIN / MANTENIMIENTO / COMUNIDAD` | HDU-10 / HDU-11 |
| **Gestión de usuarios** | Alta, baja, cambio de rol, activar/desactivar; protecciones de negocio | HDU-10 |
| **Reservas de la biblioteca** | 50 cupos simultáneos, sin superposiciones, estados `ACTIVA/CANCELADA` | HDU-09 |
| **Registro de errores con filtros** | Tipo + sensor + rango de fechas resueltos en la base de datos (una sola consulta) | HDU-07 |
| **Tablero web (dashboard) con secciones protegidas** | Dashboard público; Sensores, Control de fallas, Reservas, Errores y Usuarios con candado de rol | HDU-01 |
| **Control de fallas (ruleta de demostración)** | Permite reproducir en vivo los escenarios E1/E11 ante el docente | validación de escenarios |
| **Manejador de respuestas claras (`ErrorHandlerAdvice`)** | Los errores de negocio devuelven su motivo en lugar de un genérico "Bad Request" | — |

---

# 4. Atributos de calidad: cómo se sustentan en el prototipo

## 4.1 Disponibilidad (prioridad crítica)

- **Colas duraderas en PostgreSQL** + **QoS 1** + **sesiones persistentes**: un servicio caído no detiene el flujo; los mensajes solo se acumulan en su cola.
- **El tablero es público y sigue vivo** aunque falle un servicio: la consulta de lectura no depende de la cola caída.
- **Restauración automática** por *tick* cada 10 s, sin intervención manual.
- **Disco acotado:** retención de 30 días con purga horaria, sobre una instancia de 8 GB.
- **Verificado en producción:** 6 giros seguidos de la ruleta siempre cayeron un servicio (nunca un sensor), con restauración limpia y cola que volvió a **0**.

## 4.2 Modificabilidad (prioridad crítica)

- **CRUD de sensores sin tocar código:** el simulador **lee el catálogo de la base de datos en cada ciclo (5 s)**; crear, modificar o eliminar un sensor se refleja automáticamente.
- **Frecuencia configurable por variable de entorno** (`SENSOR_FRECUENCIA_MS`, por defecto 5000 ms) sin recompilar.
- **Topics MQTT por sede y variable:** una sede nueva es un topic nuevo, sin modificar los servicios centrales.
- **Configuración por datos:** umbrales, retención, reintentos y tope de cola viven en `application.properties` y en las variables de entorno del `.env`.
- **Roles y usuarios son datos**, no código: cambiar permisos no exige recompilar.

## 4.3 Rendimiento (prioridad alta)

- **12 sensores × 1 medición / 5 s ≈ 8.400 mediciones/hora** (medido en producción: 8.388/h y ~45.500 acumuladas).
- El tablero consulta **120–160 mediciones por gráfica** y se refresca cada **5 s** sin recargar la página.
- `spring.jpa.show-sql=false`: se detectó y corrigió un **deadlock real** por inundación de *stdout* en la t3.micro (1 GB de RAM); los tiempos de respuesta se estabilizaron tras el cambio.
- **Latencia por medición registrada** (`latenciaMs = hora de recepción − fecha del sensor`), lo que permite demostrar el tiempo real del pipeline.
- La purga horaria de mediciones y de colas evita el crecimiento descontrolado de tablas.

## 4.4 Seguridad (prioridad alta)

- **HTTP Basic validado contra la base de datos** sobre `/api/admin/**`; las denegaciones devuelven 403 con el motivo y el rol real del usuario.
- **Contraseñas SHA-256 con salt** (formato `salt$hash`), con re-hash en el login; **password obligatorio** (ya no se asigna clave por defecto).
- **Protecciones de negocio:** no autoeliminación, no borrado del último ADMIN activo, usuarios inactivos → 401.
- **Dashboard público; secciones administrativas exigen rol ADMIN.**
- **CORS restringido** a los orígenes configurados (`panel.cors-origins`).
- ⚠️ **Pendiente crítico:** el transporte es **HTTP plano**; para producción definitiva se requiere **HTTPS** (el Basic viaja sin cifrar). Debe quedar registrado como riesgo abierto.

## 4.5 Visualización: cómo funcionan las gráficas del tablero

Las gráficas (`Dashboard.jsx`, librería **Recharts**) reciben los datos así:

```
cada 5 s → GET /api/mediciones?variable=temperatura&limite=160
           GET /api/mediciones?variable=calidad-aire&limite=80
           GET /api/estado · GET /api/demo/estado
        → seriesPorSede()  →  <LineChart> con dos líneas (Sur y Federman)
```

**`seriesPorSede()` — la transformación clave:**

| Paso | Qué hace |
|---|---|
| A. *Bucketing* | Redondea cada `fecha` a un slot de **5 s** (`PASO_MS`), para que el eje X sea regular |
| B. Promedio por sede | Agrupa los valores del mismo slot **por sede** y los promedia (1 decimal): las 8 sondas de Sur salen como **una sola serie** Sur |
| C. Relleno con `null` | Recorre todos los slots entre el primero y el último; un slot vacío queda `null` → **Recharts corta la línea en vez de puentearla** |
| D. Eje X numérico | Usa el timestamp (`domain=[xMin,xMax]`), así que **el ancho del hueco es proporcional al tiempo real transcurrido** |

**Elementos visuales:**

| Elemento | Qué muestra |
|---|---|
| Dos `<Line>` sin puntos | Sur `#aa3bff`, Federman `#12b886` |
| `<ReferenceLine y={900}>` | Línea punteada "umbral 900" (solo calidad del aire) |
| `<ReferenceArea>` banda roja | Periodo con componente caído: de `falla.desde` hasta **ahora**; el eje se extiende hasta `ahora` y **el espacio vacío crece en vivo** mientras dura la caída |
| Estado vacío | *"Esperando mediciones del broker MQTT…"* |

**Tarjetas resumen y sus dos umbrales:**

- Último valor por sede (la lista llega ordenada de más reciente a más antiguo).
- **15 s** (`VIGENCIA_S`): pasado ese tiempo la tarjeta muestra *"sin datos hace X s"* — umbral **distinto** del de 90 s de la tabla de sensores.
- **900 ppm**: si el valor lo supera, la tarjeta se resalta con *"⚠ umbral superado"* → **solo visual, no genera alerta** (§9).

**Por qué aparecen huecos:**

| Hueco | Causa real |
|---|---|
| Cortes aislados de 5 s | El ciclo real del simulador mide **5.16–5.25 s** > `PASO_MS = 5000`; la deriva acumulada deja algún slot vacío |
| Bloques de 15–55 s | **Repliegues (deploys):** el backend reinicia y no llegan mediciones |
| Banda roja larga | Falla activa deliberada (ruleta del panel) |

**Por qué apagar 1 sensor no rompe la gráfica:** el promedio por sede hace que los demás sensores de esa sede sigan llenando los slots. La excepción es `calidad-aire` (**1 sensor por sede**): si ese se apaga, su serie queda `null` y **sí se corta**.

**Qué aporta al documento:** HDU-01 (actualización sin recargar + marca de datos desactualizados), E3 (4 peticiones ligeras cada 5 s, respuesta sub-segundo) y E1/E11 (línea cortada + banda roja **hacen visible la falla sin inventar datos**).

---

# 5. Frecuencias, heartbeat y tiempos del sistema

| Qué | Valor | Dónde está definido |
|---|---|---|
| **Frecuencia de envío de los sensores** | **5 s** (configurable con `SENSOR_FRECUENCIA_MS`, sin rebuild) | `SensorSimulator`, `@Scheduled(fixedDelay)` |
| **Refresco automático del tablero** | **5 s** | `Dashboard.jsx` (`INTERVALO_MS`) |
| **Marca de dato "desactualizado"** | **> 90 s** sin medición | `PanelApiController.VIGENCIA = 90 s` |
| **Marca de sensor "inactivo" (HDU-04, 2 min)** | `heartbeat.segundos=120` está **declarado en la configuración pero aún no lo consume código** (diseño futuro) | `application.properties` |
| **Tick de reintentos de colas** | **10 s** | `MqttBus` (`cola.tick-ms`) |
| **Reintentos máximos por mensaje** | **5** → pasa a `FALLIDO` | `cola.reintentos-max` |
| **Tope de mensajes pendientes por cola** | **5.000** | `cola.max-pendientes` |
| **Purga de mensajes FALLIDO** | **7 días** | `cola.fallidos-dias` |
| **Retención de mediciones** | **30 días**, purga cada hora | `RetencionMediciones` |

**Cómo funciona hoy el heartbeat:** cada medición guardada actualiza `ultimaMedicionEn` del sensor (base de la HDU-04) y lo deja en estado `ACTIVO`; al leer `/api/sensores` se calcula en ese instante si la última medición tiene más de 90 s y se marca `desactualizado: true`. La transición automática a estado `INACTIVO` a los 2 minutos queda pendiente de implementación (ver §9).

## 5.1 Cómo funciona el heartbeat (línea de tiempo)

El sistema **no tiene un "ping" aparte: cada medición es el latido** (*heartbeat implícito*, táctica E1).

**Quién lo escribe** — `PersistenciaService.refrescarSensor()`, con cada medición guardada:

```java
private void refrescarSensor(Medicion m) {
    sensores.findById(m.getSensorId()).ifPresent(s -> {
        Instant ultima = s.getUltimaMedicionEn();
        if (ultima == null || m.getFecha().isAfter(ultima)) {   // solo avanza
            s.setUltimaMedicionEn(m.getFecha());
            s.setEstado("ACTIVO");
            sensores.save(s);
        }
    });
}
```

- **Solo avanza hacia adelante:** una reentrega antigua (QoS 1) no puede atrasar la fecha.
- **También se refresca en el duplicado:** si la restricción única `(sensor_id, fecha)` rechaza el guardado, igual se actualiza el latido para no dejar al sensor "apagado" indebidamente en el tablero.
- La fecha usada es **la del sensor** (reloj del emisor), no la de recepción.

**Quién lo lee** — `PanelApiController`, **en cada consulta** (no hay un temporizador que recorra sensores):

| Endpoint | Cálculo | Uso |
|---|---|---|
| `GET /api/sensores` | `desactualizado = (ahora − ultimaMedicionEn) > 90 s` + `antiguedadSegundos` | Tabla de estado de sensores |
| `GET /api/estado` | cuenta como **activo** el latido ≤ 90 s | Chip `X/Y sensores activos` del dashboard |

**Línea de tiempo:**

| t | Evento | Lo que se ve |
|---|---|---|
| 0 s | Sensor publica → se guarda → `ultimaMedicionEn = 0` | **Activo** |
| c/5 s | Nueva medición refresca el latido | **Activo** |
| +90 s | Dejan de llegar mediciones | **Desactualizado** (fila gris + antigüedad; los demás sensores siguen normal) |
| +120 s | HDU-04: marcar **INACTIVO** + alerta | ⚠️ no implementado: `heartbeat.segundos=120` no lo consume ningún código ni se publica `SENSOR_INACTIVO` (§9) |

**Nota adicional:** si **persistencia cae**, los mensajes esperan en su cola y el latido no avanza → el sensor aparece desactualizado (correcto: el pipeline está roto aunque el sensor siga publicando).

⚠️ Quedan **tres umbrales distintos de "frescura"**: **15 s** en las tarjetas del dashboard, **90 s** en la tabla de sensores (`VIGENCIA`) y **120 s** propuestos por la HDU-04 (pendiente).

---

# 6. Colas y MQTT

## 6.1 Topics

| Topic | Quién publica | Quién escucha |
|---|---|---|
| `campus/{sede}/{variable}/{sensorId}` | Simulador | Recepción (suscripción `campus/#`) |
| `interno/validadas` | Recepción | Procesamiento |
| `interno/procesadas` | Procesamiento | Persistencia |
| `interno/logs` | Recepción y Persistencia | Persistencia (registro de errores) |
| `control/simulador/#` | API REST (botones) | Simulador (órdenes de apagar/restaurar) |

**QoS 1 en todas las publicaciones + `cleanSession=false`:** si un suscriptor se cae, el broker conserva los mensajes y los entrega al reconectar (tácticas E9 y E11).

## 6.2 Colas duraderas en PostgreSQL (tabla `cola_mensajes`)

Se implementan dos tipos de cola, **ambas persistidas en PostgreSQL** (no en memoria), porque el disco de la instancia es efímero: así sobreviven a un reinicio del contenedor.

- **Outbox (`salida`):** `publicar()` **guarda primero en la cola y solo la retira al recibir el PUBACK**. Si el broker MQTT está caído, *nada se pierde*: el outbox crece y se drena en orden FIFO cuando vuelve.
- **Inbox por suscriptor** (`campus-recepcion`, `campus-procesamiento`, `campus-persistencia`): cada suscriptor tiene su propia cola de entrada. Un mensaje cuyo procesamiento falló queda `PENDIENTE` y se reintenta en el siguiente *tick* (cada 10 s), en orden FIFO.
- **Estados:** `PENDIENTE` → procesado (eliminado) o `FALLIDO` tras 5 reintentos; `FALLIDO` se purga a los 7 días.

## 6.3 ¿Son FIFO? Quién publica y quién recibe

**Sí: las dos colas son FIFO (el más viejo primero).**

| Garantía | Evidencia en el código |
|---|---|
| Orden de extracción | `ColaService.siguiente()` → `findFirstByColaAndEstadoOrderByIdAsc(...)`: siempre se toma el mensaje **más antiguo** |
| Sin carreras al consumir | Cola de salida: **un solo hilo** (`mqtt-salida`); cola de entrada: **un hilo secuencial por suscriptor** (`mqtt-msg-<clienteId>`) → nunca se procesan dos mensajes de la misma cola a la vez |
| Sin carreras al publicar | Todos pasan por el **mismo outbox** (`salida`): el orden de salida al broker es el orden de llegada a la cola |
| Caída de un componente | El mensaje **no consume reintentos** ni pasa a `FALLIDO`: queda `PENDIENTE` y, al restaurar, la cola se vacía *"en orden FIFO sin pérdidas (E11)"* (comentario del propio código) |

**Mapa de publica/recibe (quién hace qué):**

| Topic | Publica | Recibe (suscriptor → inbox propio) |
|---|---|---|
| `campus/{sede}/{variable}/{sensorId}` | `SensorSimulator` (cada 5 s) | `campus-recepcion` (escucha `campus/#`) → `procesarMensaje` |
| `interno/validadas` | `RecepcionService` (mensaje válido) | `campus-procesamiento` → passthrough |
| `interno/procesadas` | `ProcesamientoService` | `campus-persistencia` → `guardarMedicion` (PostgreSQL) |
| `interno/logs` | `RecepcionService` (JSON inválido) y `PersistenciaService` (errores de comunicación) | `campus-persistencia-log` → `guardarError` → registro de errores |
| `control/simulador/orden` | `DemoController` (API REST, botones) | `campus-simulador` → `interpretarOrden` |

**Cuatro excepciones al FIFO (para no exagerar en el documento):**

1. **Mensaje tóxico:** tras **5 fallos** pasa a `FALLIDO` y **se salta** → lo que viene detrás sigue (decisión deliberada: que un mensaje malo no congele la cola).
2. **Cola llena (5.000):** se descartan los **más viejos** y se conservan los recientes.
3. **Base de datos caída al encolar:** *fallback* a publicación directa "mejor esfuerzo" (`publicarDirecto`), fuera de orden.
4. **QoS 1 = al menos una vez:** puede haber **duplicados** (los resuelve la restricción única `(sensor_id, fecha)`), pero no se pierde el orden.

---

# 7. Qué pasa exactamente cuando falla un componente o un sensor

| Falla | Mecanismo de detección | Qué ocurre | Recuperación |
|---|---|---|---|
| **Recepción caída** | flag + `ComponenteCaidoException` | El mensaje **queda `PENDIENTE` en su cola de entrada**: no se procesa y no se pierde | Al levantar, el siguiente *tick* (≤10 s) lo procesa en FIFO |
| **Procesamiento caído** | ídem | Ídem | Ídem |
| **Persistencia / base de datos caída** | ídem | Ídem | Ídem |
| **Broker MQTT caído** | `publicarDirecto` no recibe PUBACK | **El outbox crece**: todas las publicaciones quedan guardadas en la cola `salida` | Al restaurar, el hilo emisor drena la cola en orden FIFO |
| **Sensor apagado o caído** | Deja de publicar | Sin datos nuevos → a los **90 s** aparece `desactualizado`; **los demás sensores siguen** y la gráfica de su sede continúa con los restantes | Al encenderlo, reanuda el envío; el tablero muestra la última medición con su antigüedad |
| **Reinicio del backend** | — | Las colas viven en PostgreSQL → al arrancar se reprocesa todo lo `PENDIENTE` | Recuperación automática al iniciar |
| **Medición duplicada (QoS 1)** | restricción única `(sensor_id, fecha)` | Se ignora sin frenar el flujo | — |
| **Mensaje irrepetible** | 5 reintentos fallidos | Pasa a `FALLIDO` y se registra | Se purga a los 7 días |

**Lo que ve el usuario:** un banner global tipo *"Caído: Procesamiento — cola: N mensajes — reintentando"*, una **banda roja** sobre el periodo caído en la gráfica y la **línea cortada** donde faltan datos (el sistema nunca inventa valores para ocultar la falla).

---

# 8. Integración con metodología ágil y cambios iterativos

| Práctica ágil | Evidencia concreta en el proyecto |
|---|---|
| **Desarrollo iterativo e incremental** | 17 commits en `main`; cada tarea es un incremento entregable: stack base → simulador → colas y outbox → ruleta de fallas → tareas 7/8/9 (usuarios, reservas, filtros) → ajustes de estabilidad |
| **Convención de commits** | Prefijos `feat:` / `fix:` / `chore:` con mensaje descriptivo; el historial funciona como bitácora verificable del proyecto |
| **Definición de "listo"** | Antes de cada commit: `oxlint` + `vite build` + `mvnw test`; después: despliegue con `deploy/actualizar.sh` y **verificación en producción** (curl + SQL) |
| **Retroalimentación como nueva iteración** | Los hallazgos del docente se incorporaron como cambios iterativos: RBAC, contraseñas con salt, filtros de errores resueltos en BD, mensajes de error claros y ajuste de la ruleta de fallas |
| **Deuda técnica gestionada de forma incremental** | El filtro de errores se corrigió en tres pasos (`lower(bytea)` → parámetros nulos → normalización en Java): revisión continua, no reescritura |
| **Pruebas automatizadas** | 3 pruebas unitarias ejecutables sin Docker (`PersonaDStandaloneTest`); 15 pruebas con Testcontainers en el backlog por falta de entorno local |
| **Backlog evolutivo visible** | HTTPS, alertas por umbral, marca `INACTIVO` automática, operación *modificar* reservas |
| **Trabajo en ramas (feature branches)** | Rama `feature/error-404-amigable` (portada/404/access log) fusionada con *merge commit* a `main`; rama `docs/arquitectura-implantada` para este documento — ramas cortas que se integran rápido |

**Redacción sugerida para el documento principal:**

> El sistema se construyó con ciclos cortos e incrementales (metodología ágil): cada historia de usuario se implementó, se verificó con pruebas automatizadas y se desplegó de forma independiente. La retroalimentación del docente se incorporó como nuevas iteraciones, lo que permitió ajustes localizados —nuevos servicios, controles de seguridad y correcciones de estabilidad— sin rediseñar la arquitectura, demostrando así la modificabilidad que se propuso en los escenarios de calidad.

---

# 9. Tabla "previsto vs. implementado" (ajustes al documento)

| Afirmación actual del documento | Realidad en el prototipo | Acción |
|---|---|---|
| Despliegue "cloud" (mencionaba Render) | **AWS EC2 t3.micro + Docker Compose** | Especificar EC2 |
| "Monitoreo de sensores" como aplicación | Se implementó con un **tablero web React** (sidebar, gráficas, control de fallas, secciones protegidas) | Actualizar: el monitoreo se materializa en el dashboard |
| El **procesamiento** valida rangos y duplicados | La **recepción** valida formato y campos; el procesamiento hoy es un *passthrough* hacia persistencia | Corregir Tablas 14, 31 y 32 |
| **Monitoreo** marca `INACTIVO` a los 2 minutos | Se calcula `desactualizado` a los **90 s** en la lectura; el estado `INACTIVO` aún no se automarca | Corregir o implementar (pendiente) |
| **Servicio de alertas** + umbral 900 ppm | El umbral está **dibujado en la gráfica**, pero no se generan ni se emiten alertas | Corregir o implementar (pendiente) |
| **Redundancia pasiva** de base de datos (2.ª BD) | **Una sola** PostgreSQL con volumen persistente | Corregir (dejarlo como diseño futuro) |
| **Adaptador de interoperabilidad** (E7/E12) | No existe: hay 4 variables fijas con validación de conjunto | Corregir |
| Reservas: crear, consultar, **modificar**, cancelar | Crear, consultar, disponibilidad y cancelar (**sin modificar**) | Corregir |
| Notificación al equipo de mantenimiento (HDU-04) | Solo registro de errores y estado en el tablero (sin canal de notificación) | Corregir |

---

## Pendientes declarados como "diseño futuro"

1. Marca automática de sensor `INACTIVO` a los 120 s (`heartbeat.segundos`) + registro del error `SENSOR_INACTIVO`.
2. Evaluación del umbral de 900 ppm con emisión de alerta (HDU-06 / HDU-12).
3. Transporte **HTTPS** (hoy HTTP con Basic).
4. Operación **modificar** reservas (PUT).
5. Segunda base de datos con conmutación por falla (redundancia pasiva).
6. Adaptador de interoperabilidad para nuevos protocolos/sedes.
7. 15 pruebas con Testcontainers, pendientes de un entorno Docker en desarrollo.

---

*Fuente: elaboración propia 2026 — verificado contra el código del repositorio y la base de datos de producción del prototipo desplegado.*
