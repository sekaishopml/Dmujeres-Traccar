// Registro central de Chart.js. La página de Batería es la única que dibuja
// gráficos, así que se registran solo las piezas usadas para no engordar el
// chunk. No se usa la escala temporal (TimeScale) porque requeriría un
// adaptador de fechas que no está en package.json: el eje X se maneja con
// categorías ya formateadas por util/formato.
import { CategoryScale, Chart, Legend, LineElement, LinearScale, PointElement, Tooltip } from 'chart.js';

Chart.register(CategoryScale, LinearScale, LineElement, PointElement, Tooltip, Legend);
