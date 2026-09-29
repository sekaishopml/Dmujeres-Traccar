import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import './estilos/tokens.css';
import './estilos/global.css';

// Caché de consultas: el panel en vivo refresca seguido pero sin castigar al
// servidor. Los errores se reintentan una vez; el 401 lo maneja el cliente API.
// refetchOnWindowFocus queda apagado a propósito: con varias ventanas abiertas
// el sondeo propio de cada página ya mantiene los datos vivos y el foco no debe
// disparar ráfagas extra. gcTime de 10 min conserva las respuestas pesadas
// (replay, reportes) al navegar entre páginas; no altera ninguna cadencia.
// El staleTime global se mantiene corto para las vistas vivas; las consultas
// que lo necesitan lo suben por su cuenta (ver CACHE_* en operacion/datos.ts y
// paginas/admin/comunes.tsx).
const clienteConsultas = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 4_000,
      gcTime: 10 * 60_000,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={clienteConsultas}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
);
