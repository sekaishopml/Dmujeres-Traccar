package handler

import (
	"context"
	"encoding/json"
	"log"
	"strconv"
	"time"

	"dmujeres-traccar/internal/ws"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type IngestHandler struct {
	DB  *pgxpool.Pool
	Hub *ws.Hub
}

func NewIngestHandler(db *pgxpool.Pool, hub *ws.Hub) *IngestHandler {
	return &IngestHandler{DB: db, Hub: hub}
}

func (h *IngestHandler) HandleOsmAnd(c *fiber.Ctx) error {
	id := c.Query("id")
	latStr := c.Query("lat")
	lonStr := c.Query("lon")
	timestampStr := c.Query("timestamp")
	speedStr := c.Query("speed")
	bearingStr := c.Query("hdg")
	battStr := c.Query("batt")

	if id == "" || latStr == "" || lonStr == "" {
		return c.Status(fiber.StatusBadRequest).SendString("Bad Request: missing device id or coordinates")
	}

	lat, _ := strconv.ParseFloat(latStr, 64)
	lon, _ := strconv.ParseFloat(lonStr, 64)
	speed, _ := strconv.ParseFloat(speedStr, 64)
	bearing, _ := strconv.ParseFloat(bearingStr, 64)

	var deviceTime time.Time
	if timestampStr != "" {
		sec, err := strconv.ParseInt(timestampStr, 10, 64)
		if err == nil {
			deviceTime = time.Unix(sec, 0)
		} else {
			deviceTime = time.Now()
		}
	} else {
		deviceTime = time.Now()
	}

	var deviceDBID int64
	err := h.DB.QueryRow(context.Background(), "SELECT id FROM tc_devices WHERE uniqueid = $1", id).Scan(&deviceDBID)
	if err != nil {
		log.Printf("Device %s not found in tc_devices, registering default", id)
		err = h.DB.QueryRow(context.Background(), 
			"INSERT INTO tc_devices (name, uniqueid, lastupdate, attributes) VALUES ($1, $1, NOW(), '{}') RETURNING id", 
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
