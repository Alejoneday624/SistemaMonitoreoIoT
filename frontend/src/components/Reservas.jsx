import { useEffect, useState } from 'react'
import {
  cancelarReserva,
  crearReserva,
  fetchDisponibilidad,
  fetchReservas,
  sesion,
} from '../api.js'

const SEDES = ['Sur', 'Federman']

/** Date -> valor para input datetime-local (YYYY-MM-DDTHH:mm en hora local) */
function paraInput(fecha) {
  const sinZona = new Date(fecha.getTime() - fecha.getTimezoneOffset() * 60000)
  return sinZona.toISOString().slice(0, 16)
}

/** Valor del input -> ISO-8601 Instant, que es lo que parsea el backend */
function aInstante(texto) {
  const d = new Date(texto)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

function fechaLarga(texto) {
  if (!texto) return '—'
  const d = new Date(texto)
  return Number.isNaN(d.getTime()) ? texto : d.toLocaleString()
}

/**
 * Reservas de la biblioteca (Tarea 8 — HDU-09).
 *
 * El backend admite 50 cupos simultaneos sin superposiciones y devuelve 409
 * cuando no hay aforo o se solapa; aqui solo se muestran esos motivos.
 */
function Reservas() {
  const [reservas, setReservas] = useState([])
  const [disp, setDisp] = useState(null)
  const [error, setError] = useState(null)
  const [mensaje, setMensaje] = useState(null)
  const [form, setForm] = useState(null)
  const [guardando, setGuardando] = useState(false)
  const [recarga, setRecarga] = useState(0)

  const yo = sesion()

  const cargar = () => {
    fetchReservas()
      .then((lista) => {
        setReservas(lista)
        setError(null)
      })
      .catch((e) => setError(e.message))
  }

  // Lista de reservas
  useEffect(() => {
    cargar()
  }, [recarga])

  // Barra de cupos: usa la franja del formulario si esta abierto, si no la
  // que el backend consulta por defecto (ahora + 2 horas). Se deriva aqui para
  // que el efecto solo dependa de primitivos y no refetchee en cada tecla.
  const inicioFranja = form ? aInstante(form.inicio) : null
  const finFranja = form ? aInstante(form.fin) : null

  useEffect(() => {
    let vivo = true
    fetchDisponibilidad(inicioFranja, finFranja)
      .then((d) => vivo && setDisp(d))
      .catch((e) => vivo && setError(e.message))
    return () => {
      vivo = false
    }
  }, [inicioFranja, finFranja, recarga])

  const nuevo = () => {
    const inicio = new Date()
    inicio.setMinutes(0, 0, 0)
    const fin = new Date(inicio.getTime() + 2 * 3600 * 1000)
    setForm({
      usuarioId: yo?.username ?? '',
      puesto: '',
      sede: 'Sur',
      inicio: paraInput(inicio),
      fin: paraInput(fin),
    })
    setMensaje(null)
    setError(null)
  }

  const guardar = async (ev) => {
    ev.preventDefault()
    setGuardando(true)
    setError(null)
    try {
      const inicio = aInstante(form.inicio)
      const fin = aInstante(form.fin)
      await crearReserva({
        usuarioId: form.usuarioId.trim(),
        puesto: form.puesto === '' ? null : Number(form.puesto),
        sede: form.sede,
        inicio,
        fin,
      })
      setMensaje(`Reserva creada para ${form.usuarioId.trim()} (${form.sede})`)
      setForm(null)
      setRecarga((r) => r + 1)
    } catch (e) {
      // 409 cupos agotados / superposicion, 400 fechas u obligatorios
      setError(e.message)
    } finally {
      setGuardando(false)
    }
  }

  const cancelar = async (r) => {
    if (!window.confirm(`¿Cancelar la reserva #${r.id} de ${r.usuarioId}?`)) return
    try {
      await cancelarReserva(r.id)
      setMensaje(`Reserva #${r.id} cancelada — cupo liberado`)
      setError(null)
      setRecarga((n) => n + 1)
    } catch (e) {
      setError(e.message)
    }
  }

  const ocupados = disp?.cuposOcupados ?? 0
  const capacidad = disp?.capacidadMaxima ?? 50
  const porcentaje = capacidad > 0 ? Math.round((ocupados / capacidad) * 100) : 0

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Reservas</h1>
          <p>
            Puestos de la biblioteca: 50 cupos simultáneos sin superposiciones de
            horario (HDU-09). La franja que se consulta es la del formulario.
          </p>
        </div>
        <button type="button" className="btn-nuevo" onClick={nuevo} disabled={form !== null}>
          + Nueva reserva
        </button>
      </header>

      {mensaje && <div className="aviso aviso-ok">{mensaje}</div>}
      {error && <div className="aviso aviso-error">{error}</div>}

      <div className="tarjetas">
        <div className={`tarjeta${ocupados >= capacidad ? ' tarjeta-alerta' : ''}`}>
          <span className="tarjeta-titulo">Cupos ocupados</span>
          <span className="tarjeta-valor">
            {ocupados}
            <small> / {capacidad}</small>
          </span>
          <div className="barra">
            <div
              className={`barra-relleno${ocupados >= capacidad ? ' barra-llena' : ''}`}
              style={{ width: `${Math.min(100, porcentaje)}%` }}
            />
          </div>
          <span className="tarjeta-detalle">
            {capacidad - ocupados} disponibles · {porcentaje}% de ocupación
          </span>
        </div>
        <div className="tarjeta">
          <span className="tarjeta-titulo">Franja consultada</span>
          <span className="tarjeta-valor" style={{ fontSize: 16 }}>
            {disp ? fechaLarga(disp.inicio) : '—'}
          </span>
          <span className="tarjeta-detalle">hasta {disp ? fechaLarga(disp.fin) : '—'}</span>
        </div>
      </div>

      {form && (
        <form className="panel form-sensor" onSubmit={guardar}>
          <h3>Nueva reserva</h3>
          <div className="form-campos">
            <label>
              Usuario
              <input
                value={form.usuarioId}
                onChange={(e) => setForm({ ...form, usuarioId: e.target.value })}
                placeholder="juan.perez"
                required
                maxLength={80}
              />
            </label>
            <label>
              Puesto (opcional)
              <input
                type="number"
                min={1}
                max={50}
                value={form.puesto}
                onChange={(e) => setForm({ ...form, puesto: e.target.value })}
                placeholder="libre"
              />
            </label>
            <label>
              Sede
              <select
                value={form.sede}
                onChange={(e) => setForm({ ...form, sede: e.target.value })}
              >
                {SEDES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Inicio
              <input
                type="datetime-local"
                value={form.inicio}
                onChange={(e) => setForm({ ...form, inicio: e.target.value })}
                required
              />
            </label>
            <label>
              Fin
              <input
                type="datetime-local"
                value={form.fin}
                onChange={(e) => setForm({ ...form, fin: e.target.value })}
                required
              />
            </label>
          </div>
          <div className="form-acciones">
            <button type="submit" className="btn-guardar" disabled={guardando}>
              {guardando ? 'Reservando…' : 'Crear reserva'}
            </button>
            <button
              type="button"
              className="btn-fantasma btn-activo"
              onClick={() => setForm(null)}
            >
              Cancelar
            </button>
          </div>
        </form>
      )}

      <div className="panel">
        <table className="tabla">
          <thead>
            <tr>
              <th>#</th>
              <th>Usuario</th>
              <th>Puesto</th>
              <th>Sede</th>
              <th>Inicio</th>
              <th>Fin</th>
              <th>Estado</th>
              <th>Acción</th>
            </tr>
          </thead>
          <tbody>
            {reservas.map((r) => (
              <tr key={r.id}>
                <td>
                  <code>{r.id}</code>
                </td>
                <td>{r.usuarioId}</td>
                <td>{r.puesto ?? 'libre'}</td>
                <td>{r.sede}</td>
                <td>{fechaLarga(r.inicio)}</td>
                <td>{fechaLarga(r.fin)}</td>
                <td>
                  <span className={`punto ${r.estado === 'ACTIVA' ? 'on' : 'off'}`} />{' '}
                  {r.estado === 'ACTIVA' ? 'Activa' : 'Cancelada'}
                </td>
                <td className="celda-acciones">
                  <button
                    type="button"
                    className="btn-fantasma btn-peligro btn-activo"
                    onClick={() => cancelar(r)}
                    disabled={r.estado !== 'ACTIVA'}
                    title={r.estado === 'ACTIVA' ? 'Cancelar reserva' : 'Ya cancelada'}
                  >
                    Cancelar
                  </button>
                </td>
              </tr>
            ))}
            {reservas.length === 0 && !error && (
              <tr>
                <td colSpan="8" className="vacio">
                  Todavía no hay reservas — crea la primera con el botón de arriba.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

export default Reservas
