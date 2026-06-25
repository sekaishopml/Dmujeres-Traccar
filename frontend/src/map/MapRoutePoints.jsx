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
      filter: ['==', ['get', 'type'], 'point'],
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

    map.addLayer({
      id: `${id}-stops-bg`,
      type: 'circle',
      source: id,
      filter: ['==', ['get', 'type'], 'stop'],
      paint: {
        'circle-color': '#ff9800',
        'circle-radius': 9,
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 1.5,
        'circle-opacity': 1,
        'circle-opacity-transition': { duration: 150 },
      },
    });

    map.addLayer({
      id: `${id}-stops-text`,
      type: 'symbol',
      source: id,
      filter: ['==', ['get', 'type'], 'stop'],
      paint: {
        'text-color': '#ffffff',
        'text-opacity': 1,
        'text-opacity-transition': { duration: 150 },
      },
      layout: {
        'text-font': findFonts(map),
        'text-size': 11,
        'text-field': 'P',
        'text-allow-overlap': true,
      },
    });

    const popup = new maplibregl.Popup({
      closeButton: false,
      closeOnClick: false,
    });

    const onMouseEnter = (e) => {
      map.getCanvas().style.cursor = 'pointer';
      const features = map.queryRenderedFeatures(e.point, { layers: [id, `${id}-stops-bg`] });
      if (features.length > 0) {
        const feature = features[0];
        const coordinates = feature.geometry.coordinates.slice();
        
        if (feature.properties.type === 'stop') {
          const stopNumber = feature.properties.stopNumber;
          const durationText = feature.properties.durationText;
          const timeRangeText = feature.properties.timeRangeText;
          
          popup.setLngLat(coordinates).setHTML(`
            <div style="padding: 6px 8px; font-family: 'Outfit', 'Inter', sans-serif; font-size: 12px; color: #333; line-height: 1.4;">
              <strong style="color: #ff9800; font-size: 13px; display: block; margin-bottom: 2px;">Parada #${stopNumber}</strong>
              <div style="font-weight: 600;">Detenido: ${durationText}</div>
              <div style="color: #666; font-size: 11px; margin-top: 2px;">Horario: ${timeRangeText}</div>
            </div>
          `).addTo(map);
        } else {
          const fixTime = feature.properties.fixTime;
          if (fixTime) {
            popup.setLngLat(coordinates).setHTML(`<div style="padding: 4px; font-family: sans-serif; font-size: 12px; color: #333;">${fixTime}</div>`).addTo(map);
          }
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

    map.on('mouseenter', `${id}-stops-bg`, onMouseEnter);
    map.on('mouseleave', `${id}-stops-bg`, onMouseLeave);
    map.on('click', `${id}-stops-bg`, onMarkerClick);

    return () => {
      map.off('mouseenter', id, onMouseEnter);
      map.off('mouseleave', id, onMouseLeave);
      map.off('click', id, onMarkerClick);

      map.off('mouseenter', `${id}-stops-bg`, onMouseEnter);
      map.off('mouseleave', `${id}-stops-bg`, onMouseLeave);
      map.off('click', `${id}-stops-bg`, onMarkerClick);

      popup.remove();

      if (map.getLayer(`${id}-stops-text`)) {
        map.removeLayer(`${id}-stops-text`);
      }
      if (map.getLayer(`${id}-stops-bg`)) {
        map.removeLayer(`${id}-stops-bg`);
      }
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
        
        const stops = detectStops(positions);
        
        const stoppedIndices = new Set();
        stops.forEach(stop => {
          for (let idx = stop.startIndex; idx <= stop.endIndex; idx++) {
            stoppedIndices.add(idx);
          }
        });

        const filtered = [];
        let lastPos = null;
        for (let i = 0; i < positions.length; i++) {
          const p = positions[i];
          
          if (stoppedIndices.has(i)) {
            continue;
          }

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

        const arrowFeatures = filtered.map(({ p, index }) => ({
          type: 'Feature',
          geometry: {
            type: 'Point',
            coordinates: toMapCoordinates(p.longitude, p.latitude),
          },
          properties: {
            index,
            id: p.id,
            type: 'point',
            rotation: p.course,
            color: getSpeedColor(p.speed, minSpeed, maxSpeed),
            fixTime: formatTime(p.fixTime, 'seconds'),
          },
        }));

        const stopFeatures = stops
          .map((stop, stopIdx) => ({ stop, stopIdx }))
          .filter(({ stop }) => inBounds(stop.longitude, stop.latitude))
          .map(({ stop, stopIdx }) => ({
            type: 'Feature',
            geometry: {
              type: 'Point',
              coordinates: toMapCoordinates(stop.longitude, stop.latitude),
            },
            properties: {
              index: stop.startIndex,
              id: stop.startIndex + 990000,
              type: 'stop',
              stopNumber: stopIdx + 1,
              durationText: formatDuration(stop.duration),
              timeRangeText: `${formatTimeOnly(stop.startTime)} - ${formatTimeOnly(stop.endTime)}`,
            },
          }));

        map.getSource(id).setData({
          type: 'FeatureCollection',
          features: [...arrowFeatures, ...stopFeatures],
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

const mergeStops = (stops, positions, mergeDistanceThreshold = 80, mergeTimeThresholdMs = 5 * 60 * 1000) => {
  if (stops.length < 2) return stops;
  
  let merged = true;
  while (merged) {
    merged = false;
    const nextStops = [];
    let i = 0;
    
    while (i < stops.length) {
      if (i === stops.length - 1) {
        nextStops.push(stops[i]);
        break;
      }
      
      const s1 = stops[i];
      const s2 = stops[i + 1];
      
      const latMid = (s1.latitude + s2.latitude) * Math.PI / 360;
      const dy = (s2.latitude - s1.latitude) * 111320;
      const dx = (s2.longitude - s1.longitude) * 111320 * Math.cos(latMid);
      const dist = Math.sqrt(dx * dx + dy * dy);
      
      const timeGap = new Date(s2.startTime).getTime() - new Date(s1.endTime).getTime();
      
      if (dist < mergeDistanceThreshold && timeGap < mergeTimeThresholdMs) {
        const startIndex = s1.startIndex;
        const endIndex = s2.endIndex;
        const startTime = s1.startTime;
        const endTime = s2.endTime;
        const duration = new Date(endTime).getTime() - new Date(startTime).getTime();
        
        const getBattery = (p) => p.attributes?.batteryLevel ?? p.attributes?.battery ?? null;
        const startBattery = getBattery(positions[startIndex]);
        const endBattery = getBattery(positions[endIndex]);
        
        let sumLat = 0;
        let sumLon = 0;
        let count = 0;
        let address = "";
        
        for (let k = startIndex; k <= endIndex; k++) {
          sumLat += positions[k].latitude;
          sumLon += positions[k].longitude;
          count++;
          if (!address && positions[k].address) {
            address = positions[k].address;
          }
        }
        
        if (!address) {
          address = `${positions[startIndex].latitude.toFixed(5)}, ${positions[startIndex].longitude.toFixed(5)}`;
        }
        
        const mergedStop = {
          startIndex,
          endIndex,
          startTime,
          endTime,
          duration,
          startBattery,
          endBattery,
          address,
          latitude: sumLat / count,
          longitude: sumLon / count
        };
        
        stops[i + 1] = mergedStop;
        merged = true;
      } else {
        nextStops.push(s1);
      }
      i++;
    }
    stops = nextStops;
  }
  return stops;
};

const detectStops = (positions, distanceThreshold = 50, timeThresholdMs = 3 * 60 * 1000) => {
  const stops = [];
  if (!positions || positions.length < 2) return stops;

  let i = 0;
  while (i < positions.length) {
    let j = i + 1;
    let stopEndIndex = i;
    
    let sumLat = positions[i].latitude;
    let sumLon = positions[i].longitude;
    let count = 1;
    
    while (j < positions.length) {
      const pCurrent = positions[j];
      const centroidLat = sumLat / count;
      const centroidLon = sumLon / count;
      
      const latMid = (centroidLat + pCurrent.latitude) * Math.PI / 360;
      const dy = (pCurrent.latitude - centroidLat) * 111320;
      const dx = (pCurrent.longitude - centroidLon) * 111320 * Math.cos(latMid);
      const dist = Math.sqrt(dx * dx + dy * dy);
      
      if (dist < distanceThreshold) {
        sumLat += pCurrent.latitude;
        sumLon += pCurrent.longitude;
        count++;
        stopEndIndex = j;
        j++;
      } else {
        break;
      }
    }
    
    const startPos = positions[i];
    const endPos = positions[stopEndIndex];
    const duration = new Date(endPos.fixTime).getTime() - new Date(startPos.fixTime).getTime();
    
    // Average speed filter to avoid false positives (moving slowly / traffic / signal loss)
    const candidatePoints = positions.slice(i, stopEndIndex + 1);
    const avgSpeed = candidatePoints.reduce((sum, p) => sum + p.speed, 0) / candidatePoints.length;
    
    if (duration >= timeThresholdMs && avgSpeed < 1.5) {
      const getBattery = (p) => p.attributes?.batteryLevel ?? p.attributes?.battery ?? null;
      const startBattery = getBattery(startPos);
      const endBattery = getBattery(endPos);

      let address = "";
      for (let k = i; k <= stopEndIndex; k++) {
        if (positions[k].address) {
          address = positions[k].address;
          break;
        }
      }
      if (!address) {
        address = `${startPos.latitude.toFixed(5)}, ${startPos.longitude.toFixed(5)}`;
      }

      stops.push({
        startIndex: i,
        endIndex: stopEndIndex,
        startTime: startPos.fixTime,
        endTime: endPos.fixTime,
        duration,
        startBattery,
        endBattery,
        address,
        latitude: sumLat / count,
        longitude: sumLon / count
      });
      
      i = stopEndIndex + 1;
    } else {
      i++;
    }
  }
  
  return mergeStops(stops, positions);
};

const formatTimeOnly = (value) => {
  if (value) {
    const d = new Date(value);
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  return '';
};

const formatDuration = (ms) => {
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const d = Math.floor(hours / 24);

  const parts = [];
  if (d > 0) parts.push(`${d}d`);
  if (hours % 24 > 0) parts.push(`${hours % 24}h`);
  if (minutes % 60 > 0) parts.push(`${minutes % 60}m`);
  if (seconds % 60 > 0 && parts.length === 0) parts.push(`${seconds % 60}s`);
  return parts.join(' ') || '0s';
};

export default MapRoutePoints;
