import { useState } from 'react'
import { login } from '../api.js'

/**
 * Pantalla de acceso (Tarea 7 — HDU-10).
 *
 * Valida usuario y clave contra la base de datos (POST /api/auth/login) y
 * guarda la sesion para que api.js pueda enviar Authorization: Basic en las
 * peticiones a /api/admin/**.
 *
 * AVISO: el servidor corre por HTTP plano, la clave viaja en base64 sin
 * cifrar. La decision de montar HTTPS queda pendiente.
 */
function Login({ onEntrar, destino }) {
  const [usuario, setUsuario] = useState('')
  const [clave, setClave] = useState('')
  const [error, setError] = useState(null)
  const [entrando, setEntrando] = useState(false)

  const enviar = async (ev) => {
    ev.preventDefault()
    setEntrando(true)
    setError(null)
    try {
      const datos = await login(usuario.trim(), clave)
      onEntrar(datos)
    } catch (e) {
      setError(e.message)
    } finally {
      setEntrando(false)
    }
  }

  return (
    <div className="login">
      <form className="panel form-sensor login-tarjeta" onSubmit={enviar}>
        <h3>Iniciar sesión</h3>
        <p className="login-nota">
          {destino
            ? `La sección “${destino}” está reservada al rol ADMIN.`
            : 'Entra con tu usuario del campus para continuar.'}
        </p>

        {error && <div className="aviso aviso-error">{error}</div>}

        <div className="form-campos">
          <label>
            Usuario
            <input
              value={usuario}
              onChange={(e) => setUsuario(e.target.value)}
              placeholder="admin"
              autoComplete="username"
              required
              autoFocus
            />
          </label>
          <label>
            Contraseña
            <input
              type="password"
              value={clave}
              onChange={(e) => setClave(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
              required
            />
          </label>
        </div>

        <div className="form-acciones">
          <button type="submit" className="btn-guardar" disabled={entrando}>
            {entrando ? 'Verificando…' : 'Entrar'}
          </button>
        </div>
      </form>
    </div>
  )
}

export default Login
