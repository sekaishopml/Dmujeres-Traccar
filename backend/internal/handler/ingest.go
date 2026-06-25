package handler

import (
	"context"
	"encoding/json"
	"log"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type IngestHandler struct {
	DB *pgxpool.Pool
}

func NewIngestHandler(db *pgxpool.Pool) *IngestHandler {
	return &IngestHandler{DB: db}
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

	// Fetch device ID from unique ID
	var deviceDBID int64
	err := h.DB.QueryRow(context.Background(), "SELECT id FROM tc_devices WHERE uniqueid = $1", id).Scan(&deviceDBID)
	if err != nil {
		// Auto-register device for testing if it doesn't exist
		log.Printf("Device %s not found in tc_devices, registering default", id)
		err = h.DB.QueryRow(context.Background(), 
			"INSERT INTO tc_devices (name, uniqueid, status, lastupdate, attributes) VALUES ($1, $1, 'online', NOW(), '{}') RETURNING id", 
			id).Scan(&deviceDBID)
		if err != nil {
			return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
		}
	}

	// Attributes formatting
	attributes := map[string]interface{}{}
	if battStr != "" {
		batt, _ := strconv.ParseFloat(battStr, 64)
		attributes["batteryLevel"] = batt
	}
	attributesBytes, _ := json.Marshal(attributes)

	// Save position
	var positionID int64
	insertQuery := `
		INSERT INTO tc_positions (deviceid, protocol, servertime, devicetime, latitude, longitude, speed, course, altitude, attributes)
		VALUES ($1, 'osmand', NOW(), $2, $3, $4, $5, $6, 0.0, $7)
		RETURNING id`
	err = h.DB.QueryRow(context.Background(), insertQuery, deviceDBID, deviceTime, lat, lon, speed, bearing, string(attributesBytes)).Scan(&positionID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	// Update device status and position reference
	updateQuery := `
		UPDATE tc_devices
		SET positionid = $1, lastupdate = NOW(), status = 'online'
		WHERE id = $2`
	_, err = h.DB.Exec(context.Background(), updateQuery, positionID, deviceDBID)
	if err != nil {
		log.Printf("Failed to update device status: %v", err)
	}

	log.Printf("GPS Ping saved. PositionID=%d for Device=%s (%f, %f)", positionID, id, lat, lon)
	return c.SendString("OK")
}
