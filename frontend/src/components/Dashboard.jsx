import { useEffect, useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { fetchDemoEstado, fetchEstado, fetchMediciones } from '../api.js'

/** El tablero se actualiza solo, sin recargar la pagina */
const INTERVALO_MS = 5000

/** Cadencia de publicacion de los sensores: sirve para rellenar la serie */
const PASO_MS = 5000

/** Sin datos recientes el marcador de la tarjeta cambia a "sin datos" */
const VIGENCIA_S = 15

const COLORES = {
  Sur: '#aa3bff',
  Federman: '#12b886',
}

function hora(fecha) {
  if (fecha == null) return ''
  const d = fecha instanceof Date ? fecha : new Date(fecha)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/**
 * Convierte la lista de mediciones (mas reciente primero) en una serie
 * temporal continua por sede:
 *  - aglomera por slots de PASO_MS y promedia los sensores de la misma sede;
 *  - rellena con null los slots sin datos, para que la linea se CORTEN
 *    donde falten mediciones (caida de un servicio) en vez de puentealas;
 *  - usa el timestamp numerico en el eje X para que el hueco sea
 *    proporcional al tiempo real transcurrido.
 */
function seriesPorSede(mediciones) {
  const porSlot = new Map()
  let min = Infinity
  let max = -Infinity
  for (const m of mediciones) {
    const t = Date.parse(m.fecha)
    if (Number.isNaN(t)) continue
    const slot = Math.round(t / PASO_MS) * PASO_MS
    let punto = porSlot.get(slot)
    if (!punto) {
      punto = { Sur: [], Federman: [] }
      porSlot.set(slot, punto)
    }
    if (punto[m.sede]) punto[m.sede].push(m.valor)
    if (t < min) min = t
    if (t > max) max = t
  }
  if (min === Infinity) return []

  const media = (a) =>
    a.length ? Math.round(a.reduce((s, v) => s + v, 0) / a.length * 10) / 10 : null

  const inicio = Math.floor(min / PASO_MS) * PASO_MS
  const fin = Math.ceil(max / PASO_MS) * PASO_MS
  const salida = []
  for (let t = inicio; t <= fin; t += PASO_MS) {
    const punto = porSlot.get(t)
    salida.push({
      ts: t,
      Sur: punto ? media(punto.Sur) : null,
      Federman: punto ? media(punto.Federman) : null,
    })
  }
  return salida
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

function Grafica({ titulo, mediciones, umbral, unidad, colorUmbral, falla }) {
  const datos = seriesPorSede(mediciones)
  const ahora = Date.now()

  // El eje llega hasta "ahora" mientras haya falla: el espacio vacio crece
  // y la banda sombreada marca el periodo caido.
  const ultimo = datos.length ? datos[datos.length - 1].ts : ahora
  const xMax = falla?.caido ? Math.max(ultimo, ahora) : ultimo
  const xMin = datos.length ? datos[0].ts : 0
  const xFalla = falla?.caido && falla.desde ? Date.parse(falla.desde) : null

  return (
    <div className="panel">
      <h3>{titulo}</h3>
      {datos.length === 0 ? (
        <p className="vacio">Esperando mediciones del broker MQTT…</p>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={datos} margin={{ top: 8, right: 12, left: -14, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              type="number"
              dataKey="ts"
              domain={[xMin, xMax]}
              tickFormatter={hora}
              tick={{ fontSize: 11 }}
              minTickGap={60}
            />
            <YAxis tick={{ fontSize: 11 }} width={44} domain={['auto', 'auto']} />
            <Tooltip
              formatter={(v, name) => [`${v} ${unidad}`, name]}
              labelFormatter={hora}
              labelStyle={{ color: 'var(--text-h)' }}
              contentStyle={{ background: 'var(--bg)', border: '1px solid var(--border)' }}
            />
            {xFalla != null && xMax > xFalla && (
              <ReferenceArea
                x1={xFalla}
                x2={xMax}
                fill="#fa5252"
                fillOpacity={0.12}
                label={{
                  value: `${falla.victima || 'servicio'} caído`,
                  fontSize: 11,
                  fill: '#fa5252',
                  position: 'insideTopRight',
                }}
              />
            )}
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
            />
            <Line
              type="monotone"
              dataKey="Federman"
              stroke={COLORES.Federman}
              dot={false}
              strokeWidth={2}
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

/** Detalle de la tarjeta: "ultima: HH:MM:SS" o "sin datos hace X s" */
function detalleMedicion(medicion) {
  if (!medicion) return 'sin datos'
  const hace = Math.round((Date.now() - Date.parse(medicion.fecha)) / 1000)
  if (hace > VIGENCIA_S) return `sin datos hace ${hace} s`
  return `ultima: ${hora(medicion.fecha)}`
}

function Dashboard() {
  const [temp, setTemp] = useState([])
  const [aire, setAire] = useState([])
  const [estado, setEstado] = useState(null)
  const [falla, setFalla] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let vivo = true
    const cargar = async () => {
      try {
        const [t, a, e, f] = await Promise.all([
          fetchMediciones('temperatura', 160),
          fetchMediciones('calidad-aire', 80),
          fetchEstado(),
          fetchDemoEstado(),
        ])
        if (!vivo) return
        setTemp(t)
        setAire(a)
        setEstado(e)
        setFalla(f)
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
  const ppmUmbral = 900

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
          detalle={detalleMedicion(ultTempSur)}
        />
        <Tarjeta
          titulo="Temperatura · Federman"
          valor={ultTempFed ? ultTempFed.valor : '—'}
          unidad="°C"
          detalle={detalleMedicion(ultTempFed)}
        />
        <Tarjeta
          titulo="Calidad del aire · Sur"
          valor={ultAireSur ? ultAireSur.valor : '—'}
          unidad="ppm"
          alerta={ultAireSur && ultAireSur.valor >= ppmUmbral}
          detalle={
            ultAireSur && ultAireSur.valor >= ppmUmbral
              ? '⚠ umbral superado'
              : detalleMedicion(ultAireSur)
          }
        />
        <Tarjeta
          titulo="Calidad del aire · Federman"
          valor={ultAireFed ? ultAireFed.valor : '—'}
          unidad="ppm"
          alerta={ultAireFed && ultAireFed.valor >= ppmUmbral}
          detalle={
            ultAireFed && ultAireFed.valor >= ppmUmbral
              ? '⚠ umbral superado'
              : detalleMedicion(ultAireFed)
          }
        />
      </div>

      <div className="graficas">
        <Grafica
          titulo="Temperatura por sede (°C)"
          mediciones={temp}
          unidad="°C"
          falla={falla}
        />
        <Grafica
          titulo="Calidad del aire por sede (ppm)"
          mediciones={aire}
          umbral={ppmUmbral}
          unidad="ppm"
          falla={falla}
        />
      </div>
    </>
  )
}

export default Dashboard
