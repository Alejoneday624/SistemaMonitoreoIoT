import { useEffect, useState } from 'react'
import Dashboard from './components/Dashboard.jsx'
import Sensores from './components/Sensores.jsx'
import ControlFallas from './components/ControlFallas.jsx'
import BannerFallas from './components/BannerFallas.jsx'
import Login from './components/Login.jsx'
import Usuarios from './components/Usuarios.jsx'
import Reservas from './components/Reservas.jsx'
import Errores from './components/Errores.jsx'
import { cerrarSesion, sesion } from './api.js'
import './App.css'

/**
 * Secciones del tablero.
 *
 * `publica: true` = cualquiera puede verla sin sesion. Todo lo demas esta
 * reservado al rol ADMIN (coherente con /api/admin/**, que el backend ya
 * protege con el filtro RBAC): al pulsarla aparece el login.
 */
const SECCIONES = [
  { id: 'dashboard', etiqueta: 'Dashboard', icono: '📊', publica: true },
  { id: 'sensores', etiqueta: 'Sensores', icono: '🌡️' },
  { id: 'fallas', etiqueta: 'Control de fallas', icono: '🔴' },
  { id: 'reservas', etiqueta: 'Reservas', icono: '📅' },
  { id: 'errores', etiqueta: 'Errores', icono: '⚠️' },
  { id: 'usuarios', etiqueta: 'Usuarios', icono: '👥' },
]

function App() {
  const [seccion, setSeccion] = useState('dashboard')
  const [usuario, setUsuario] = useState(() => sesion())

  // Marca de conexion con la API: indica si los datos son confiables
  const [apiOk, setApiOk] = useState(null)
  useEffect(() => {
    let vivo = true
    const ping = () =>
      fetch('/api/estado')
        .then((r) => vivo && setApiOk(r.ok))
        .catch(() => vivo && setApiOk(false))
    ping()
    const id = setInterval(ping, 10000)
    return () => {
      vivo = false
      clearInterval(id)
    }
  }, [])

  const admin = usuario !== null && usuario.rol === 'ADMIN'
  const actual = SECCIONES.find((s) => s.id === seccion) ?? SECCIONES[0]
  const requiereAdmin = !actual.publica
  const bloqueada = requiereAdmin && !admin

  const entrar = (datos) => {
    setUsuario(sesion())
    // Solo el ADMIN puede quedarse en la seccion que pidio
    if (datos.rol !== 'ADMIN') setSeccion('dashboard')
  }

  const salir = () => {
    cerrarSesion()
    setUsuario(null)
    setSeccion('dashboard')
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="marca">
          <span className="marca-punto" data-ok={apiOk} />
          <div>
            <strong>Campus IoT</strong>
            <small>Monitoreo universitario</small>
          </div>
        </div>

        <nav>
          {SECCIONES.map((s) => {
            const cerrada = !s.publica && !admin
            return (
              <button
                key={s.id}
                type="button"
                className={seccion === s.id ? 'activa' : ''}
                onClick={() => setSeccion(s.id)}
                title={cerrada ? 'Requiere usuario ADMIN' : s.etiqueta}
              >
                <span aria-hidden="true">{s.icono}</span> {s.etiqueta}
                {cerrada && (
                  <span className="candado" aria-hidden="true">
                    🔒
                  </span>
                )}
              </button>
            )
          })}
        </nav>

        <div className="sidebar-pie">
          {usuario ? (
            <div className="sesion">
              <div className="sesion-datos">
                <strong>{usuario.nombre}</strong>
                <span className="chip-rol">{usuario.rol}</span>
              </div>
              <button type="button" className="btn-fantasma btn-activo" onClick={salir}>
                Cerrar sesión
              </button>
            </div>
          ) : (
            <>
              Monitoreo ambiental en tiempo real
              <br />
              <small>Sin sesión iniciada</small>
              <br />
              <button
                type="button"
                className="btn-fantasma btn-activo"
                style={{ marginTop: 8 }}
                onClick={() => setSeccion('usuarios')}
              >
                Iniciar sesión
              </button>
            </>
          )}
        </div>
      </aside>

      <main className="contenido">
        {bloqueada ? (
          <>
            <header className="cabecera">
              <div>
                <h1>{actual.etiqueta}</h1>
                <p>Esta sección está reservada al rol ADMIN del sistema.</p>
              </div>
            </header>
            <Login onEntrar={entrar} destino={actual.etiqueta} />
          </>
        ) : (
          <>
            <BannerFallas />
            {seccion === 'dashboard' && <Dashboard />}
            {seccion === 'sensores' && <Sensores />}
            {seccion === 'fallas' && <ControlFallas />}
            {seccion === 'reservas' && <Reservas />}
            {seccion === 'errores' && <Errores />}
            {seccion === 'usuarios' && <Usuarios />}
          </>
        )}
      </main>
    </div>
  )
}

export default App
