import { useMemo } from 'react';
import { useTranslation } from '../../common/components/LocalizationProvider';

const styleCustom = ({ tiles, minZoom, maxZoom, attribution, rasterFadeDuration }) => {
  const source = {
    type: 'raster',
    tiles,
    attribution,
    tileSize: 256,
    minzoom: minZoom,
    maxzoom: maxZoom,
    rasterFadeDuration,
  };
  Object.keys(source).forEach((key) => source[key] === undefined && delete source[key]);
  return {
    version: 8,
    sources: {
      custom: source,
    },
    glyphs: 'https://cdn.traccar.com/map/fonts/{fontstack}/{range}.pbf',
    layers: [
      {
        id: 'custom',
        type: 'raster',
        source: 'custom',
      },
    ],
  };
};

export default () => {
  const t = useTranslation();

  return useMemo(
    () => [
      {
        id: 'googleRoad',
        title: t('mapGoogleRoad'),
        style: styleCustom({
          tiles: ['/google-tiles/lyrs=m&hl=es&x={x}&y={y}&z={z}&s=Ga'],
          maxZoom: 20,
          attribution: '© Google',
          rasterFadeDuration: 0,
        }),
        available: true,
      },
    ],
    [t],
  );
};
