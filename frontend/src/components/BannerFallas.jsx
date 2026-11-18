import { useEffect, useState } from 'react'
import { fetchDemoEstado, restaurar } from '../api.js'

const INTERVALO_MS = 5000

/** Nombres legibles de cada servicio para el banner */
const NOMBRE_SERVICIO = {
  RECEPCION: 'Recepción',
  PROCESAMIENTO: 'Procesamiento',
  PERSISTENCIA: 'Base de datos',
  MQTT: 'Broker MQTT',
}

function antiguedad(segundos) {
  if (segundos == null) return ''
  if (segundos < 60) return `hace ${segundos} s`
  if (segundos < 3600) return `hace ${Math.floor(segundos / 60)} min`
  return `hace ${Math.floor(segundos / 3600)} h`
}

/**
 * Banner global de fallas: se monta en App.jsx y se ve en TODAS las
 * secciones. Mientras la ruleta tenga algo caido muestra:
 *   - SENSOR   -> "Sensor caido: TMP-SUR-01 — sin datos hace X — reintentando"
 *   - SERVICIO -> "Caido: Procesamiento — cola: N mensajes — reintentando"
 * con el contador de cola creciendo y el boton para restaurar.
 */
function BannerFallas() {
  const [estado, setEstado] = useState(null)
  const [error, setError] = useState(null)
  const [restaurando, setRestaurando] = useState(false)

  useEffect(() => {
    let vivo = true
    const cargar = () =>
      fetchDemoEstado()
        .then((e) => {
          if (!vivo) return
          setEstado(e)
          setError(null)
        })
        .catch((err) => vivo && setError(err.message))
    cargar()
    const id = setInterval(cargar, INTERVALO_MS)
    return () => {
      vivo = false
      clearInterval(id)
    }
  }, [])

  const restaurarTodo = async () => {
    setRestaurando(true)
    try {
      await restaurar()
      setEstado((e) => (e ? { ...e, caido: false, tipo: null, victima: null } : e))
    } catch (err) {
      setError(err.message)
    } finally {
      setRestaurando(false)
    }
  }

  if (error) {
    return <div className="aviso aviso-error">Sin conexion con el backend: {error}</div>
  }
  if (!estado || !estado.caido) return null

  const esSensor = estado.tipo === 'SENSOR'
  const titulo = esSensor
    ? `Sensor caido: ${estado.victima}`
    : `Caido: ${NOMBRE_SERVICIO[estado.tipo] || estado.victima || 'servicio'}`
  const detalle = esSensor
    ? `sin datos ${antiguedad(estado.segundosCaido)}`
    : `cola: ${estado.colaPendiente} mensaje(s)`

  return (
    <div className={`banner-fallas${esSensor ? '' : ' banner-componente'}`} role="alert">
      <span className="banner-icono" aria-hidden="true">
        🔴
      </span>
      <div className="banner-texto">
        <strong>{titulo}</strong>
        <span>
          {detalle} · reintentando… (cada 10 s)
          {estado.colaPendiente > 0 && ` · cola total: ${estado.colaPendiente}`}
        </span>
      </div>
      <button type="button" className="btn-restaurar" onClick={restaurarTodo} disabled={restaurando}>
        {restaurando ? 'Restaurando…' : '🟢 Restaurar'}
      </button>
    </div>
  )
}

export default BannerFallas
