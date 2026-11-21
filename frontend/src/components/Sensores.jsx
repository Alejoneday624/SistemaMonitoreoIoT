import { useEffect, useState } from 'react'
import {
  apagarSensor,
  crearSensor,
  eliminarSensor,
  encenderSensor,
  fetchMonitoreo,
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
 * Estado visible de un sensor combinando el heartbeat (T3) con el
 * interruptor de encendido (T6):
 *   APAGADO     -> el administrador lo apago por MQTT
 *   INACTIVO    -> encendido pero sin latido durante el umbral (120 s)
 *   Desactualizado -> sin datos >90 s pero aun dentro del umbral heartbeat
 *   ACTIVO      -> transmitiendo
 */
function estadoSensor(s) {
  if (s.apagado || s.estado === 'APAGADO') {
    return { texto: 'Apagado', clase: 'off', title: 'Apagado por el administrador (T6)' }
  }
  if (s.estado === 'INACTIVO' || s.inactivoHeartbeat) {
    return {
      texto: 'Inactivo (heartbeat)',
      clase: 'off',
      title: 'Sin latido durante el umbral del heartbeat (T3)',
    }
  }
  if (s.desactualizado) {
    return { texto: 'Desactualizado', clase: 'off', title: 'Sin datos recientes (>90 s)' }
  }
  return { texto: 'Activo', clase: 'on', title: 'Transmitiendo con normalidad' }
}

/**
 * Tabla de sensores con:
 *  - Estado en vivo (activo / inactivo por heartbeat / apagado)
 *  - Toggle apagar/encender por MQTT (T6)
 *  - CRUD completo: crear, editar y eliminar un sensor SIN tocar codigo —
 *    el simulador lo lee de la BD en el proximo ciclo.
 */
function Sensores() {
  const [sensores, setSensores] = useState([])
  const [monitoreo, setMonitoreo] = useState(null)
  const [error, setError] = useState(null)
  const [mensaje, setMensaje] = useState(null)

  // Formulario crear/editar (null = oculto)
  const [form, setForm] = useState(null)
  const [editandoId, setEditandoId] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const cargar = () =>
    Promise.all([fetchSensores(), fetchMonitoreo().catch(() => null)])
      .then(([s, m]) => {
        setSensores(s)
        setMonitoreo(m)
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
        const r = await encenderSensor(s.id)
        setMensaje(`Sensor ${s.id}: orden de encendido enviada por MQTT (${r.via}).`)
      } else {
        const r = await apagarSensor(s.id)
        setMensaje(`Sensor ${s.id}: orden de apagado enviada por MQTT (${r.via}).`)
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
            Heartbeat (T3): si un sensor encendido no publica durante{' '}
            <strong>{monitoreo?.heartbeatSegundos ?? 120} s</strong>, pasa a
            <em> Inactivo</em> y se publica una alerta en <code>interno/alertas</code>.
            Apagar/encender (T6) viaja por MQTT al simulador. El CRUD se aplica en
            caliente, sin tocar código ni reiniciar.
          </p>
        </div>
        <button type="button" className="btn-nuevo" onClick={nuevo} disabled={form !== null}>
          + Nuevo sensor
        </button>
      </header>

      {monitoreo && (
        <div className={`panel monitoreo-panel${monitoreo.inactivos > 0 ? ' monitoreo-alerta' : ''}`}>
          <div className="monitoreo-cabecera">
            <strong>Heartbeat</strong>
            <span className={`badge ${monitoreo.inactivos > 0 ? 'badge-off' : 'badge-ok'}`}>
              {monitoreo.inactivos > 0 ? '⚠ con alertas' : '✓ al día'}
            </span>
          </div>
          <div className="monitoreo-datos">
            <span>
              Umbral: <strong>{monitoreo.heartbeatSegundos} s</strong>
            </span>
            <span>
              Activos: <strong>{monitoreo.activos}</strong>
            </span>
            <span>
              Inactivos: <strong>{monitoreo.inactivos}</strong>
            </span>
            <span>
              Apagados: <strong>{monitoreo.apagados}</strong>
            </span>
            <span>
              Comprobaciones: <strong>{monitoreo.comprobaciones}</strong>
            </span>
            {monitoreo.ultimaComprobacion && (
              <span>
                Última: <strong>{new Date(monitoreo.ultimaComprobacion).toLocaleTimeString('es-CO')}</strong>
              </span>
            )}
          </div>
        </div>
      )}

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
            {sensores.map((s) => {
              const estado = estadoSensor(s)
              return (
                <tr
                  key={s.id}
                  className={
                    estado.clase === 'off' && !s.apagado ? 'fila-desactualizada' : ''
                  }
                >
                  <td>
                    <code>{s.id}</code>
                  </td>
                  <td>{ETIQUETA_VARIABLE[s.variable] || s.variable}</td>
                  <td>{s.sede}</td>
                  <td>{s.salon || '—'}</td>
                  <td>
                    <span className={`punto ${estado.clase}`} title={estado.title} />{' '}
                    {estado.texto}
                  </td>
                  <td>{antiguedad(s.antiguedadSegundos)}</td>
                  <td className="celda-acciones">
                    <button
                      type="button"
                      className="btn-fantasma btn-activo"
                      onClick={() => toggle(s)}
                      title={s.apagado ? 'Reactivar sensor por MQTT' : 'Apagar sensor por MQTT'}
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
              )
            })}
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
