package handler

import (
	"context"
	"encoding/json"
	"strconv"
	"time"

	"dmujeres-traccar/internal/auth"
	"dmujeres-traccar/internal/model"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PositionHandler struct {
	DB *pgxpool.Pool
}

func NewPositionHandler(db *pgxpool.Pool) *PositionHandler {
	return &PositionHandler{DB: db}
}

func (h *PositionHandler) GetPositions(c *fiber.Ctx) error {
	_, err := auth.GetUserFromCookie(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
	}

	deviceIDStr := c.Query("deviceId")
	fromStr := c.Query("from")
	toStr := c.Query("to")
	toleranceStr := c.Query("tolerance")

	if deviceIDStr != "" && fromStr != "" && toStr != "" {
		deviceID, _ := strconv.ParseInt(deviceIDStr, 10, 64)
		fromTime, _ := time.Parse(time.RFC3339, fromStr)
		toTime, _ := time.Parse(time.RFC3339, toStr)
		tolerance, _ := strconv.ParseFloat(toleranceStr, 64)

		var rows pgx.Rows

		if tolerance > 0 {
			rows, err = h.DB.Query(context.Background(),
				`WITH points AS (
				  SELECT id, deviceid, protocol, servertime, devicetime, COALESCE(fixtime, devicetime) as fixtime, COALESCE(valid, false) as valid, latitude, longitude, speed, course, altitude, COALESCE(address, '') as address, COALESCE(accuracy, 0.0) as accuracy, COALESCE(network, '') as network, COALESCE(attributes, '{}') as attributes,
						 ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) as geom
				  FROM tc_positions
				  WHERE deviceid = $1 AND devicetime >= $2 AND devicetime <= $3
				  ORDER BY devicetime ASC
				),
				line AS (
				  SELECT ST_SimplifyPreserveTopology(ST_MakeLine(geom), $4) as geom
				  FROM points
				),
				dumped AS (
				  SELECT (ST_DumpPoints(geom)).geom as geom
				  FROM line
				)
				SELECT DISTINCT ON (p.id) p.id, p.deviceid, p.protocol, p.servertime, p.devicetime, p.fixtime, p.valid, p.latitude, p.longitude, p.speed, p.course, p.altitude, p.address, p.accuracy, p.network, p.attributes
				FROM points p
				JOIN dumped d ON p.geom = d.geom
				ORDER BY p.id, p.devicetime ASC`, deviceID, fromTime, toTime, tolerance)
		} else {
			rows, err = h.DB.Query(context.Background(),
				`SELECT id, deviceid, protocol, servertime, devicetime, COALESCE(fixtime, devicetime) as fixtime, COALESCE(valid, false) as valid, latitude, longitude, speed, course, altitude, COALESCE(address, '') as address, COALESCE(accuracy, 0.0) as accuracy, COALESCE(network, '') as network, COALESCE(attributes, '{}') as attributes
				 FROM tc_positions
				 WHERE deviceid = $1 AND devicetime >= $2 AND devicetime <= $3
				 ORDER BY devicetime ASC`, deviceID, fromTime, toTime)
		}

		if err != nil {
			return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
		}
		defer rows.Close()

		positions := []model.Position{}
		for rows.Next() {
			var p model.Position
			var attrsBytes []byte
			err := rows.Scan(
				&p.ID, &p.DeviceID, &p.Protocol, &p.ServerTime, &p.DeviceTime, &p.FixTime, &p.Valid,
				&p.Latitude, &p.Longitude, &p.Speed, &p.Course, &p.Altitude,
				&p.Address, &p.Accuracy, &p.Network, &attrsBytes,
			)
			if err == nil {
				p.Attributes = make(map[string]interface{})
				if len(attrsBytes) > 0 {
					_ = json.Unmarshal(attrsBytes, &p.Attributes)
				}
				positions = append(positions, p)
			}
		}
		return c.JSON(positions)
	}

	userID, err := auth.GetUserFromCookie(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
	}

	rows, err := h.DB.Query(context.Background(),
		`SELECT p.id, p.deviceid, p.protocol, p.servertime, p.devicetime, COALESCE(p.fixtime, p.devicetime) as fixtime, COALESCE(p.valid, false) as valid, p.latitude, p.longitude, p.speed, p.course, p.altitude, COALESCE(p.address, '') as address, COALESCE(p.accuracy, 0.0) as accuracy, COALESCE(p.network, '') as network, COALESCE(p.attributes, '{}') as attributes
		 FROM tc_positions p
		 JOIN tc_devices d ON p.id = d.positionid
		 JOIN tc_user_device ud ON d.id = ud.deviceid
		 WHERE ud.userid = $1`, userID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}
	defer rows.Close()

	positions := []model.Position{}
	for rows.Next() {
		var p model.Position
		var attrsBytes []byte
		err := rows.Scan(
			&p.ID, &p.DeviceID, &p.Protocol, &p.ServerTime, &p.DeviceTime, &p.FixTime, &p.Valid,
			&p.Latitude, &p.Longitude, &p.Speed, &p.Course, &p.Altitude,
			&p.Address, &p.Accuracy, &p.Network, &attrsBytes,
		)
		if err == nil {
			p.Attributes = make(map[string]interface{})
			if len(attrsBytes) > 0 {
				_ = json.Unmarshal(attrsBytes, &p.Attributes)
			}
			positions = append(positions, p)
		}
	}

	return c.JSON(positions)
}
