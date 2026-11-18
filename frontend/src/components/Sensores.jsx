import { useEffect, useState } from 'react'
import {
  apagarSensor,
  crearSensor,
  eliminarSensor,
  encenderSensor,
  fetchSensores,
  actualizarSensor,
} from '../api.js'

const INTERVALO_MS = 5000

const ETIQUETA_VARIABLE = {
  temperatura: 'Temperatura',
  'calidad-aire': 'Calidad del aire',
  'consumo-electrico': 'Consumo eléctrico',
  'consumo-agua': 'Consumo de agua',
}

const VARIABLES = Object.keys(ETIQUETA_VARIABLE)
const SEDES = ['Sur', 'Federman']

const SENSOR_VACIO = { id: '', variable: 'temperatura', sede: 'Sur', salon: '', valorBase: '' }

function antiguedad(segundos) {
  if (segundos == null) return 'nunca'
  if (segundos < 60) return `hace ${segundos} s`
  if (segundos < 3600) return `hace ${Math.floor(segundos / 60)} min`
  return `hace ${Math.floor(segundos / 3600)} h`
}

/**
 * Tabla de sensores con:
 *  - Estado en vivo (activo / apagado / desactualizado)
 *  - Toggle apagar/encender por sensor
 *  - CRUD completo: crear, editar y eliminar un sensor SIN tocar codigo —
 *    el simulador lo lee de la BD en el proximo ciclo.
 */
function Sensores() {
  const [sensores, setSensores] = useState([])
  const [error, setError] = useState(null)
  const [mensaje, setMensaje] = useState(null)

  // Formulario crear/editar (null = oculto)
  const [form, setForm] = useState(null)
  const [editandoId, setEditandoId] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const cargar = () =>
    fetchSensores()
      .then((s) => {
        setSensores(s)
        setError(null)
      })
      .catch((e) => setError(e.message))

  useEffect(() => {
    cargar()
    const id = setInterval(cargar, INTERVALO_MS)
    return () => clearInterval(id)
  }, [])

  const toggle = async (s) => {
    try {
      if (s.apagado) {
        await encenderSensor(s.id)
        setMensaje(`Sensor ${s.id} reactivado`)
      } else {
        await apagarSensor(s.id)
        setMensaje(`Sensor ${s.id} apagado — dejará de publicar`)
      }
      cargar()
    } catch (e) {
      setError(e.message)
    }
  }

  const eliminar = async (s) => {
    if (!window.confirm(`¿Eliminar el sensor ${s.id}? Sus mediciones históricas se conservan.`)) {
      return
    }
    try {
      await eliminarSensor(s.id)
      setMensaje(`Sensor ${s.id} eliminado`)
      cargar()
    } catch (e) {
      setError(e.message)
    }
  }

  const editar = (s) => {
    setEditandoId(s.id)
    setForm({
      id: s.id,
      variable: s.variable,
      sede: s.sede,
      salon: s.salon || '',
      valorBase: s.valorBase ?? '',
    })
    setMensaje(null)
    setError(null)
  }

  const nuevo = () => {
    setEditandoId(null)
    setForm({ ...SENSOR_VACIO })
    setMensaje(null)
    setError(null)
  }

  const guardar = async (ev) => {
    ev.preventDefault()
    setGuardando(true)
    setError(null)
    try {
      const datos = {
        variable: form.variable,
        sede: form.sede,
        salon: form.salon || null,
        valorBase: form.valorBase === '' ? null : Number(form.valorBase),
      }
      if (editandoId) {
        await actualizarSensor(editandoId, { id: editandoId, ...datos })
        setMensaje(`Sensor ${editandoId} modificado`)
      } else {
        await crearSensor({ id: form.id.trim(), ...datos })
        setMensaje(`Sensor ${form.id.trim()} creado — el simulador lo medirá en el próximo ciclo`)
      }
      setForm(null)
      setEditandoId(null)
      cargar()
    } catch (e) {
      setError(e.message)
    } finally {
      setGuardando(false)
    }
  }

  const cancelar = () => {
    setForm(null)
    setEditandoId(null)
  }

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Sensores</h1>
          <p>
            Los datos sin recibir en más de 90 s se marcan desactualizados. Aquí se
            agregan, modifican y eliminan sensores en caliente, sin tocar código ni
            reiniciar el sistema.
          </p>
        </div>
        <button type="button" className="btn-nuevo" onClick={nuevo} disabled={form !== null}>
          + Nuevo sensor
        </button>
      </header>

      {mensaje && <div className="aviso aviso-ok">{mensaje}</div>}
      {error && <div className="aviso aviso-error">{error}</div>}

      {form && (
        <form className="panel form-sensor" onSubmit={guardar}>
          <h3>{editandoId ? `Editar sensor ${editandoId}` : 'Nuevo sensor'}</h3>
          <div className="form-campos">
            <label>
              Id
              <input
                value={form.id}
                onChange={(e) => setForm({ ...form, id: e.target.value })}
                disabled={editandoId !== null}
                placeholder="TMP-SUR-06"
                required
                maxLength={40}
              />
            </label>
            <label>
              Variable
              <select
                value={form.variable}
                onChange={(e) => setForm({ ...form, variable: e.target.value })}
              >
                {VARIABLES.map((v) => (
                  <option key={v} value={v}>
                    {ETIQUETA_VARIABLE[v]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Sede
              <select value={form.sede} onChange={(e) => setForm({ ...form, sede: e.target.value })}>
                {SEDES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Salón
              <input
                value={form.salon}
                onChange={(e) => setForm({ ...form, salon: e.target.value })}
                placeholder="Salon 104"
                maxLength={80}
              />
            </label>
            <label>
              Valor base
              <input
                type="number"
                step="any"
                value={form.valorBase}
                onChange={(e) => setForm({ ...form, valorBase: e.target.value })}
                placeholder="23.0"
              />
            </label>
          </div>
          <div className="form-acciones">
            <button type="submit" className="btn-guardar" disabled={guardando}>
              {guardando ? 'Guardando…' : editandoId ? 'Guardar cambios' : 'Crear sensor'}
            </button>
            <button type="button" className="btn-fantasma btn-activo" onClick={cancelar}>
              Cancelar
            </button>
          </div>
        </form>
      )}

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
              <th>Acción</th>
            </tr>
          </thead>
          <tbody>
            {sensores.map((s) => (
              <tr
                key={s.id}
                className={s.desactualizado && !s.apagado ? 'fila-desactualizada' : ''}
              >
                <td>
                  <code>{s.id}</code>
                </td>
                <td>{ETIQUETA_VARIABLE[s.variable] || s.variable}</td>
                <td>{s.sede}</td>
                <td>{s.salon || '—'}</td>
                <td>
                  <span
                    className={`punto ${s.apagado || s.desactualizado ? 'off' : 'on'}`}
                    title={
                      s.apagado
                        ? 'Apagado por orden del panel'
                        : s.desactualizado
                          ? 'Sin datos recientes'
                          : 'Transmitiendo'
                    }
                  />{' '}
                  {s.apagado ? 'Apagado' : s.desactualizado ? 'Desactualizado' : 'Activo'}
                </td>
                <td>{antiguedad(s.antiguedadSegundos)}</td>
                <td className="celda-acciones">
                  <button
                    type="button"
                    className="btn-fantasma btn-activo"
                    onClick={() => toggle(s)}
                    title={s.apagado ? 'Reactivar sensor' : 'Apagar sensor'}
                  >
                    {s.apagado ? 'Encender' : 'Apagar'}
                  </button>
                  <button
                    type="button"
                    className="btn-fantasma btn-activo"
                    onClick={() => editar(s)}
                    title="Modificar sensor"
                  >
                    Editar
                  </button>
                  <button
                    type="button"
                    className="btn-fantasma btn-peligro btn-activo"
                    onClick={() => eliminar(s)}
                    title="Eliminar sensor"
                  >
                    Eliminar
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
