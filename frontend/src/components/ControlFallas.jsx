import { useEffect, useState } from 'react'
import { fetchDemoEstado, panic, restaurar } from '../api.js'

/**
 * Panel de control de fallas — la "ruleta" de la demo (E1, E11).
 *
 *  - "Apagar aleatorio": el backend elige AL AZAR entre un sensor del
 *    simulador o el componente interno de procesamiento, y lo silencia.
 *    El tablero sigue funcionando con el resto.
 *  - "Restaurar": reactiva todo lo apagado; la cola pendiente se drena
 *    sin duplicados (HDU-08, recuperacion automatica).
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
          : `caido el componente ${r.victima} (${r.detalle})`,
      )
      setEstado((e) => (e ? { ...e, caido: true, tipo: r.tipo, victima: r.tipo === 'SENSOR' ? r.victima : 'Procesamiento' } : e))
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

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Control de fallas</h1>
          <p>
            Botón de pánico para la demo: la ruleta elige al azar qué apagar y
            comprueba que el sistema sigue funcionando (E1, E11).
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
              {caido
                ? esSensor
                  ? `sensor ${estado.victima} caído — reintentando`
                  : `componente ${estado.victima} caído — reintentando`
                : ultimo}
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
            <strong>Apagar un sensor</strong> — su gráfica se congela con marca
            “sin datos”, las demás siguen moviéndose; a los 90 s salta la marca
            de inactivo (HDU-04, heartbeat).
          </li>
          <li>
            <strong>Caer el componente de procesamiento</strong> — las
            mediciones se acumulan en la cola (QoS 1 + sesión persistente) y el
            tablero muestra “componente caído — reintentando” con el contador de
            cola creciendo.
          </li>
          <li>
            <strong>Restaurar</strong> — la cola se vacía sin duplicados y todo
            vuelve a la normalidad (HDU-08, recuperación automática).
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
