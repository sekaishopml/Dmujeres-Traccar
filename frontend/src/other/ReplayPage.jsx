import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import {
  IconButton,
  Paper,
  Slider,
  Toolbar,
  Typography,
  Select,
  MenuItem,
  FormControl,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  List,
  ListItemButton,
  ListItemText,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { makeStyles } from 'tss-react/mui';
import TuneIcon from '@mui/icons-material/Tune';
import DownloadIcon from '@mui/icons-material/Download';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import PauseIcon from '@mui/icons-material/Pause';
import FastForwardIcon from '@mui/icons-material/FastForward';
import FastRewindIcon from '@mui/icons-material/FastRewind';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useSelector } from 'react-redux';
import MapView, { map } from '../map/core/MapView';
import MapRoutePath from '../map/MapRoutePath';
import MapRoutePoints from '../map/MapRoutePoints';
import MapPositions from '../map/MapPositions';
import { formatTime } from '../common/util/formatter';
import ReportFilter from '../reports/components/ReportFilter';
import { useTranslation } from '../common/components/LocalizationProvider';
import { useCatchCallback } from '../reactHelper';
import MapCamera from '../map/MapCamera';
import MapGeofence from '../map/MapGeofence';
import StatusCard from '../common/components/StatusCard';
import MapScale from '../map/MapScale';
import BackIcon from '../common/components/BackIcon';
import fetchOrThrow from '../common/util/fetchOrThrow';
import MapOverlay from '../map/overlay/MapOverlay';

const useStyles = makeStyles()((theme) => ({
  root: {
    height: '100%',
  },
  sidebar: {
    display: 'flex',
    flexDirection: 'column',
    position: 'fixed',
    zIndex: 3,
    left: 0,
    top: 0,
    margin: theme.spacing(1.5),
    width: theme.dimensions.drawerWidthDesktop,
    [theme.breakpoints.down('md')]: {
      width: '100%',
      margin: 0,
    },
  },
  title: {
    flexGrow: 1,
  },
  slider: {
    width: '100%',
  },
  controls: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  formControlLabel: {
    height: '100%',
    width: '100%',
    paddingRight: theme.spacing(1),
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  content: {
    display: 'flex',
    flexDirection: 'column',
    padding: theme.spacing(2),
    [theme.breakpoints.down('md')]: {
      margin: theme.spacing(1),
    },
    [theme.breakpoints.up('md')]: {
      marginTop: theme.spacing(1),
    },
  },
}));

const ReplayPage = () => {
  const t = useTranslation();
  const { classes } = useStyles();
  const navigate = useNavigate();

  const [searchParams] = useSearchParams();

  const defaultDeviceId = useSelector((state) => state.devices.selectedId);

  const [positions, setPositions] = useState([]);
  const [index, setIndex] = useState(0);
  const [selectedDeviceId, setSelectedDeviceId] = useState(defaultDeviceId);
  const [showCard, setShowCard] = useState(false);
  const from = searchParams.get('from');
  const to = searchParams.get('to');
  const [playing, setPlaying] = useState(false);
  const [loading, setLoading] = useState(false);
  const [filterOpen, setFilterOpen] = useState(false);
  const [speed, setSpeed] = useState(1);

  const loaded = Boolean(from && to && !loading && positions.length);

  const deviceName = useSelector((state) => {
    if (selectedDeviceId) {
      const device = state.devices.items[selectedDeviceId];
      if (device) {
        return device.name;
      }
    }
    return null;
  });

  useEffect(() => {
    if (!from && !to) {
      setPositions([]);
    }
  }, [from, to, setPositions]);

  const speedRef = useRef(1);
  useEffect(() => {
    speedRef.current = speed;
  }, [speed]);

  const positionsRef = useRef([]);
  useEffect(() => {
    positionsRef.current = positions;
  }, [positions]);

  useEffect(() => {
    let animationFrameId;
    let lastTimestamp = null;
    let accumulatedTime = 0;

    const loop = (timestamp) => {
      if (!lastTimestamp) {
        lastTimestamp = timestamp;
        animationFrameId = requestAnimationFrame(loop);
        return;
      }

      const elapsed = timestamp - lastTimestamp;
      lastTimestamp = timestamp;

      // Delay between points at 1x speed is 1000ms.
      const pointDelay = 1000 / speedRef.current;
      accumulatedTime += elapsed;

      if (accumulatedTime >= pointDelay) {
        const steps = Math.floor(accumulatedTime / pointDelay);
        accumulatedTime %= pointDelay;

        setIndex((prevIndex) => {
          const nextIndex = prevIndex + steps;
          if (nextIndex >= positionsRef.current.length - 1) {
            return positionsRef.current.length - 1;
          }
          return nextIndex;
        });
      }

      animationFrameId = requestAnimationFrame(loop);
    };

    if (playing && positions.length > 0) {
      animationFrameId = requestAnimationFrame(loop);
    }

    return () => {
      if (animationFrameId) {
        cancelAnimationFrame(animationFrameId);
      }
    };
  }, [playing, positions]);

  useEffect(() => {
    if (index >= positions.length - 1) {
      setPlaying(false);
    }
  }, [index, positions]);

  const stops = useMemo(() => detectStops(positions), [positions]);

  const handleStopClick = useCallback((stop) => {
    setIndex(stop.startIndex);
    if (map) {
      map.easeTo({
        center: [stop.longitude, stop.latitude],
        zoom: 17,
        duration: 1000
      });
    }
  }, [setIndex]);

  const onPointClick = useCallback(
    (_, index) => {
      setIndex(index);
    },
    [setIndex],
  );

  const onMarkerClick = useCallback(
    (positionId) => {
      setShowCard(!!positionId);
    },
    [setShowCard],
  );

  const onShow = useCatchCallback(
    async ({ deviceIds, from, to }) => {
      const deviceId = deviceIds.find(() => true);
      setLoading(true);
      setSelectedDeviceId(deviceId);
      const query = new URLSearchParams({ deviceId, from, to });
      try {
        const response = await fetchOrThrow(`/api/positions?${query.toString()}`);
        setIndex(0);
        const positions = await response.json();
        setPositions(positions);
        if (!positions.length) {
          throw Error(t('sharedNoData'));
        }
        setFilterOpen(false);
      } finally {
        setLoading(false);
      }
    },
    [t],
  );

  const handleDownload = () => {
    const query = new URLSearchParams({ deviceId: selectedDeviceId, from, to });
    window.location.assign(`/api/positions/kml?${query.toString()}`);
  };

  return (
    <div className={classes.root}>
      <MapView>
        <MapOverlay />
        <MapGeofence />
        <MapRoutePath positions={positions} />
        <MapRoutePoints positions={positions} onClick={onPointClick} showSpeedControl />
        {index < positions.length && (
          <MapPositions
            positions={[positions[index]]}
            onMarkerClick={onMarkerClick}
            titleField="fixTime"
            directionIcon="direction-replay"
          />
        )}
      </MapView>
      <MapScale />
      <MapCamera positions={positions} />
      <div className={classes.sidebar}>
        <Paper elevation={3} square>
          <Toolbar>
            <IconButton edge="start" sx={{ mr: 2 }} onClick={() => navigate(-1)}>
              <BackIcon />
            </IconButton>
            <Typography variant="h6" className={classes.title}>
              {t('reportReplay')}
            </Typography>
            {loaded && (
              <>
                <IconButton onClick={handleDownload}>
                  <DownloadIcon />
                </IconButton>
                <IconButton edge="end" onClick={() => setFilterOpen((open) => !open)}>
                  <TuneIcon />
                </IconButton>
              </>
            )}
          </Toolbar>
        </Paper>
        <Paper className={classes.content} square>
          {loaded && !filterOpen && (
            <>
              <Typography variant="subtitle1" align="center">
                {deviceName}
              </Typography>
              <Slider
                className={classes.slider}
                max={positions.length - 1}
                step={1}
                value={index}
                onChange={(_, index) => setIndex(index)}
              />
              <div className={classes.controls}>
                <Typography variant="caption">{`${index + 1}/${positions.length}`}</Typography>
                <IconButton
                  onClick={() => setIndex((index) => index - 1)}
                  disabled={playing || index <= 0}
                >
                  <FastRewindIcon />
                </IconButton>
                <IconButton
                  onClick={() => setPlaying(!playing)}
                  disabled={index >= positions.length - 1}
                >
                  {playing ? <PauseIcon /> : <PlayArrowIcon />}
                </IconButton>
                <IconButton
                  onClick={() => setIndex((index) => index + 1)}
                  disabled={playing || index >= positions.length - 1}
                >
                  <FastForwardIcon />
                </IconButton>
                <FormControl variant="standard" sx={{ minWidth: 60, mx: 1 }}>
                  <Select
                    value={speed}
                    onChange={(e) => setSpeed(Number(e.target.value))}
                    size="small"
                    disableUnderline
                    sx={{ fontSize: '0.875rem' }}
                  >
                    <MenuItem value={1}>1x</MenuItem>
                    <MenuItem value={2}>2x</MenuItem>
                    <MenuItem value={5}>5x</MenuItem>
                    <MenuItem value={10}>10x</MenuItem>
                    <MenuItem value={20}>20x</MenuItem>
                    <MenuItem value={50}>50x</MenuItem>
                    <MenuItem value={100}>100x</MenuItem>
                  </Select>
                </FormControl>
                <Typography variant="caption">
                  {formatTime(positions[index].fixTime, 'seconds')}
                </Typography>
              </div>
            </>
          )}
          <div style={{ display: loaded && !filterOpen ? 'none' : 'block' }}>
            <ReportFilter onShow={onShow} deviceType="single" loading={loading} />
          </div>
        </Paper>
        {loaded && !filterOpen && (
          <Accordion sx={{ mt: 1 }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Typography variant="subtitle2" sx={{ fontWeight: 'bold' }}>
                {`Paradas Detectadas (${stops.length})`}
              </Typography>
            </AccordionSummary>
            <AccordionDetails sx={{ p: 0, maxHeight: '250px', overflowY: 'auto' }}>
              <List dense disablePadding>
                {stops.map((stop, i) => (
                  <ListItemButton
                    key={i}
                    onClick={() => handleStopClick(stop)}
                    sx={{
                      borderBottom: '1px solid #eee',
                      py: 1,
                      px: 2,
                      '&:hover': {
                        backgroundColor: '#f5f5f5',
                      },
                    }}
                  >
                    <ListItemText
                      primary={
                        <Typography variant="subtitle2" sx={{ fontWeight: 'bold', color: '#e5004f' }}>
                          {`Parada ${i + 1}: ${formatDuration(stop.duration)}`}
                        </Typography>
                      }
                      secondary={
                        <span style={{ display: 'block', marginTop: '4px' }}>
                          <Typography variant="caption" display="block" color="textPrimary">
                            {`Permanencia: ${formatTimeOnly(stop.startTime)} - ${formatTimeOnly(stop.endTime)}`}
                          </Typography>
                          {stop.startBattery !== null && (
                            <Typography variant="caption" display="block" sx={{ fontWeight: 'bold', color: '#2e7d32', mt: 0.5 }}>
                              {`Batería: ${stop.startBattery}% ➔ ${stop.endBattery}%`}
                            </Typography>
                          )}
                          <Typography variant="caption" display="block" color="textSecondary" sx={{ mt: 0.5, fontStyle: 'italic' }}>
                            {`Dirección: ${stop.address}`}
                          </Typography>
                        </span>
                      }
                    />
                  </ListItemButton>
                ))}
                {stops.length === 0 && (
                  <Typography variant="body2" sx={{ p: 2, textAlign: 'center' }} color="textSecondary">
                    No se detectaron paradas en esta ruta.
                  </Typography>
                )}
              </List>
            </AccordionDetails>
          </Accordion>
        )}
      </div>
      {showCard && index < positions.length && (
        <StatusCard
          deviceId={selectedDeviceId}
          position={positions[index]}
          onClose={() => setShowCard(false)}
          disableActions
        />
      )}
    </div>
  );
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

const detectStops = (positions, distanceThreshold = 50, timeThresholdMs = 5 * 60 * 1000) => {
  const stops = [];
  if (!positions || positions.length < 2) return stops;

  const firstPos = positions[0];
  const cosLat = Math.cos(firstPos.latitude * Math.PI / 180);
  const kY = 111320;
  const kX = 111320 * cosLat;
  const distanceThresholdSq = distanceThreshold * distanceThreshold;

  let i = 0;
  const maxConsecutiveOutliers = 3;
  
  while (i < positions.length) {
    let j = i + 1;
    let stopEndIndex = i;
    
    let sumLat = positions[i].latitude;
    let sumLon = positions[i].longitude;
    let count = 1;
    
    let consecutiveOutliers = 0;
    
    while (j < positions.length) {
      const pCurrent = positions[j];
      const centroidLat = sumLat / count;
      const centroidLon = sumLon / count;
      
      const dy = (pCurrent.latitude - centroidLat) * kY;
      const dx = (pCurrent.longitude - centroidLon) * kX;
      const distSq = dx * dx + dy * dy;
      
      if (distSq < distanceThresholdSq) {
        sumLat += pCurrent.latitude;
        sumLon += pCurrent.longitude;
        count++;
        stopEndIndex = j;
        consecutiveOutliers = 0;
        j++;
      } else {
        consecutiveOutliers++;
        if (consecutiveOutliers >= maxConsecutiveOutliers) {
          break;
        }
        j++;
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

export default ReplayPage;
