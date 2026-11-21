import { useEffect, useState } from 'react'
import { fetchDemoEstado, fetchMqttEstado, panic, restaurar } from '../api.js'

/** Nombres legibles de cada victima para la ruleta */
const NOMBRE_SERVICIO = {
  SENSOR: 'Sensor',
  RECEPCION: 'Recepción',
  PROCESAMIENTO: 'Procesamiento',
  PERSISTENCIA: 'Base de datos',
  MQTT: 'Broker MQTT',
}

/**
 * Panel de control de fallas — la "ruleta" (T4).
 *
 *  - "Girar ruleta": el backend elige AL AZAR un sensor encendido o
 *    cualquier servicio del flujo (recepción, procesamiento, persistencia,
 *    broker MQTT) y lo deja fuera de servicio.
 *  - Con el selector se puede FORZAR la victima para una demostración
 *    reproducible (sensor, recepción, procesamiento, BD o broker).
 *  - "Restaurar": reactiva la victima; las colas se drenan sin duplicados.
 *  - El panel de comunicación MQTT muestra broker, clientes, topics y la
 *    cola de salida (outbox) en tiempo real.
 */
function ControlFallas() {
  const [estado, setEstado] = useState(null)
  const [mqtt, setMqtt] = useState(null)
  const [tipo, setTipo] = useState('ALEATORIO')
  const [ultimo, setUltimo] = useState('listo')
  const [error, setError] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  useEffect(() => {
    let vivo = true
    const cargar = () => {
      fetchDemoEstado()
        .then((e) => {
          if (!vivo) return
          setEstado(e)
          setError(null)
        })
        .catch((err) => vivo && setError(err.message))
      fetchMqttEstado()
        .then((m) => vivo && setMqtt(m))
        .catch(() => {})
    }
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
      const r = await panic(tipo === 'ALEATORIO' ? undefined : tipo)
      setUltimo(
        r.tipo === 'SENSOR'
          ? `caído el sensor ${r.victima} (${r.detalle})`
          : `caído ${r.victima} (${r.detalle})`,
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
  const sinDatos = esSensor && estado?.segundosSinDatos != null
    ? ` — sin datos hace ${estado.segundosSinDatos} s`
    : ''

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
            <label className="select-falla">
              Víctima
              <select
                value={tipo}
                onChange={(e) => setTipo(e.target.value)}
                disabled={ocupado || caido}
              >
                <option value="ALEATORIO">Aleatoria (ruleta)</option>
                <option value="SENSOR">Sensor encendido al azar</option>
                <option value="RECEPCION">Recepción</option>
                <option value="PROCESAMIENTO">Procesamiento</option>
                <option value="PERSISTENCIA">Base de datos</option>
                <option value="MQTT">Broker MQTT</option>
              </select>
            </label>
            <button
              type="button"
              className="btn-panico"
              onClick={girar}
              disabled={ocupado || caido}
              title={caido ? 'Ya hay una falla activa: restaura primero' : 'Provoca la falla seleccionada'}
            >
              🔴 Girar ruleta (provocar falla)
            </button>
            <button
              type="button"
              className="btn-restaurar"
              onClick={restaurarTodo}
              disabled={ocupado || !caido}
              title={!caido ? 'No hay nada caído' : 'Reactiva la víctima y drena las colas'}
            >
              🟢 Restaurar
            </button>
          </div>
          <p className="papanico-estado">
            Estado:{' '}
            <strong>
              {caido ? `${nombreCaido} caído${sinDatos} — reintentando` : ultimo}
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
          {esSensor && estado?.ubicacion && (
            <p className="papanico-estado">
              Ubicación: <strong>{estado.ubicacion}</strong>
              {estado.estadoSensor && ` · estado: ${estado.estadoSensor}`}
            </p>
          )}
        </div>

        <h3>Qué demuestra</h3>
        <ol className="pasos">
          <li>
            <strong>Apagar un sensor (T4 + T6)</strong> — la orden viaja por
            MQTT (<code>control/simulador/orden</code>) y el sensor deja de publicar.
            A los <strong>120 s</strong> el heartbeat lo marca <em>Inactivo</em> y
            publica <code>SENSOR_INACTIVO</code> en <code>interno/alertas</code>.
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
      </div>

      {mqtt && (
        <div className="panel">
          <h3>Comunicación MQTT</h3>
          <div className="mqtt-resumen">
            <span className={`badge ${mqtt.broker.clienteEmisorConectado ? 'badge-ok' : 'badge-off'}`}>
              {mqtt.broker.clienteEmisorConectado ? 'Broker conectado' : 'Broker desconectado'}
            </span>
            <span>
              URL: <code>{mqtt.broker.url}</code>
            </span>
            <span>
              QoS: <strong>{mqtt.broker.qos}</strong> · sesiones persistentes:{' '}
              <strong>{mqtt.broker.sesionesPersistentes ? 'sí' : 'no'}</strong>
            </span>
            <span>
              Outbox: <strong>{mqtt.broker.colaSalidaPendiente}</strong> mensaje(s)
            </span>
            {mqtt.broker.caidoPorDemo && (
              <span className="badge badge-off">caído por la ruleta</span>
            )}
          </div>

          <div className="mqtt-grid">
            <div>
              <h4>Clientes y suscripciones</h4>
              <table className="tabla tabla-compacta">
                <thead>
                  <tr>
                    <th>Cliente</th>
                    <th>Topic</th>
                    <th>Estado</th>
                  </tr>
                </thead>
                <tbody>
                  {mqtt.suscripciones.map((s) => (
                    <tr key={`${s.clienteId}-${s.topic}`}>
                      <td><code>{s.clienteId}</code></td>
                      <td><code>{s.topic}</code></td>
                      <td>
                        <span className={`punto ${s.conectado ? 'on' : 'off'}`} />{' '}
                        {s.conectado ? 'conectado' : 'reconectando'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div>
              <h4>Mapa de topics (Pub/Sus)</h4>
              <table className="tabla tabla-compacta">
                <thead>
                  <tr>
                    <th>Topic</th>
                    <th>Publica</th>
                    <th>Escucha</th>
                  </tr>
                </thead>
                <tbody>
                  {mqtt.topics.map((t) => (
                    <tr key={t.topic}>
                      <td><code>{t.topic}</code></td>
                      <td>{t.publica}</td>
                      <td>{t.escucha}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      <div className="panel">
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
