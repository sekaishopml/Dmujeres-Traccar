-- Avisos del cronograma en el panel: hasta cuándo vio cada cuenta del panel
-- el cronograma de cada persona. Lo cargado o editado después de esa marca es
-- "nuevo" y se avisa con un número en Reportes y un círculo junto a la
-- persona. Abrir su cronograma actualiza la marca.
BEGIN;

CREATE TABLE IF NOT EXISTS operations.dmt_cronograma_visto (
    usuario_id      BIGINT      NOT NULL REFERENCES iam.dmt_usuario(id) ON DELETE CASCADE,
    dispositivo_id  BIGINT      NOT NULL REFERENCES tracking.dmt_dispositivo(id) ON DELETE CASCADE,
    visto_en        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, dispositivo_id)
);

CREATE INDEX IF NOT EXISTS dmt_actividad_actualizado_idx
    ON operations.dmt_actividad (dispositivo_id, actualizado_en) WHERE NOT eliminada;

GRANT SELECT, INSERT, UPDATE, DELETE ON operations.dmt_cronograma_visto TO dmt;

COMMIT;
