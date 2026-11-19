import { useEffect, useState } from 'react'
import {
  cambiarEstadoUsuario,
  cambiarRolUsuario,
  crearUsuario,
  eliminarUsuario,
  fetchRoles,
  fetchUsuarios,
  sesion,
} from '../api.js'

const ROLES_POR_DEFECTO = ['ADMIN', 'MANTENIMIENTO', 'COMUNIDAD']

const USUARIO_VACIO = {
  username: '',
  nombre: '',
  email: '',
  password: '',
  rol: 'COMUNIDAD',
}

function fechaCorta(texto) {
  if (!texto) return '—'
  const d = new Date(texto)
  return Number.isNaN(d.getTime()) ? texto : d.toLocaleString()
}

/**
 * Gestion de usuarios y roles (Tarea 7 — HDU-10 / HDU-11).
 *
 * Todo bajo /api/admin/**: si el backend responde 403 aqui se ve el motivo
 * que devuelve el filtro RBAC. Las reglas de negocio (password obligatorio,
 * no autoeliminacion, no borrar el ultimo ADMIN activo) tambien las impone el
 * backend y aqui solo se muestran.
 */
function Usuarios() {
  const [usuarios, setUsuarios] = useState([])
  const [roles, setRoles] = useState([])
  const [error, setError] = useState(null)
  const [mensaje, setMensaje] = useState(null)

  const [form, setForm] = useState(null)
  const [guardando, setGuardando] = useState(false)

  const yo = sesion()

  const cargar = () => {
    fetchUsuarios()
      .then((lista) => {
        setUsuarios(lista)
        setError(null)
      })
      .catch((e) => setError(e.message))

    fetchRoles()
      .then(setRoles)
      .catch(() => setRoles(ROLES_POR_DEFECTO))
  }

  useEffect(() => {
    cargar()
  }, [])

  const avisoOk = (texto) => {
    setMensaje(texto)
    setError(null)
  }

  const avisoFallo = (e) => {
    setError(e.message)
    setMensaje(null)
  }

  const cambiarRol = async (u, rol) => {
    try {
      await cambiarRolUsuario(u.id, rol)
      avisoOk(`${u.username} ahora tiene el rol ${rol}`)
      cargar()
    } catch (e) {
      avisoFallo(e)
      cargar()
    }
  }

  const alternarEstado = async (u) => {
    try {
      await cambiarEstadoUsuario(u.id, !u.activo)
      avisoOk(`${u.username} ${u.activo ? 'desactivado' : 'activado'}`)
      cargar()
    } catch (e) {
      avisoFallo(e)
    }
  }

  const eliminar = async (u) => {
    if (!window.confirm(`¿Eliminar el usuario ${u.username}? Esta acción no se puede deshacer.`)) {
      return
    }
    try {
      await eliminarUsuario(u.id)
      avisoOk(`Usuario ${u.username} eliminado`)
      cargar()
    } catch (e) {
      // Aqui caen los bloqueos del backend: autoeliminacion y ultimo ADMIN
      avisoFallo(e)
      cargar()
    }
  }

  const nuevo = () => {
    setForm({ ...USUARIO_VACIO })
    setMensaje(null)
    setError(null)
  }

  const guardar = async (ev) => {
    ev.preventDefault()
    setGuardando(true)
    setError(null)
    try {
      await crearUsuario({
        username: form.username.trim(),
        nombre: form.nombre.trim(),
        email: form.email.trim(),
        password: form.password,
        rol: form.rol,
      })
      avisoOk(`Usuario ${form.username.trim()} creado con rol ${form.rol}`)
      setForm(null)
      cargar()
    } catch (e) {
      avisoFallo(e)
    } finally {
      setGuardando(false)
    }
  }

  const catalogo = roles.length > 0 ? roles : ROLES_POR_DEFECTO

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Usuarios</h1>
          <p>
            Alta, baja y cambio de rol de las cuentas del campus (HDU-10 / HDU-11).
            Solo el rol ADMIN llega hasta aquí: el resto recibe 403 en el filtro
            RBAC. El password es obligatorio y se guarda con salt propio.
          </p>
        </div>
        <button type="button" className="btn-nuevo" onClick={nuevo} disabled={form !== null}>
          + Nuevo usuario
        </button>
      </header>

      {mensaje && <div className="aviso aviso-ok">{mensaje}</div>}
      {error && <div className="aviso aviso-error">{error}</div>}

      {form && (
        <form className="panel form-sensor" onSubmit={guardar}>
          <h3>Nuevo usuario</h3>
          <div className="form-campos">
            <label>
              Usuario
              <input
                value={form.username}
                onChange={(e) => setForm({ ...form, username: e.target.value })}
                placeholder="juan.perez"
                required
                maxLength={40}
              />
            </label>
            <label>
              Nombre completo
              <input
                value={form.nombre}
                onChange={(e) => setForm({ ...form, nombre: e.target.value })}
                placeholder="Juan Pérez"
                required
                maxLength={80}
              />
            </label>
            <label>
              Correo
              <input
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
                placeholder="juan@campus.edu.co"
                required
              />
            </label>
            <label>
              Contraseña
              <input
                type="password"
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="mínimo 6 caracteres"
                required
                minLength={6}
                autoComplete="new-password"
              />
            </label>
            <label>
              Rol
              <select value={form.rol} onChange={(e) => setForm({ ...form, rol: e.target.value })}>
                {catalogo.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="form-acciones">
            <button type="submit" className="btn-guardar" disabled={guardando}>
              {guardando ? 'Creando…' : 'Crear usuario'}
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
              <th>Usuario</th>
              <th>Correo</th>
              <th>Rol</th>
              <th>Estado</th>
              <th>Creado</th>
              <th>Acción</th>
            </tr>
          </thead>
          <tbody>
            {usuarios.map((u) => {
              const esYo = yo !== null && u.username === yo.username
              return (
                <tr key={u.id}>
                  <td>
                    <strong>{u.nombre}</strong>
                    <br />
                    <code>{u.username}</code>
                    {esYo && <span className="chip">tú</span>}
                  </td>
                  <td>{u.email || '—'}</td>
                  <td>
                    <select
                      className="select-rol"
                      value={u.rol}
                      onChange={(e) => cambiarRol(u, e.target.value)}
                      title={`Cambiar rol de ${u.username}`}
                    >
                      {catalogo.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <span className={`punto ${u.activo ? 'on' : 'off'}`} />{' '}
                    {u.activo ? 'Activo' : 'Inactivo'}
                  </td>
                  <td>{fechaCorta(u.creadoEn)}</td>
                  <td className="celda-acciones">
                    <button
                      type="button"
                      className="btn-fantasma btn-activo"
                      onClick={() => alternarEstado(u)}
                    >
                      {u.activo ? 'Desactivar' : 'Activar'}
                    </button>
                    <button
                      type="button"
                      className="btn-fantasma btn-peligro btn-activo"
                      onClick={() => eliminar(u)}
                      disabled={esYo}
                      title={
                        esYo
                          ? 'No puedes eliminar tu propia cuenta'
                          : `Eliminar a ${u.username}`
                      }
                    >
                      Eliminar
                    </button>
                  </td>
                </tr>
              )
            })}
            {usuarios.length === 0 && !error && (
              <tr>
                <td colSpan="6" className="vacio">
                  Cargando usuarios…
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  )
}

export default Usuarios
