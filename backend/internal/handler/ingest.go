package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strconv"
	"time"

	"dmujeres-traccar/internal/ws"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type IngestPayload struct {
	DeviceID string `json:"device_id"`
	Location struct {
		Timestamp string `json:"timestamp"`
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
		Altitude  *float64 `json:"altitude"`
		Speed     *float64 `json:"speed"`
		Bearing   *float64 `json:"bearing"`
		Heading   *float64 `json:"heading"`
		Accuracy  *float64 `json:"accuracy"`
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
	} `json:"location"`
}

type IngestHandler struct {
	DB  *pgxpool.Pool
	Hub *ws.Hub
}

func NewIngestHandler(db *pgxpool.Pool, hub *ws.Hub) *IngestHandler {
	return &IngestHandler{DB: db, Hub: hub}
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

	var lat float64
	var lon float64
	var speed float64
	var bearing float64
	var hasValidCoords bool
	var hasID bool

	if id != "" && latStr != "" && lonStr != "" {
		hasID = true
		lat, _ = strconv.ParseFloat(latStr, 64)
		lon, _ = strconv.ParseFloat(lonStr, 64)
		speed, _ = strconv.ParseFloat(speedStr, 64)
		bearing, _ = strconv.ParseFloat(bearingStr, 64)
		hasValidCoords = true
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

				if jsonPayload.Location.Speed != nil {
					speed = *jsonPayload.Location.Speed
				} else if jsonPayload.Location.Coords != nil && jsonPayload.Location.Coords.Speed != nil {
					speed = *jsonPayload.Location.Coords.Speed
				}

				if jsonPayload.Location.Bearing != nil {
					bearing = *jsonPayload.Location.Bearing
				} else if jsonPayload.Location.Heading != nil {
					bearing = *jsonPayload.Location.Heading
				} else if jsonPayload.Location.Coords != nil {
					if jsonPayload.Location.Coords.Bearing != nil {
						bearing = *jsonPayload.Location.Coords.Bearing
					} else if jsonPayload.Location.Coords.Heading != nil {
						bearing = *jsonPayload.Location.Coords.Heading
					}
				}

				if jsonPayload.Location.Battery != nil && jsonPayload.Location.Battery.Level != nil {
					val := *jsonPayload.Location.Battery.Level
					if val <= 1.0 {
						val = val * 100
					}
					battStr = strconv.FormatFloat(val, 'f', -1, 64)
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
					speed, _ = strconv.ParseFloat(fmt.Sprintf("%v", sVal), 64)
				}
				if hVal, ok := flatPayload["hdg"]; ok {
					bearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				} else if hVal, ok := flatPayload["bearing"]; ok {
					bearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				} else if hVal, ok := flatPayload["heading"]; ok {
					bearing, _ = strconv.ParseFloat(fmt.Sprintf("%v", hVal), 64)
				}
				if bVal, ok := flatPayload["batt"]; ok {
					battStr = fmt.Sprintf("%v", bVal)
				} else if bVal, ok := flatPayload["battery"]; ok {
					battStr = fmt.Sprintf("%v", bVal)
				}
				if tVal, ok := flatPayload["timestamp"]; ok {
					timestampStr = fmt.Sprintf("%v", tVal)
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

	attributes := map[string]interface{}{}
	if battStr != "" {
		batt, _ := strconv.ParseFloat(battStr, 64)
		attributes["batteryLevel"] = batt
	}
	attributesBytes, _ := json.Marshal(attributes)

	var positionID int64
	insertQuery := `
		INSERT INTO tc_positions (deviceid, protocol, servertime, devicetime, latitude, longitude, speed, course, altitude, attributes)
		VALUES ($1, 'osmand', NOW(), $2, $3, $4, $5, $6, 0.0, $7)
		RETURNING id`
	err = h.DB.QueryRow(context.Background(), insertQuery, deviceDBID, deviceTime, lat, lon, speed, bearing, string(attributesBytes)).Scan(&positionID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	updateQuery := `
		UPDATE tc_devices
		SET positionid = $1, lastupdate = NOW()
		WHERE id = $2`
	_, err = h.DB.Exec(context.Background(), updateQuery, positionID, deviceDBID)
	if err != nil {
		log.Printf("Failed to update device lastupdate: %v", err)
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
				"fixTime":    time.Now().Format(time.RFC3339),
				"valid":      true,
				"latitude":   lat,
				"longitude":  lon,
				"speed":      speed,
				"course":     bearing,
				"altitude":   0.0,
				"attributes": attributes,
			},
		},
	}
	wsBytes, err := json.Marshal(wsMessage)
	if err == nil {
		h.Hub.Broadcast <- wsBytes
	}

	log.Printf("GPS Ping saved & broadcasted. PositionID=%d for Device=%s (%f, %f)", positionID, id, lat, lon)
	return c.SendString("OK")
}
