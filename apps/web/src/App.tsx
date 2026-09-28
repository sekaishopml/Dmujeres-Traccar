import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import Disposicion from './componentes/Disposicion';
import Cargando from './componentes/Cargando';

// Cada página se carga por separado: el panel abre rápido y solo baja lo que usa.
const Login = lazy(() => import('./paginas/Login'));
const Inicio = lazy(() => import('./paginas/Inicio'));
const EnVivo = lazy(() => import('./paginas/EnVivo'));
const Detalle = lazy(() => import('./paginas/Detalle'));
const Historial = lazy(() => import('./paginas/Historial'));
const Replay = lazy(() => import('./paginas/Replay'));
const Bateria = lazy(() => import('./paginas/Bateria'));
const Reportes = lazy(() => import('./paginas/Reportes'));
const Usuarios = lazy(() => import('./paginas/Usuarios'));
const Configuracion = lazy(() => import('./paginas/Configuracion'));
const Sistema = lazy(() => import('./paginas/Sistema'));

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<Cargando />}>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route element={<Disposicion />}>
            <Route index element={<Inicio />} />
            <Route path="/en-vivo" element={<EnVivo />} />
            <Route path="/unidad/:id" element={<Detalle />} />
            <Route path="/historial" element={<Historial />} />
            <Route path="/replay" element={<Replay />} />
            <Route path="/bateria" element={<Bateria />} />
            <Route path="/reportes" element={<Reportes />} />
            <Route path="/usuarios" element={<Usuarios />} />
            <Route path="/configuracion" element={<Configuracion />} />
            <Route path="/sistema" element={<Sistema />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
