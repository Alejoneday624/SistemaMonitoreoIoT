import { useEffect, useState } from 'react'
import { fetchErrores, fetchSensores } from '../api.js'

/** Tipos que publican recepcion y persistencia (el campo admite cualquier texto) */
const TIPOS = ['MEDICION_INVALIDA', 'SENSOR_INACTIVO', 'ERROR_COMUNICACION']

const FILTROS_VACIOS = { tipo: '', sensorId: '', fecha: '', desde: '', hasta: '' }

function fechaLarga(texto) {
  if (!texto) return '—'
  const d = new Date(texto)
  return Number.isNaN(d.getTime()) ? texto : d.toLocaleString()
}

/**
 * Registro de errores con filtros combinables (Tarea 9 — HDU-07).
 *
 * Los filtros viajan a /api/errores y los resuelve la base de datos en una
 * sola query (WHERE + ORDER BY por fecha descendente), no en memoria.
 */
function Errores() {
  const [filtros, setFiltros] = useState({ ...FILTROS_VACIOS })
  const [resultados, setResultados] = useState([])
  const [sensores, setSensores] = useState([])
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)
  const [consultas, setConsultas] = useState(0)

  // Sin setState sincrono aqui: el indicador de carga solo lo tocan los
  // eventos (aplicar / limpiar) y el estado inicial.
  const consultar = (criterio) => {
    fetchErrores(criterio)
      .then((lista) => {
        setResultados(lista)
        setError(null)
      })
      .catch((e) => setError(e.message))
      .finally(() => setCargando(false))
  }

  // Primera carga con los filtros vacios
  useEffect(() => {
    consultar({ ...FILTROS_VACIOS })
  }, [consultas])

  // Datalist de sensores para completar el filtro
  useEffect(() => {
    fetchSensores()
      .then((lista) => setSensores(lista.map((s) => s.id)))
      .catch(() => setSensores([]))
  }, [])

  const aplicar = (ev) => {
    ev.preventDefault()
    setCargando(true)
    consultar(filtros)
  }

  const limpiar = () => {
    setFiltros({ ...FILTROS_VACIOS })
    setCargando(true)
    setConsultas((n) => n + 1)
  }

  const cambiar = (campo) => (e) => setFiltros({ ...filtros, [campo]: e.target.value })

  const hayFiltros = Object.values(filtros).some((v) => v !== '')

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Errores</h1>
          <p>
            Registro de fallas del sistema (HDU-07). Tipo, sensor y rango de
            fechas se combinan libremente y se filtran en la base de datos,
            ordenado por fecha descendente.
          </p>
        </div>
      </header>

      <form className="panel form-sensor" onSubmit={aplicar}>
        <h3>Filtros</h3>
        <div className="form-campos">
          <label>
            Tipo
            <input
              list="tipos-error"
              value={filtros.tipo}
              onChange={cambiar('tipo')}
              placeholder="MEDICION_INVALIDA"
            />
            <datalist id="tipos-error">
              {TIPOS.map((t) => (
                <option key={t} value={t} />
              ))}
            </datalist>
          </label>
          <label>
            Sensor
            <input
              list="ids-sensor"
              value={filtros.sensorId}
              onChange={cambiar('sensorId')}
              placeholder="TMP-SUR-01"
            />
            <datalist id="ids-sensor">
              {sensores.map((id) => (
                <option key={id} value={id} />
              ))}
            </datalist>
          </label>
          <label>
            Fecha exacta
            <input type="date" value={filtros.fecha} onChange={cambiar('fecha')} />
          </label>
          <label>
            Desde
            <input type="date" value={filtros.desde} onChange={cambiar('desde')} />
          </label>
          <label>
            Hasta
            <input type="date" value={filtros.hasta} onChange={cambiar('hasta')} />
          </label>
        </div>
        <div className="form-acciones">
          <button type="submit" className="btn-guardar" disabled={cargando}>
            {cargando ? 'Buscando…' : 'Aplicar'}
          </button>
          <button
            type="button"
            className="btn-fantasma btn-activo"
            onClick={limpiar}
            disabled={!hayFiltros}
          >
            Limpiar
          </button>
        </div>
      </form>

      {error && <div className="aviso aviso-error">{error}</div>}

      <div className="panel">
        <table className="tabla">
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Tipo</th>
              <th>Sensor</th>
              <th>Descripción</th>
            </tr>
          </thead>
          <tbody>
            {resultados.map((e) => (
              <tr key={e.id}>
                <td>{fechaLarga(e.fecha)}</td>
                <td>
                  <code>{e.tipo}</code>
                </td>
                <td>{e.sensorId || '—'}</td>
                <td>{e.descripcion}</td>
              </tr>
            ))}
            {resultados.length === 0 && !error && (
              <tr>
                <td colSpan="4" className="vacio">
                  {cargando
                    ? 'Buscando registros…'
                    : 'Ningún registro coincide con los filtros.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

export default Errores
