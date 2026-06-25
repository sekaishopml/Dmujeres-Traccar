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
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.5,
        'text-opacity': 1,
        'text-opacity-transition': { duration: 150 },
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

    let isFirstRun = true;
    let fadeTimeout = null;

    const updatePoints = () => {
      if (!map.getSource(id) || !map.getLayer(id)) return;
      
      const computeAndSetData = () => {
        const zoom = map.getZoom();
        const bounds = map.getBounds();
        const west = bounds.getWest();
        const east = bounds.getEast();
        const south = bounds.getSouth();
        const north = bounds.getNorth();
        
        const inBounds = (lon, lat) => {
          if (lat < south || lat > north) return false;
          if (west <= east) {
            return lon >= west && lon <= east;
          }
          return lon >= west || lon <= east;
        };

        const minPixels = 15;
        const degreeThreshold = (360 / (256 * Math.pow(2, zoom))) * minPixels;
        
        const filtered = [];
        let lastPos = null;
        for (let i = 0; i < positions.length; i++) {
          const p = positions[i];
          
          const isStartOrEnd = i === 0 || i === positions.length - 1;
          if (!isStartOrEnd && !inBounds(p.longitude, p.latitude)) {
            continue;
          }

          if (filtered.length === 0 || isStartOrEnd) {
            filtered.push({ p, index: i });
            lastPos = p;
          } else {
            const dx = p.longitude - lastPos.longitude;
            const dy = p.latitude - lastPos.latitude;
            const distSq = dx * dx + dy * dy;
            if (distSq >= degreeThreshold * degreeThreshold) {
              filtered.push({ p, index: i });
              lastPos = p;
            }
          }
        }

        map.getSource(id).setData({
          type: 'FeatureCollection',
          features: filtered.map(({ p, index }) => ({
            type: 'Feature',
            geometry: {
              type: 'Point',
              coordinates: toMapCoordinates(p.longitude, p.latitude),
            },
            properties: {
              index,
              id: p.id,
              rotation: p.course,
              color: getSpeedColor(p.speed, minSpeed, maxSpeed),
              fixTime: formatTime(p.fixTime, 'seconds'),
            },
          })),
        });
      };

      if (isFirstRun) {
        computeAndSetData();
        isFirstRun = false;
      } else {
        if (fadeTimeout) {
          clearTimeout(fadeTimeout);
        }
        map.setPaintProperty(id, 'text-opacity', 0);
        fadeTimeout = setTimeout(() => {
          if (!map.getSource(id) || !map.getLayer(id)) return;
          computeAndSetData();
          map.setPaintProperty(id, 'text-opacity', 1);
        }, 150);
      }
    };

    updatePoints();

    map.on('zoomend', updatePoints);
    map.on('moveend', updatePoints);

    return () => {
      if (fadeTimeout) {
        clearTimeout(fadeTimeout);
      }
      map.off('zoomend', updatePoints);
      map.off('moveend', updatePoints);
    };
  }, [positions, id]);

  return showSpeedControl ? <MapSpeedLegend positions={positions} /> : null;
};

export default MapRoutePoints;
