-- Velocidad del canal por lote: las apps 2.1.77+ mandan `speed` en nudos y el
-- receptor la guardaba como km/h (quedaba a la mitad). Se corrige lo ya
-- guardado una sola vez; desde este despliegue el receptor convierte. La marca
-- en atributos impide aplicarla dos veces.
UPDATE tracking.dmt_posicion
SET velocidad_kmh = velocidad_kmh * 1.852,
    atributos = atributos || '{"velocidadCorregida": "nudos_a_kmh"}'::jsonb,
    actualizado_en = now()
WHERE protocolo = 'lote'
  AND velocidad_kmh IS NOT NULL
  AND NOT (atributos ? 'velocidadCorregida');
