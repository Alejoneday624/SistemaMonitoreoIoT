import { useEffect, useState } from 'react'
import Dashboard from './components/Dashboard.jsx'
import Sensores from './components/Sensores.jsx'
import ControlFallas from './components/ControlFallas.jsx'
import './App.css'

const SECCIONES = [
  { id: 'dashboard', etiqueta: 'Dashboard', icono: '📊' },
  { id: 'sensores', etiqueta: 'Sensores', icono: '🌡️' },
  { id: 'fallas', etiqueta: 'Control de fallas', icono: '🔴' },
]

function App() {
  const [seccion, setSeccion] = useState('dashboard')

  // Marca de conexion con la API (HDU-01: mostrar si los datos son confiables)
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
          {SECCIONES.map((s) => (
            <button
              key={s.id}
              type="button"
              className={seccion === s.id ? 'activa' : ''}
              onClick={() => setSeccion(s.id)}
            >
              <span aria-hidden="true">{s.icono}</span> {s.etiqueta}
            </button>
          ))}
        </nav>

        <footer className="sidebar-pie">
          Arquitectura de Software
          <br />
          <small>Prototipo · Ronda 1</small>
        </footer>
      </aside>

      <main className="contenido">
        {seccion === 'dashboard' && <Dashboard />}
        {seccion === 'sensores' && <Sensores />}
        {seccion === 'fallas' && <ControlFallas />}
      </main>
    </div>
  )
}

export default App
