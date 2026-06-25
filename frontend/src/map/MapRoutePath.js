import { useTheme } from '@mui/material/styles';
import { useId, useEffect } from 'react';
import { useSelector } from 'react-redux';
import { map } from './core/MapView';
import getSpeedColor from '../common/util/colors';
import { useAttributePreference } from '../common/util/preferences';
import { toMapCoordinates } from './core/mapUtil';

const MapRoutePath = ({ positions }) => {
  const id = useId();

  const theme = useTheme();

  const reportColor = useSelector((state) => {
    const position = positions?.find(() => true);
    if (position) {
      const attributes = state.devices.items[position.deviceId]?.attributes;
      if (attributes) {
        const color = attributes['web.reportColor'];
        if (color) {
          return color;
        }
      }
    }
    return null;
  });

  const mapLineWidth = useAttributePreference('mapLineWidth', 2);
  const mapLineOpacity = useAttributePreference('mapLineOpacity', 1);

  useEffect(() => {
    map.addSource(id, {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: [],
      },
    });
    map.addLayer({
      source: id,
      id: `${id}-line`,
      type: 'line',
      layout: {
        'line-join': 'round',
        'line-cap': 'round',
      },
      paint: {
        'line-color': ['get', 'color'],
        'line-width': [
          'interpolate',
          ['linear'],
          ['zoom'],
          10, mapLineWidth,
          14, mapLineWidth * 1.5,
          18, mapLineWidth * 2.5,
        ],
        'line-opacity': mapLineOpacity,
      },
    });

    return () => {
      if (map.getLayer(`${id}-line`)) {
        map.removeLayer(`${id}-line`);
      }
      if (map.getSource(id)) {
        map.removeSource(id);
      }
    };
  }, [id]);

  useEffect(() => {
    if (map.getLayer(`${id}-line`)) {
      map.setPaintProperty(`${id}-line`, 'line-width', [
        'interpolate',
        ['linear'],
        ['zoom'],
        10, mapLineWidth,
        14, mapLineWidth * 1.5,
        18, mapLineWidth * 2.5,
      ]);
    }
  }, [id, mapLineWidth]);

  useEffect(() => {
    if (map.getLayer(`${id}-line`)) {
      map.setPaintProperty(`${id}-line`, 'line-opacity', mapLineOpacity);
    }
  }, [id, mapLineOpacity]);

  useEffect(() => {
    if (!positions || positions.length === 0) {
      map.getSource(id)?.setData({
        type: 'FeatureCollection',
        features: [],
      });
      return;
    }

    if (reportColor) {
      // Draw a single LineString feature for the entire path
      map.getSource(id)?.setData({
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: positions.map((p) => toMapCoordinates(p.longitude, p.latitude)),
            },
            properties: {
              color: reportColor,
            },
          },
        ],
      });
    } else {
      // Pre-transform coordinates to reduce map conversions by half and reuse arrays
      const transformedCoords = positions.map((p) => toMapCoordinates(p.longitude, p.latitude));

      const minSpeed = positions.map((p) => p.speed).reduce((a, b) => Math.min(a, b), Infinity);
      const maxSpeed = positions.map((p) => p.speed).reduce((a, b) => Math.max(a, b), -Infinity);
      
      const colorGroups = {};
      let currentLine = null;
      let lastColor = null;

      for (let i = 0; i < positions.length - 1; i += 1) {
        const rawColor = getSpeedColor(positions[i + 1].speed, minSpeed, maxSpeed);
        
        if (rawColor !== lastColor) {
          if (!colorGroups[rawColor]) {
            colorGroups[rawColor] = [];
          }
          currentLine = [transformedCoords[i], transformedCoords[i + 1]];
          colorGroups[rawColor].push(currentLine);
          lastColor = rawColor;
        } else {
          currentLine.push(transformedCoords[i + 1]);
        }
      }

      const features = Object.entries(colorGroups).map(([color, lines]) => ({
        type: 'Feature',
        geometry: {
          type: 'MultiLineString',
          coordinates: lines,
        },
        properties: {
          color,
        },
      }));

      map.getSource(id)?.setData({
        type: 'FeatureCollection',
        features,
      });
    }
  }, [theme, positions, reportColor, id]);

  return null;
};

export default MapRoutePath;
