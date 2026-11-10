/**
 * Capa de acceso a la API del tablero.
 * En Docker las rutas las resuelve nginx (/api -> backend); en desarrollo
 * local con `npm run dev` las resuelve el proxy de vite.config.js.
 */

async function get(ruta) {
  const res = await fetch(ruta)
  if (!res.ok) {
    throw new Error(`API ${ruta} respondio ${res.status}`)
  }
  return res.json()
}

/** HDU-01: estado de los sensores (activos / desactualizados) */
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

/** HDU-07: registro de errores */
export function fetchErrores(tipo) {
  const q = tipo ? `?tipo=${encodeURIComponent(tipo)}` : ''
  return get(`/api/errores${q}`)
}

// ---------------------------------------------------------------------------
// Ronda 2 — endpoints del panel de control de fallas
// TODO(Ronda 2): descomentar cuando exista DemoController en el backend:
//   POST /api/demo/panic     -> apaga un sensor o componente interno al azar
//   POST /api/demo/restaurar -> reactiva todo lo apagado
//
// export function panic() {
//   return fetch('/api/demo/panic', { method: 'POST' }).then((r) => r.json())
// }
//
// export function restaurar() {
//   return fetch('/api/demo/restaurar', { method: 'POST' }).then((r) => r.json())
// }
// ---------------------------------------------------------------------------
