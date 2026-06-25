package handler

import (
        "context"
        "strconv"
        "time"

        "dmujeres-traccar/internal/auth"
        "dmujeres-traccar/internal/model"
        "github.com/gofiber/fiber/v2"
        "github.com/jackc/pgx/v5"
        "github.com/jackc/pgx/v5/pgxpool"
)

type ReportHandler struct {
        DB *pgxpool.Pool
}

func NewReportHandler(db *pgxpool.Pool) *ReportHandler {
        return &ReportHandler{DB: db}
}

func (h *ReportHandler) GetRouteReport(c *fiber.Ctx) error {
        _, err := auth.GetUserFromCookie(c)
        if err != nil {
                return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
        }

        deviceIDStr := c.Query("deviceId")
        fromStr := c.Query("from")
        toStr := c.Query("to")
        toleranceStr := c.Query("tolerance")

        if deviceIDStr == "" || fromStr == "" || toStr == "" {
                return c.Status(fiber.StatusBadRequest).SendString("Missing deviceId, from, or to parameters")
        }

        deviceID, _ := strconv.ParseInt(deviceIDStr, 10, 64)
        fromTime, _ := time.Parse(time.RFC3339, fromStr)
        toTime, _ := time.Parse(time.RFC3339, toStr)
        tolerance, _ := strconv.ParseFloat(toleranceStr, 64)

        var rows pgx.Rows

        if tolerance > 0 {
                rows, err = h.DB.Query(context.Background(),
                        `WITH points AS (
                          SELECT id, deviceid, protocol, servertime, devicetime, latitude, longitude, speed, course, altitude, COALESCE(attributes, '{}') as attributes,
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
                        SELECT DISTINCT ON (p.id) p.id, p.deviceid, p.protocol, p.servertime, p.devicetime, p.latitude, p.longitude, p.speed, p.course, p.altitude, p.attributes
                        FROM points p
                        JOIN dumped d ON p.geom = d.geom
                        ORDER BY p.id, p.devicetime ASC`, deviceID, fromTime, toTime, tolerance)
        } else {
                rows, err = h.DB.Query(context.Background(),
                        `SELECT id, deviceid, protocol, servertime, devicetime, latitude, longitude, speed, course, altitude, COALESCE(attributes, '{}')
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
                err := rows.Scan(&p.ID, &p.DeviceID, &p.Protocol, &p.ServerTime, &p.DeviceTime, &p.Latitude, &p.Longitude, &p.Speed, &p.Course, &p.Altitude, &p.Attributes)
                if err == nil {
                        positions = append(positions, p)
                }
        }

        return c.JSON(positions)
}
