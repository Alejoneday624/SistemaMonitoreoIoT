import { useState } from 'react'

/**
 * Panel de control de fallas — estructura completa de la Ronda 1,
 * logica pendiente para la Ronda 2.
 *
 * Comportamiento previsto (se aprueba en el plan):
 *  - "Apagar aleatorio": el backend elige al azar entre un sensor del
 *    simulador o un componente interno, y lo silencia. El tablero sigue
 *    funcionando con el resto.
 *  - "Restaurar": reactiva todo lo apagado; la cola del broker se vacia.
 *  - El backend publica la orden en control/simulador/# (Pub/Sus puro).
 */
function ControlFallas() {
  const [estado, setEstado] = useState('listo')

  // TODO(Ronda 2): descomentar cuando exista DemoController en el backend
  // const apagar = async () => {
  //   setEstado('apagando…')
  //   const r = await panic()
  //   setEstado(`apagado: ${r.victima}`)
  // }
  // const restaurar = async () => {
  //   setEstado('restaurando…')
  //   await restaurar()
  //   setEstado('todo operativo')
  // }

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Control de fallas</h1>
          <p>
            Botón de pánico para la demo: apaga un servicio al azar y comprueba
            que el sistema sigue funcionando (E1, E11).
          </p>
        </div>
      </header>

      <div className="panel">
        <div className="papanico">
          <div className="papanico-botones">
            {/* TODO(Ronda 2): onClick={apagar} con endpoint POST /api/demo/panic */}
            <button type="button" className="btn-panico" disabled title="Ronda 2 — pendiente">
              🔴 Apagar aleatorio
            </button>
            {/* TODO(Ronda 2): onClick={restaurar} con POST /api/demo/restaurar */}
            <button type="button" className="btn-restaurar" disabled title="Ronda 2 — pendiente">
              🟢 Restaurar
            </button>
          </div>
          <p className="papanico-estado">
            Estado: <strong>{estado}</strong> — los botones se activan en la Ronda 2.
          </p>
        </div>

        <h3>Qué demostrará</h3>
        <ol className="pasos">
          <li>
            <strong>Apagar un sensor</strong> — su gráfica se congela con marca
            “sin datos”, las demás siguen moviéndose; a los 2 min salta la alerta
            de inactivo (HDU-04, heartbeat).
          </li>
          <li>
            <strong>Pausar un componente interno</strong> — las mediciones se
            encolan en el broker (QoS 1 + sesión persistente) y el tablero
            muestra el último dato con aviso de retraso.
          </li>
          <li>
            <strong>Restaurar</strong> — la cola se vacía sin duplicados y todo
            vuelve a la normalidad (HDU-08, recuperación automática).
          </li>
        </ol>

        <h3>Lista de espera (Ronda 2)</h3>
        <ul className="pasos">
          <li>
            <code>POST /api/demo/panic</code> — elige la víctima al azar y
            publica la orden en <code>control/simulador/#</code>.
          </li>
          <li>
            <code>POST /api/demo/restaurar</code> — reactiva sensor y
            componentes, drena la cola pendiente.
          </li>
          <li>
            Banner en el dashboard con “qué falló, qué sigue operativo y cuánto
            lleva caído”.
          </li>
        </ul>
      </div>
    </>
  )
}

export default ControlFallas
