import { useEffect, useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { fetchEstado, fetchMediciones } from '../api.js'

/** HDU-01: el tablero se actualiza solo, sin recargar la pagina */
const INTERVALO_MS = 5000

const COLORES = {
  Sur: '#aa3bff',
  Federman: '#12b886',
}

function hora(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/**
 * Convierte la lista de mediciones (mas reciente primero) en series por sede
 * ordenadas en el tiempo, para pintar una linea por sede en la grafica.
 * Promedia los sensores de la misma sede en el mismo segundo.
 */
function seriesPorSede(mediciones) {
  const porFecha = new Map()
  for (const m of mediciones) {
    const clave = m.fecha
    if (!porFecha.has(clave)) {
      porFecha.set(clave, { fecha: m.fecha, Sur: [], Federman: [] })
    }
    const punto = porFecha.get(clave)
    if (punto[m.sede]) punto[m.sede].push(m.valor)
  }
  const media = (a) =>
    a.length ? Math.round((a.reduce((s, v) => s + v, 0) / a.length) * 10) / 10 : null
  return [...porFecha.values()]
    .sort((a, b) => (a.fecha < b.fecha ? -1 : 1))
    .map((p) => ({ hora: hora(p.fecha), Sur: media(p.Sur), Federman: media(p.Federman) }))
}

function Tarjeta({ titulo, valor, unidad, detalle, alerta }) {
  return (
    <div className={`tarjeta${alerta ? ' tarjeta-alerta' : ''}`}>
      <span className="tarjeta-titulo">{titulo}</span>
      <strong className="tarjeta-valor">
        {valor}
        <small> {unidad}</small>
      </strong>
      <span className="tarjeta-detalle">{detalle}</span>
    </div>
  )
}

function Grafica({ titulo, mediciones, umbral, unidad, colorUmbral }) {
  const datos = seriesPorSede(mediciones)
  return (
    <div className="panel">
      <h3>{titulo}</h3>
      {datos.length === 0 ? (
        <p className="vacio">Esperando mediciones del broker MQTT…</p>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={datos} margin={{ top: 8, right: 12, left: -14, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey="hora" tick={{ fontSize: 11 }} minTickGap={40} />
            <YAxis tick={{ fontSize: 11 }} width={44} />
            <Tooltip
              formatter={(v, name) => [`${v} ${unidad}`, name]}
              labelStyle={{ color: 'var(--text-h)' }}
              contentStyle={{ background: 'var(--bg)', border: '1px solid var(--border)' }}
            />
            {umbral != null && (
              <ReferenceLine
                y={umbral}
                stroke={colorUmbral || '#fa5252'}
                strokeDasharray="6 4"
                label={{ value: `umbral ${umbral}`, fontSize: 11, fill: colorUmbral || '#fa5252' }}
              />
            )}
            <Line
              type="monotone"
              dataKey="Sur"
              stroke={COLORES.Sur}
              dot={false}
              strokeWidth={2}
              connectNulls
            />
            <Line
              type="monotone"
              dataKey="Federman"
              stroke={COLORES.Federman}
              dot={false}
              strokeWidth={2}
              connectNulls
            />
          </LineChart>
        </ResponsiveContainer>
      )}
      <div className="leyenda">
        <span style={{ background: COLORES.Sur }} /> Sur
        <span style={{ background: COLORES.Federman }} /> Federman
      </div>
    </div>
  )
}

function Dashboard() {
  const [temp, setTemp] = useState([])
  const [aire, setAire] = useState([])
  const [estado, setEstado] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let vivo = true
    const cargar = async () => {
      try {
        const [t, a, e] = await Promise.all([
          fetchMediciones('temperatura', 160),
          fetchMediciones('calidad-aire', 80),
          fetchEstado(),
        ])
        if (!vivo) return
        setTemp(t)
        setAire(a)
        setEstado(e)
        setError(null)
      } catch (err) {
        if (vivo) setError(err.message)
      }
    }
    cargar()
    const id = setInterval(cargar, INTERVALO_MS)
    return () => {
      vivo = false
      clearInterval(id)
    }
  }, [])

  // Ultimo valor por sede para las tarjetas resumen
  const ultima = (lista, sede) => lista.find((m) => m.sede === sede)
  const ultTempSur = ultima(temp, 'Sur')
  const ultTempFed = ultima(temp, 'Federman')
  const ultAireSur = ultima(aire, 'Sur')
  const ultAireFed = ultima(aire, 'Federman')
  const ppmUmbral = 900 // HDU-06 / Tabla 1

  return (
    <>
      <header className="cabecera">
        <div>
          <h1>Dashboard del campus</h1>
          <p>Condiciones ambientales por sede — actualización automática</p>
        </div>
        {estado && (
          <div className="estado-rapido">
            <span className={`punto ${estado.sensoresInactivos > 0 ? 'off' : 'on'}`} />
            {estado.sensoresActivos}/{estado.sensoresTotales} sensores activos ·{' '}
            {estado.medicionesTotales} mediciones
          </div>
        )}
      </header>

      {error && (
        <div className="aviso aviso-error">
          Sin conexion con el backend: {error}. Los datos mostrados pueden estar desactualizados.
        </div>
      )}

      <div className="tarjetas">
        <Tarjeta
          titulo="Temperatura · Sur"
          valor={ultTempSur ? ultTempSur.valor : '—'}
          unidad="°C"
          detalle={ultTempSur ? `ultima: ${hora(ultTempSur.fecha)}` : 'sin datos'}
        />
        <Tarjeta
          titulo="Temperatura · Federman"
          valor={ultTempFed ? ultTempFed.valor : '—'}
          unidad="°C"
          detalle={ultTempFed ? `ultima: ${hora(ultTempFed.fecha)}` : 'sin datos'}
        />
        <Tarjeta
          titulo="Calidad del aire · Sur"
          valor={ultAireSur ? ultAireSur.valor : '—'}
          unidad="ppm"
          alerta={ultAireSur && ultAireSur.valor >= ppmUmbral}
          detalle={
            ultAireSur
              ? ultAireSur.valor >= ppmUmbral
                ? '⚠ umbral superado'
                : `ultima: ${hora(ultAireSur.fecha)}`
              : 'sin datos'
          }
        />
        <Tarjeta
          titulo="Calidad del aire · Federman"
          valor={ultAireFed ? ultAireFed.valor : '—'}
          unidad="ppm"
          alerta={ultAireFed && ultAireFed.valor >= ppmUmbral}
          detalle={
            ultAireFed
              ? ultAireFed.valor >= ppmUmbral
                ? '⚠ umbral superado'
                : `ultima: ${hora(ultAireFed.fecha)}`
              : 'sin datos'
          }
        />
      </div>

      <div className="graficas">
        <Grafica titulo="Temperatura por sede (°C)" mediciones={temp} unidad="°C" />
        <Grafica
          titulo="Calidad del aire por sede (ppm)"
          mediciones={aire}
          umbral={ppmUmbral}
          unidad="ppm"
        />
      </div>
    </>
  )
}

export default Dashboard
