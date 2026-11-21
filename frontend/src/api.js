/**
 * Capa de acceso a la API del tablero.
 *
 * Tres escenarios, sin cambiar el codigo de los componentes:
 * - Docker: nginx resuelve /api -> backend (relojava con la ruta relativa).
 * - `npm run dev` (Vite): el proxy de vite.config.js resuelve /api -> :8081.
 * - Vercel: la ruta relativa /api la reescribe vercel.json hacia Render.
 *
 * Alternativa (si prefieres llamar al backend EN ABSOLUTO, p.ej. sin
 * rewrites): define VITE_API_URL=https://TU-BACKEND.onrender.com en el
 * entorno de Vercel y las peticiones van directas a ese host (en ese caso
 * el backend debe permitir el origen con panel.cors-origins).
 */

const BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '')

// ---------------------------------------------------------------------------
// Sesion (Tarea 7 — HDU-10/11)
//
// El backend protege /api/admin/** con Basic auth y rol ADMIN. Las credenciales
// se guardan en sessionStorage para reenviarlas en cada peticion y para que la
// recarga de pagina no pierda la sesion. AVISO: el servidor corre por HTTP
// plano, asi que la clave viaja en base64 SIN cifrar (pendiente de HTTPS).
// ---------------------------------------------------------------------------

const CLAVE_USUARIO = 'campusiot.usuario'
const CLAVE_NOMBRE = 'campusiot.nombre'
const CLAVE_ROL = 'campusiot.rol'
const CLAVE_CLAVE = 'campusiot.clave'

/** base64 UTF-8 seguro (btoa no acepta acentos ni ñ) */
function codificar(texto) {
  const bytes = new TextEncoder().encode(texto)
  let salida = ''
  for (const byte of bytes) salida += String.fromCharCode(byte)
  return btoa(salida)
}

function guardarSesion(datos, clave) {
  sessionStorage.setItem(CLAVE_USUARIO, datos.username)
  sessionStorage.setItem(CLAVE_NOMBRE, datos.nombre || datos.username)
  sessionStorage.setItem(CLAVE_ROL, datos.rol || '')
  sessionStorage.setItem(CLAVE_CLAVE, clave)
}

/** Sesion activa o null. Devuelve { username, nombre, rol } */
export function sesion() {
  const username = sessionStorage.getItem(CLAVE_USUARIO)
  if (!username) return null
  return {
    username,
    nombre: sessionStorage.getItem(CLAVE_NOMBRE) || username,
    rol: sessionStorage.getItem(CLAVE_ROL) || '',
  }
}

/** Solo el rol ADMIN entra a usuarios, reservas, sensores, fallas y errores */
export function esAdmin() {
  const actual = sesion()
  return actual !== null && actual.rol === 'ADMIN'
}

export function cerrarSesion() {
  sessionStorage.removeItem(CLAVE_USUARIO)
  sessionStorage.removeItem(CLAVE_NOMBRE)
  sessionStorage.removeItem(CLAVE_ROL)
  sessionStorage.removeItem(CLAVE_CLAVE)
}

/** Cabecera Basic si hay sesion (vacia en caso contrario) */
function cabeceraAuth() {
  const usuario = sessionStorage.getItem(CLAVE_USUARIO)
  if (usuario == null) return {}
  const clave = sessionStorage.getItem(CLAVE_CLAVE) ?? ''
  return { Authorization: `Basic ${codificar(`${usuario}:${clave}`)}` }
}

/**
 * Valida las credenciales contra la base de datos y abre la sesion.
 * POST /api/auth/login -> { id, username, nombre, rol, activo }
 */
export async function login(usuario, clave) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Basic ${codificar(`${usuario}:${clave}`)}`,
    },
    body: JSON.stringify({ username: usuario, password: clave }),
  })
  const datos = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new Error(datos.message || `Inicio de sesion rechazado (${res.status})`)
  }
  guardarSesion(datos, clave)
  return datos
}

async function get(ruta) {
  const res = await fetch(`${BASE}${ruta}`, { headers: cabeceraAuth() })
  if (!res.ok) {
    const datos = await res.json().catch(() => ({}))
    throw new Error(datos.message || `API ${ruta} respondio ${res.status}`)
  }
  return res.json()
}

/** Helpers de mutacion: devuelven el JSON y lanzan con el mensaje del backend */
async function enviar(metodo, ruta, cuerpo) {
  const res = await fetch(`${BASE}${ruta}`, {
    method: metodo,
    headers: { 'Content-Type': 'application/json', ...cabeceraAuth() },
    body: cuerpo != null ? JSON.stringify(cuerpo) : undefined,
  })
  if (!res.ok) {
    let detalle = ''
    try {
      const data = await res.json()
      detalle = data.message || data.error || ''
    } catch {
      /* sin cuerpo JSON */
    }
    throw new Error(detalle || `API ${ruta} respondio ${res.status}`)
  }
  return res.status === 204 ? null : res.json()
}

const post = (ruta, cuerpo) => enviar('POST', ruta, cuerpo)
const put = (ruta, cuerpo) => enviar('PUT', ruta, cuerpo)
const del = (ruta) => enviar('DELETE', ruta, undefined)

/** Estado de los sensores (activos / desactualizados) */
export function fetchSensores() {
  return get('/api/sensores')
}

/** Ultimas mediciones, opcionalmente de una variable (graficas) */
export function fetchMediciones(variable, limite = 120) {
  const q = new URLSearchParams()
  if (variable) q.set('variable', variable)
  q.set('limite', String(limite))
  return get(`/api/mediciones?${q}`)
}

/** Resumen operativo del sistema */
export function fetchEstado() {
  return get('/api/estado')
}

/**
 * Heartbeat (T3 — HDU-04): umbral configurado, ultima comprobacion y estado
 * de cada sensor (ACTIVO / INACTIVO / APAGADO).
 */
export function fetchMonitoreo() {
  return get('/api/monitoreo')
}

/** Historial de alertas del heartbeat (SENSOR_INACTIVO / SENSOR_RECUPERADO) */
export function fetchAlertasHeartbeat(limite = 20) {
  const q = new URLSearchParams({ tipo: 'SENSOR_INACTIVO', limite: String(limite) })
  return get(`/api/monitoreo/alertas?${q}`)
}

/** Gestion de la comunicacion MQTT: broker, clientes, topics y colas */
export function fetchMqttEstado() {
  return get('/api/mqtt/estado')
}

/**
 * Registro de errores con filtros combinables (Tarea 9 — HDU-07).
 * `filtros` admite { tipo, sensorId, sensor, fecha, desde, hasta } y lo que
 * venga vacio no se manda: el backend lo resuelve en la base de datos.
 */
export function fetchErrores(filtros = {}) {
  const q = new URLSearchParams()
  Object.entries(filtros).forEach(([clave, valor]) => {
    if (valor != null && String(valor).trim() !== '') q.set(clave, String(valor).trim())
  })
  const consulta = q.toString()
  return get(`/api/errores${consulta ? `?${consulta}` : ''}`)
}

// ---------------------------------------------------------------------------
// Control de fallas — la ruleta del panel (T4)
//   POST /api/demo/panic?tipo=... -> apaga al azar un sensor o un servicio
//                                    (o fuerza la victima si se indica tipo)
//   POST /api/demo/restaurar      -> reactiva la victima y drena las colas
//   GET  /api/demo/estado         -> que esta caido, desde cuando y cuanta cola
// ---------------------------------------------------------------------------

export function panic(tipo) {
  const q = tipo ? `?tipo=${encodeURIComponent(tipo)}` : ''
  return post(`/api/demo/panic${q}`)
}

export function restaurar() {
  return post('/api/demo/restaurar')
}

export function fetchDemoEstado() {
  return get('/api/demo/estado')
}

// ---------------------------------------------------------------------------
// CRUD de sensores (agregar / modificar / eliminar sin tocar codigo)
// ---------------------------------------------------------------------------

export function crearSensor(sensor) {
  return post('/api/sensores', sensor)
}

export function actualizarSensor(id, sensor) {
  return put(`/api/sensores/${encodeURIComponent(id)}`, sensor)
}

export function eliminarSensor(id) {
  return del(`/api/sensores/${encodeURIComponent(id)}`)
}

export function apagarSensor(id) {
  return post(`/api/sensores/${encodeURIComponent(id)}/apagar`)
}

export function encenderSensor(id) {
  return post(`/api/sensores/${encodeURIComponent(id)}/encender`)
}

// ---------------------------------------------------------------------------
// Usuarios y roles (Tarea 7 — HDU-10/11). Todo bajo /api/admin/**: exige
// Basic auth con rol ADMIN, si no el backend responde 403.
// ---------------------------------------------------------------------------

export function fetchUsuarios() {
  return get('/api/admin/usuarios')
}

export function fetchRoles() {
  return get('/api/admin/roles')
}

/** { username, nombre, email, password, rol } — el password es obligatorio */
export function crearUsuario(usuario) {
  return post('/api/admin/usuarios', usuario)
}

export function cambiarRolUsuario(id, rol) {
  return put(`/api/admin/usuarios/${id}/rol`, { rol })
}

export function cambiarEstadoUsuario(id, activo) {
  return put(`/api/admin/usuarios/${id}/estado`, { activo })
}

export function eliminarUsuario(id) {
  return del(`/api/admin/usuarios/${id}`)
}

// ---------------------------------------------------------------------------
// Reservas de biblioteca (Tarea 8 — HDU-09): 50 cupos sin superposiciones
// ---------------------------------------------------------------------------

export function fetchReservas(filtros = {}) {
  const q = new URLSearchParams()
  Object.entries(filtros).forEach(([clave, valor]) => {
    if (valor != null && String(valor).trim() !== '') q.set(clave, String(valor).trim())
  })
  const consulta = q.toString()
  return get(`/api/reservas${consulta ? `?${consulta}` : ''}`)
}

/** Cupos ocupados y disponibles en una franja (por defecto: ahora + 2 h) */
export function fetchDisponibilidad(inicio, fin) {
  const q = new URLSearchParams()
  if (inicio) q.set('inicio', inicio)
  if (fin) q.set('fin', fin)
  const consulta = q.toString()
  return get(`/api/reservas/disponibilidad${consulta ? `?${consulta}` : ''}`)
}

/** { usuarioId, puesto?, sede, inicio, fin } -> 201 con la reserva creada */
export function crearReserva(reserva) {
  return post('/api/reservas', reserva)
}

export function cancelarReserva(id) {
  return del(`/api/reservas/${id}`)
}
