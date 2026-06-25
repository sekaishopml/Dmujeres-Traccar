package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"strconv"
	"time"

	"dmujeres-traccar/internal/ws"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type IngestPayload struct {
	DeviceID string `json:"device_id"`
	Location struct {
		Timestamp string   `json:"timestamp"`
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
		Altitude  *float64 `json:"altitude"`
		Speed     *float64 `json:"speed"`
		Bearing   *float64 `json:"bearing"`
		Heading   *float64 `json:"heading"`
		Accuracy  *float64 `json:"accuracy"`
		Odometer  *float64 `json:"odometer"`
		Coords    *struct {
			Latitude  *float64 `json:"latitude"`
			Longitude *float64 `json:"longitude"`
			Altitude  *float64 `json:"altitude"`
			Speed     *float64 `json:"speed"`
			Bearing   *float64 `json:"bearing"`
			Heading   *float64 `json:"heading"`
			Accuracy  *float64 `json:"accuracy"`
		} `json:"coords"`
		Battery *struct {
			Level *float64 `json:"level"`
		} `json:"battery"`
		Activity *struct {
			Type string `json:"type"`
		} `json:"activity"`
	} `json:"location"`
}

type IngestHandler struct {
	DB  *pgxpool.Pool
	Hub *ws.Hub
}

func NewIngestHandler(db *pgxpool.Pool, hub *ws.Hub) *IngestHandler {
	return &IngestHandler{DB: db, Hub: hub}
}

func distance(lat1, lon1, lat2, lon2 float64) float64 {
	const R = 6371000 // Earth radius in meters
	phi1 := lat1 * math.Pi / 180
	phi2 := lat2 * math.Pi / 180
	deltaPhi := (lat2 - lat1) * math.Pi / 180
	deltaLambda := (lon2 - lon1) * math.Pi / 180

	a := math.Sin(deltaPhi/2)*math.Sin(deltaPhi/2) +
		math.Cos(phi1)*math.Cos(phi2)*
			math.Sin(deltaLambda/2)*math.Sin(deltaLambda/2)
	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))

	return R * c
}

func (h *IngestHandler) HandleOsmAnd(c *fiber.Ctx) error {
	// Log the incoming request for debugging
	log.Printf("GPS Ping Ingress: Method=%s, Path=%s, Content-Type=%s, IP=%s, Query=%s, Body=%s",
		c.Method(), c.Path(), c.Get("Content-Type"), c.IP(), c.Context().QueryArgs().String(), string(c.Body()))

	id := c.Query("id")
	latStr := c.Query("lat")
	lonStr := c.Query("lon")
	timestampStr := c.Query("timestamp")
	speedStr := c.Query("speed")
	bearingStr := c.Query("hdg")
	if bearingStr == "" {
		bearingStr = c.Query("bearing")
	}
	battStr := c.Query("batt")
	altStr := c.Query("altitude")
	if altStr == "" {
		altStr = c.Query("alt")
	}
	accStr := c.Query("accuracy")
	if accStr == "" {
		accStr = c.Query("acc")
	}
	odoStr := c.Query("odometer")

	// If missing query params, try Form parameters (POST urlencoded/form-data)
	if id == "" {
		id = c.FormValue("id")
	}
	if latStr == "" {
		latStr = c.FormValue("lat")
	}
	if lonStr == "" {
		lonStr = c.FormValue("lon")
	}
	if timestampStr == "" {
		timestampStr = c.FormValue("timestamp")
	}
	if speedStr == "" {
		speedStr = c.FormValue("speed")
	}
	if bearingStr == "" {
		bearingStr = c.FormValue("hdg")
		if bearingStr == "" {
			bearingStr = c.FormValue("bearing")
		}
	}
	if battStr == "" {
		battStr = c.FormValue("batt")
	}
	if altStr == "" {
		altStr = c.FormValue("altitude")
		if altStr == "" {
			altStr = c.FormValue("alt")
		}
	}
	if accStr == "" {
		accStr = c.FormValue("accuracy")
		if accStr == "" {
			accStr = c.FormValue("acc")
		}
	}
	if odoStr == "" {
		odoStr = c.FormValue("odometer")
	}

	var lat float64
	var lon float64
	var speed float64
	var bearing float64
	var altitude float64
	var accuracy float64
	var odometer float64
	var hasOdometer bool
	var hasValidCoords bool
	var hasID bool

	attributes := map[string]interface{}{}

	if id != "" && latStr != "" && lonStr != "" {
		hasID = true
		lat, _ = strconv.ParseFloat(latStr, 64)
		lon, _ = strconv.ParseFloat(lonStr, 64)
		
		s, _ := strconv.ParseFloat(speedStr, 64)
		if s < 0 {
			speed = 0.0
		} else {
			speed = s / 1.852 // Convert km/h to knots
		}

		b, _ := strconv.ParseFloat(bearingStr, 64)
		if b < 0 {
			bearing = 0.0
		} else {
			bearing = b
		}

		hasValidCoords = true

		if altStr != "" {
			altitude, _ = strconv.ParseFloat(altStr, 64)
		}
		if accStr != "" {
			accuracy, _ = strconv.ParseFloat(accStr, 64)
		}
		if odoStr != "" {
			odometer, _ = strconv.ParseFloat(odoStr, 64)
			hasOdometer = true
		}
	}

	// Try nested JSON format if we still lack device_id or coords
	if !hasValidCoords || !hasID {
		var jsonPayload IngestPayload
		if err := json.Unmarshal(c.Body(), &jsonPayload); err == nil {
			if jsonPayload.DeviceID != "" {
				id = jsonPayload.DeviceID
				hasID = true
			}

			if jsonPayload.Location.Latitude != nil && jsonPayload.Location.Longitude != nil {
				lat = *jsonPayload.Location.Latitude
				lon = *jsonPayload.Location.Longitude
				hasValidCoords = true
			} else if jsonPayload.Location.Coords != nil && jsonPayload.Location.Coords.Latitude != nil && jsonPayload.Location.Coords.Longitude != nil {
				lat = *jsonPayload.Location.Coords.Latitude
				lon = *jsonPayload.Location.Coords.Longitude
				hasValidCoords = true
			}

			if hasValidCoords {
				if jsonPayload.Location.Timestamp != "" {
					timestampStr = jsonPayload.Location.Timestamp
				}

				var rawSpeed float64
				if jsonPayload.Location.Speed != nil {
					rawSpeed = *jsonPayload.Location.Speed
				} else if jsonPayload.Location.Coords != nil && jsonPayload.Location.Coords.Speed != nil {
					rawSpeed = *jsonPayload.Location.Coords.Speed
				}
				if rawSpeed < 0 {
					speed = 0.0
				} else {
					speed = rawSpeed * 1.94384 // Convert m/s to knots
				}

				var rawBearing float64
				if jsonPayload.Location.Bearing != nil {
					rawBearing = *jsonPayload.Location.Bearing
				} else if jsonPayload.Location.Heading != nil {
					rawBearing = *jsonPayload.Location.Heading
				} else if jsonPayload.Location.Coords != nil {
					if jsonPayload.Location.Coords.Bearing != nil {
						rawBearing = *jsonPayload.Location.Coords.Bearing
					} else if jsonPayload.Location.Coords.Heading != nil {
						rawBearing = *jsonPayload.Location.Coords.Heading
					}
				}
				if rawBearing < 0 {
					bearing = 0.0
				} else {
					bearing = rawBearing
				}

				if jsonPayload.Location.Battery != nil && jsonPayload.Location.Battery.Level != nil {
					val := *jsonPayload.Location.Battery.Level
					if val <= 1.0 {
						val = val * 100
					}
					battStr = strconv.FormatFloat(val, 'f', -1, 64)
				}

				if jsonPayload.Location.Odometer != nil {
					odometer = *jsonPayload.Location.Odometer
					hasOdometer = true
				}
				if jsonPayload.Location.Altitude != nil {
					altitude = *jsonPayload.Location.Altitude
				} else if jsonPayload.Location.Coords != nil && jsonPayload.Location.Coords.Altitude != nil {
					altitude = *jsonPayload.Location.Coords.Altitude
				}
				if jsonPayload.Location.Accuracy != nil {
					accuracy = *jsonPayload.Location.Accuracy
				} else if jsonPayload.Location.Coords != nil && jsonPayload.Location.Coords.Accuracy != nil {
					accuracy = *jsonPayload.Location.Coords.Accuracy
				}
				if jsonPayload.Location.Activity != nil && jsonPayload.Location.Activity.Type != "" {
					attributes["activity"] = jsonPayload.Location.Activity.Type
				}
			}
		}
	}

	// Try flat JSON format if we still lack device_id or coords
	if !hasValidCoords || !hasID {
		var flatPayload map[string]interface{}
		if err := json.Unmarshal(c.Body(), &flatPayload); err == nil {
			if val, ok := flatPayload["id"]; ok {
				id = fmt.Sprintf("%v", val)
				hasID = true
			} else if val, ok := flatPayload["deviceid"]; ok {
				id = fmt.Sprintf("%v", val)
				hasID = true
			} else if val, ok := flatPayload["deviceId"]; ok {
				id = fmt.Sprintf("%v", val)
				hasID = true
			} else if val, ok := flatPayload["device_id"]; ok {
				id = fmt.Sprintf("%v", val)
				hasID = true
			}

			var flatLat, flatLon bool
			if lVal, ok := flatPayload["lat"]; ok {
				lat, _ = strconv.ParseFloat(fmt.Sprintf("%v", lVal), 64)
				flatLat = true
			} else if lVal, ok := flatPayload["latitude"]; ok {
				lat, _ = strconv.ParseFloat(fmt.Sprintf("%v", lVal), 64)
				flatLat = true
			}

			if lVal, ok := flatPayload["lon"]; ok {
				lon, _ = strconv.ParseFloat(fmt.Sprintf("%v", lVal), 64)
				flatLon = true
			} else if lVal, ok := flatPayload["lng"]; ok {
				lon, _ = strconv.ParseFloat(fmt.Sprintf("%v", lVal), 64)
				flatLon = true
			} else if lVal, ok := flatPayload["longitude"]; ok {
				lon, _ = strconv.ParseFloat(fmt.Sprintf("%v", lVal), 64)
				flatLon = true
			}

			if flatLat && flatLon {
				hasValidCoords = true
			}

			if hasValidCoords {
				if sVal, ok := flatPayload["speed"]; ok {
					s, _ := strconv.ParseFloat(fmt.Sprintf("%v", sVal), 64)
					if s < 0 {
						speed = 0.0
					} else {
						speed = s * 1.94384 // Convert m/s to knots
					}
				}
				var rawBearing float64
				if hVal, ok := flatPayload["hdg"]; ok {
					rawBearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				} else if hVal, ok := flatPayload["bearing"]; ok {
					rawBearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				} else if hVal, ok := flatPayload["heading"]; ok {
					rawBearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				}
				if rawBearing < 0 {
					bearing = 0.0
				} else {
					bearing = rawBearing
				}
				if bVal, ok := flatPayload["batt"]; ok {
					battStr = fmt.Sprintf("%v", bVal)
				} else if bVal, ok := flatPayload["battery"]; ok {
					battStr = fmt.Sprintf("%v", bVal)
				}
				if tVal, ok := flatPayload["timestamp"]; ok {
					timestampStr = fmt.Sprintf("%v", tVal)
				}
				if oVal, ok := flatPayload["odometer"]; ok {
					odometer, _ = strconv.ParseFloat(fmt.Sprintf("%v", oVal), 64)
					hasOdometer = true
				}
				if aVal, ok := flatPayload["altitude"]; ok {
					altitude, _ = strconv.ParseFloat(fmt.Sprintf("%v", aVal), 64)
				} else if aVal, ok := flatPayload["alt"]; ok {
					altitude, _ = strconv.ParseFloat(fmt.Sprintf("%v", aVal), 64)
				}
				if aVal, ok := flatPayload["accuracy"]; ok {
					accuracy, _ = strconv.ParseFloat(fmt.Sprintf("%v", aVal), 64)
				} else if aVal, ok := flatPayload["acc"]; ok {
					accuracy, _ = strconv.ParseFloat(fmt.Sprintf("%v", aVal), 64)
				}
			}
		}
	}

	if id == "" || !hasValidCoords {
		log.Printf("GPS Ping rejected: missing device id or coordinates. id=%s, coords_ok=%t", id, hasValidCoords)
		return c.Status(fiber.StatusBadRequest).SendString("Bad Request: missing device id or coordinates")
	}

	var deviceTime time.Time
	if timestampStr != "" {
		val, err := strconv.ParseInt(timestampStr, 10, 64)
		if err == nil {
			if val > 9999999999 { // Milliseconds
				deviceTime = time.UnixMilli(val)
			} else { // Seconds
				deviceTime = time.Unix(val, 0)
			}
		} else {
			// Try parsing as ISO 8601 / RFC3339
			tParsed, err := time.Parse(time.RFC3339, timestampStr)
			if err == nil {
				deviceTime = tParsed
			} else {
				// Try other common formats
				tParsed2, err := time.Parse("2006-01-02T15:04:05.000Z", timestampStr)
				if err == nil {
					deviceTime = tParsed2
				} else {
					tParsed3, err := time.Parse("2006-01-02 15:04:05", timestampStr)
					if err == nil {
						deviceTime = tParsed3
					} else {
						deviceTime = time.Now()
					}
				}
			}
		}
	} else {
		deviceTime = time.Now()
	}

	var deviceDBID int64
	err := h.DB.QueryRow(context.Background(), "SELECT id FROM tc_devices WHERE uniqueid = $1", id).Scan(&deviceDBID)
	if err != nil {
		log.Printf("Device %s not found in tc_devices, registering default", id)
		err = h.DB.QueryRow(context.Background(),
			"INSERT INTO tc_devices (name, uniqueid, attributes) VALUES ($1, $1, '{}') RETURNING id",
			id).Scan(&deviceDBID)
		if err != nil {
			return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
		}
	}

	if battStr != "" {
		batt, _ := strconv.ParseFloat(battStr, 64)
		attributes["batteryLevel"] = batt
	}

	// Calculate segment distance and totalDistance
	var distanceVal float64
	var totalDistanceVal float64

	var prevLat, prevLon float64
	var prevAttrsBytes []byte
	var hasPrev bool

	err = h.DB.QueryRow(context.Background(),
		`SELECT latitude, longitude, COALESCE(attributes, '{}')
		 FROM tc_positions
		 WHERE id = (SELECT positionid FROM tc_devices WHERE id = $1)`, deviceDBID).Scan(&prevLat, &prevLon, &prevAttrsBytes)
	if err == nil {
		hasPrev = true
	}

	if hasPrev {
		distanceVal = distance(prevLat, prevLon, lat, lon)

		var prevAttrs map[string]interface{}
		if json.Unmarshal(prevAttrsBytes, &prevAttrs) == nil {
			if prevTotalDist, ok := prevAttrs["totalDistance"].(float64); ok {
				totalDistanceVal = prevTotalDist + distanceVal
			} else {
				totalDistanceVal = distanceVal
			}
		} else {
			totalDistanceVal = distanceVal
		}
	} else {
		distanceVal = 0.0
		totalDistanceVal = 0.0
	}

	if hasOdometer {
		totalDistanceVal = odometer
	}

	attributes["distance"] = distanceVal
	attributes["totalDistance"] = totalDistanceVal

	attributesBytes, _ := json.Marshal(attributes)

	var positionID int64
	insertQuery := `
		INSERT INTO tc_positions (deviceid, protocol, servertime, devicetime, fixtime, valid, latitude, longitude, speed, course, altitude, address, accuracy, network, attributes)
		VALUES ($1, 'osmand', NOW(), $2, $2, true, $3, $4, $5, $6, $7, '', $8, '', $9)
		RETURNING id`
	err = h.DB.QueryRow(context.Background(), insertQuery, deviceDBID, deviceTime, lat, lon, speed, bearing, altitude, accuracy, string(attributesBytes)).Scan(&positionID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	updateQuery := `
		UPDATE tc_devices d
		SET positionid = $1, lastupdate = NOW()
		WHERE d.id = $2 AND (
			d.positionid IS NULL OR 
			$3 >= (SELECT devicetime FROM tc_positions WHERE id = d.positionid)
		)`
	_, err = h.DB.Exec(context.Background(), updateQuery, positionID, deviceDBID, deviceTime)
	if err != nil {
		log.Printf("Failed to update device lastupdate: %v", err)
	}

	var devName string
	var devUniqueId string
	var devLastUpdate time.Time
	var devAttrsBytes []byte
	err = h.DB.QueryRow(context.Background(),
		`SELECT name, uniqueid, COALESCE(lastupdate, NOW()), COALESCE(attributes, '{}')
		 FROM tc_devices WHERE id = $1`, deviceDBID).Scan(&devName, &devUniqueId, &devLastUpdate, &devAttrsBytes)

	var devAttrs map[string]interface{}
	if err == nil {
		_ = json.Unmarshal(devAttrsBytes, &devAttrs)
	} else {
		devAttrs = make(map[string]interface{})
	}

	// WS Broadcast to connected web clients in real-time
	wsMessage := map[string]interface{}{
		"positions": []interface{}{
			map[string]interface{}{
				"id":         positionID,
				"deviceId":   deviceDBID,
				"protocol":   "osmand",
				"serverTime": time.Now().Format(time.RFC3339),
				"deviceTime": deviceTime.Format(time.RFC3339),
				"fixTime":    deviceTime.Format(time.RFC3339),
				"valid":      true,
				"latitude":   lat,
				"longitude":  lon,
				"speed":      speed,
				"course":     bearing,
				"altitude":   altitude,
				"accuracy":   accuracy,
				"attributes": attributes,
			},
		},
		"devices": []interface{}{
			map[string]interface{}{
				"id":         deviceDBID,
				"name":       devName,
				"uniqueId":   devUniqueId,
				"status":     "online",
				"lastUpdate": devLastUpdate.Format(time.RFC3339),
				"positionId": positionID,
				"attributes": devAttrs,
			},
		},
	}
	wsBytes, err := json.Marshal(wsMessage)
	if err == nil {
		h.Hub.Broadcast <- wsBytes
	}

	log.Printf("GPS Ping saved & broadcasted. PositionID=%d for Device=%s (%f, %f) Dist=%.1fm Total=%.1fm", positionID, id, lat, lon, distanceVal, totalDistanceVal)
	return c.SendString("OK")
}
