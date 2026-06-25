import { useId, useCallback, useEffect } from 'react';
import maplibregl from 'maplibre-gl';
import { map } from './core/MapView';
import getSpeedColor from '../common/util/colors';
import { findFonts, toMapCoordinates } from './core/mapUtil';
import MapSpeedLegend from './control/MapSpeedLegend';
import { formatTime } from '../common/util/formatter';

const MapRoutePoints = ({ positions, onClick, showSpeedControl }) => {
  const id = useId();

  const onMarkerClick = useCallback(
    (event) => {
      event.preventDefault();
      const feature = event.features[0];
      if (onClick) {
        onClick(feature.properties.id, feature.properties.index);
      }
    },
    [onClick],
  );

  useEffect(() => {
    map.addSource(id, {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: [],
      },
    });
    map.addLayer({
      id,
      type: 'symbol',
      source: id,
      paint: {
        'text-color': ['get', 'color'],
      },
      layout: {
        'text-font': findFonts(map),
        'text-size': 12,
        'text-field': '▲',
        'text-allow-overlap': true,
        'text-rotate': ['get', 'rotation'],
      },
    });

    const popup = new maplibregl.Popup({
      closeButton: false,
      closeOnClick: false,
    });

    const onMouseEnter = (e) => {
      map.getCanvas().style.cursor = 'pointer';
      const features = map.queryRenderedFeatures(e.point, { layers: [id] });
      if (features.length > 0) {
        const feature = features[0];
        const coordinates = feature.geometry.coordinates.slice();
        const fixTime = feature.properties.fixTime;
        if (fixTime) {
          popup.setLngLat(coordinates).setHTML(`<div style="padding: 4px; font-family: sans-serif; font-size: 12px; color: #333;">${fixTime}</div>`).addTo(map);
        }
      }
    };

    const onMouseLeave = () => {
      map.getCanvas().style.cursor = '';
      popup.remove();
    };

    map.on('mouseenter', id, onMouseEnter);
    map.on('mouseleave', id, onMouseLeave);
    map.on('click', id, onMarkerClick);

    return () => {
      map.off('mouseenter', id, onMouseEnter);
      map.off('mouseleave', id, onMouseLeave);
      map.off('click', id, onMarkerClick);
      popup.remove();

      if (map.getLayer(id)) {
        map.removeLayer(id);
      }
      if (map.getSource(id)) {
        map.removeSource(id);
      }
    };
  }, [onMarkerClick, id]);

  useEffect(() => {
    if (!positions || positions.length === 0) {
      map.getSource(id)?.setData({
        type: 'FeatureCollection',
        features: [],
      });
      return;
    }

    const maxSpeed = positions.reduce((a, p) => Math.max(a, p.speed), -Infinity);
    const minSpeed = positions.reduce((a, p) => Math.min(a, p.speed), Infinity);

    const updatePoints = () => {
      if (!map.getSource(id)) return;
      
      const zoom = map.getZoom();
      const minPixels = 15;
      const degreeThreshold = (360 / (256 * Math.pow(2, zoom))) * minPixels;
      
      const filtered = [];
      let lastPos = null;
      for (let i = 0; i < positions.length; i++) {
        const p = positions[i];
        if (i === 0 || i === positions.length - 1) {
          filtered.push(p);
          lastPos = p;
        } else {
          const dx = p.longitude - lastPos.longitude;
          const dy = p.latitude - lastPos.latitude;
          const distSq = dx * dx + dy * dy;
          if (distSq >= degreeThreshold * degreeThreshold) {
            filtered.push(p);
            lastPos = p;
          }
        }
      }

      map.getSource(id).setData({
        type: 'FeatureCollection',
        features: filtered.map((position) => {
          const origIndex = positions.indexOf(position);
          return {
            type: 'Feature',
            geometry: {
              type: 'Point',
              coordinates: toMapCoordinates(position.longitude, position.latitude),
            },
            properties: {
              index: origIndex >= 0 ? origIndex : 0,
              id: position.id,
              rotation: position.course,
              color: getSpeedColor(position.speed, minSpeed, maxSpeed),
              fixTime: formatTime(position.fixTime, 'seconds'),
            },
          };
        }),
      });
    };

    updatePoints();

    map.on('zoom', updatePoints);
    map.on('moveend', updatePoints);

    return () => {
      map.off('zoom', updatePoints);
      map.off('moveend', updatePoints);
    };
  }, [positions, id]);

  return showSpeedControl ? <MapSpeedLegend positions={positions} /> : null;
};

export default MapRoutePoints;
