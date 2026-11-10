import { useEffect, useState } from 'react'
import { fetchSensores } from '../api.js'

const INTERVALO_MS = 5000

const ETIQUETA_VARIABLE = {
  temperatura: 'Temperatura',
  'calidad-aire': 'Calidad del aire',
  'consumo-electrico': 'Consumo eléctrico',
  'consumo-agua': 'Consumo de agua',
}

function antiguedad(segundos) {
  if (segundos == null) return 'nunca'
  if (segundos < 60) return `hace ${segundos} s`
  if (segundos < 3600) return `hace ${Math.floor(segundos / 60)} min`
  return `hace ${Math.floor(segundos / 3600)} h`
}

function Sensores() {
  const [sensores, setSensores] = useState([])
  const [error, setError] = useState(null)

  useEffect(() => {
    let vivo = true
    const cargar = () =>
      fetchSensores()
        .then((s) => {
          if (!vivo) return
          setSensores(s)
          setError(null)
        })
        .catch((e) => vivo && setError(e.message))
    cargar()
    const id = setInterval(cargar, INTERVALO_MS)
    return () => {
      vivo = false
      clearInterval(id)
    }
  }, [])

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Sensores</h1>
          <p>
            Los datos sin recibir en más de 90 s se marcan desactualizados (HDU-01).
            Aquí vive el botón de apagar individual — Ronda 2 (HDU-05).
          </p>
        </div>
      </header>

      {error && <div className="aviso aviso-error">Sin conexion con el backend: {error}</div>}

      <div className="panel">
        <table className="tabla">
          <thead>
            <tr>
              <th>Sensor</th>
              <th>Variable</th>
              <th>Sede</th>
              <th>Salón</th>
              <th>Estado</th>
              <th>Última medición</th>
              <th>
                {/* TODO(Ronda 2 · HDU-05): columna con toggle apagar/encender */}
                Acción
              </th>
            </tr>
          </thead>
          <tbody>
            {sensores.map((s) => (
              <tr key={s.id} className={s.desactualizado ? 'fila-desactualizada' : ''}>
                <td>
                  <code>{s.id}</code>
                </td>
                <td>{ETIQUETA_VARIABLE[s.variable] || s.variable}</td>
                <td>{s.sede}</td>
                <td>{s.salon}</td>
                <td>
                  <span
                    className={`punto ${s.desactualizado ? 'off' : 'on'}`}
                    title={s.desactualizado ? 'Sin datos recientes' : 'Transmitiendo'}
                  />{' '}
                  {s.desactualizado ? 'Desactualizado' : 'Activo'}
                </td>
                <td>{antiguedad(s.antiguedadSegundos)}</td>
                <td>
                  {/* TODO(Ronda 2): boton apagar/encender por sensor */}
                  <button type="button" className="btn-fantasma" disabled title="Ronda 2">
                    Apagar
                  </button>
                </td>
              </tr>
            ))}
            {sensores.length === 0 && !error && (
              <tr>
                <td colSpan="7" className="vacio">
                  Cargando sensores…
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

export default Sensores
