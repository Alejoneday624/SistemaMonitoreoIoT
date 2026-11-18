import { useEffect, useState } from 'react'
import { fetchDemoEstado, panic, restaurar } from '../api.js'

/** Nombres legibles de cada servicio para la ruleta */
const NOMBRE_SERVICIO = {
  RECEPCION: 'Recepción',
  PROCESAMIENTO: 'Procesamiento',
  PERSISTENCIA: 'Base de datos',
  MQTT: 'Broker MQTT',
}

/**
 * Panel de control de fallas — la "ruleta".
 *
 *  - "Girar ruleta": el backend elige AL AZAR un sensor del catalogo o
 *    cualquier servicio del flujo (recepcion, procesamiento, persistencia,
 *    broker MQTT) y lo deja fuera de servicio. El tablero sigue vivo con
 *    el resto y la cola acumula los mensajes pendientes.
 *  - "Restaurar": reactiva todo lo caido; las colas se drenan sin
 *    duplicados (recuperacion automatica).
 *  - El estado y la cola se actualizan solos cada 5 s.
 */
function ControlFallas() {
  const [estado, setEstado] = useState(null)
  const [ultimo, setUltimo] = useState('listo')
  const [error, setError] = useState(null)
  const [ocupado, setOcupado] = useState(false)

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
    const id = setInterval(cargar, 5000)
    return () => {
      vivo = false
      clearInterval(id)
    }
  }, [])

  const girar = async () => {
    setOcupado(true)
    setUltimo('girando la ruleta…')
    try {
      const r = await panic()
      setUltimo(
        r.tipo === 'SENSOR'
          ? `caido el sensor ${r.victima} (${r.detalle})`
          : `caido ${r.victima} (${r.detalle})`,
      )
      setEstado((e) => (e ? { ...e, caido: true, tipo: r.tipo, victima: r.victima } : e))
      setError(null)
    } catch (err) {
      setUltimo('sin cambios')
      setError(err.message)
    } finally {
      setOcupado(false)
    }
  }

  const restaurarTodo = async () => {
    setOcupado(true)
    setUltimo('restaurando…')
    try {
      const r = await restaurar()
      setUltimo(`todo operativo · cola pendiente: ${r.colaPendiente}`)
      setEstado((e) => (e ? { ...e, caido: false, tipo: null, victima: null, colaPendiente: r.colaPendiente } : e))
      setError(null)
    } catch (err) {
      setUltimo('sin cambios')
      setError(err.message)
    } finally {
      setOcupado(false)
    }
  }

  const caido = estado?.caido
  const esSensor = estado?.tipo === 'SENSOR'
  const nombreCaido = esSensor
    ? `sensor ${estado.victima}`
    : NOMBRE_SERVICIO[estado?.tipo] || estado?.victima || 'servicio'

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Control de fallas</h1>
          <p>
            La ruleta elige al azar qué apagar — un sensor o cualquier servicio del flujo —
            y comprueba que el sistema lo absorbe sin perder datos.
          </p>
        </div>
      </header>

      {error && <div className="aviso aviso-error">{error}</div>}

      <div className="panel">
        <div className="papanico">
          <div className="papanico-botones">
            <button
              type="button"
              className="btn-panico"
              onClick={girar}
              disabled={ocupado || caido}
              title={caido ? 'Ya hay una falla activa: restaura primero' : 'Elige una victima al azar'}
            >
              🔴 Girar ruleta (apagar aleatorio)
            </button>
            <button
              type="button"
              className="btn-restaurar"
              onClick={restaurarTodo}
              disabled={ocupado || !caido}
              title={!caido ? 'No hay nada caido' : 'Reactiva todo y drena la cola'}
            >
              🟢 Restaurar
            </button>
          </div>
          <p className="papanico-estado">
            Estado:{' '}
            <strong>
              {caido ? `${nombreCaido} caído — reintentando` : ultimo}
            </strong>
            {caido && estado.segundosCaido != null && ` — caído hace ${estado.segundosCaido} s`}
          </p>
          {caido && (
            <p className="papanico-estado">
              Cola: <strong>{estado.colaPendiente}</strong> pendiente(s)
              {estado.colaFallidos > 0 && ` · ${estado.colaFallidos} fallido(s)`} — se drenará al
              restaurar
            </p>
          )}
        </div>

        <h3>Qué demuestra</h3>
        <ol className="pasos">
          <li>
            <strong>Apagar un sensor</strong> — deja de publicar, su marca pasa a
            “sin datos” en las tarjetas y a los 90 s se marca inactivo
            (heartbeat).
          </li>
          <li>
            <strong>Caer un servicio</strong> — recepción, procesamiento, base de
            datos o broker MQTT: los mensajes se acumulan en la cola (QoS 1 +
            sesión persistente) y el tablero muestra “caído — reintentando” con
            el contador creciendo.
          </li>
          <li>
            <strong>Restaurar</strong> — cada cola se vacía en orden FIFO sin
            duplicados y todo vuelve a la normalidad (recuperación automática).
          </li>
        </ol>

        <h3>Detalle de colas</h3>
        {estado?.porCola?.length ? (
          <table className="tabla">
            <thead>
              <tr>
                <th>Cola</th>
                <th>Estado</th>
                <th>Mensajes</th>
              </tr>
            </thead>
            <tbody>
              {estado.porCola.map((c) => (
                <tr key={`${c.cola}-${c.estado}`}>
                  <td>
                    <code>{c.cola}</code>
                  </td>
                  <td>{c.estado}</td>
                  <td>{c.total}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="vacio">Sin mensajes en cola — todo fluye.</p>
        )}
      </div>
    </>
  )
}

export default ControlFallas
