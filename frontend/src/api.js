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

async function get(ruta) {
  const res = await fetch(`${BASE}${ruta}`)
  if (!res.ok) {
    throw new Error(`API ${ruta} respondio ${res.status}`)
  }
  return res.json()
}

/** Helpers de mutacion: devuelven el JSON y lanzan con el mensaje del backend */
async function enviar(metodo, ruta, cuerpo) {
  const res = await fetch(`${BASE}${ruta}`, {
    method: metodo,
    headers: cuerpo != null ? { 'Content-Type': 'application/json' } : undefined,
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
  return res.json()
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

/** Registro de errores */
export function fetchErrores(tipo) {
  const q = tipo ? `?tipo=${encodeURIComponent(tipo)}` : ''
  return get(`/api/errores${q}`)
}

// ---------------------------------------------------------------------------
// Control de fallas — la ruleta del panel
//   POST /api/demo/panic     -> apaga al azar un sensor o cualquier servicio
//                               (recepcion, procesamiento, persistencia, MQTT)
//   POST /api/demo/restaurar -> reactiva todo lo caido y drena las colas
//   GET  /api/demo/estado    -> que esta caido, desde cuando y cuanta cola
// ---------------------------------------------------------------------------

export function panic() {
  return post('/api/demo/panic')
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
