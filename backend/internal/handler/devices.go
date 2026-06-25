package handler

import (
	"context"
	"strconv"

	"dmujeres-traccar/internal/auth"
	"dmujeres-traccar/internal/model"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type DeviceHandler struct {
	DB *pgxpool.Pool
}

func NewDeviceHandler(db *pgxpool.Pool) *DeviceHandler {
	return &DeviceHandler{DB: db}
}

func (h *DeviceHandler) GetDevices(c *fiber.Ctx) error {
	userID, err := auth.GetUserFromCookie(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
	}

	rows, err := h.DB.Query(context.Background(),
		`SELECT d.id, d.name, d.uniqueid, d.status, COALESCE(d.lastupdate, '0001-01-01 00:00:00'), COALESCE(d.positionid, 0), COALESCE(d.attributes, '{}')
		 FROM tc_devices d 
		 JOIN tc_user_device ud ON d.id = ud.deviceid 
		 WHERE ud.userid = $1`, userID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}
	defer rows.Close()

	devices := []model.Device{}
	for rows.Next() {
		var d model.Device
		err := rows.Scan(&d.ID, &d.Name, &d.UniqueID, &d.Status, &d.LastUpdate, &d.PositionID, &d.Attributes)
		if err == nil {
			devices = append(devices, d)
		}
	}

	return c.JSON(devices)
}

func (h *DeviceHandler) CreateDevice(c *fiber.Ctx) error {
	userID, err := auth.GetUserFromCookie(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
	}

	var d model.Device
	if err := c.BodyParser(&d); err != nil {
		return c.Status(fiber.StatusBadRequest).SendString(err.Error())
	}

	if d.Name == "" || d.UniqueID == "" {
		return c.Status(fiber.StatusBadRequest).SendString("Missing name or uniqueId")
	}

	tx, err := h.DB.Begin(context.Background())
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}
	defer tx.Rollback(context.Background())

	var deviceID int64
	err = tx.QueryRow(context.Background(),
		"INSERT INTO tc_devices (name, uniqueid, status, lastupdate, attributes) VALUES ($1, $2, 'offline', NOW(), $3) RETURNING id",
		d.Name, d.UniqueID, d.Attributes).Scan(&deviceID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	_, err = tx.Exec(context.Background(),
		"INSERT INTO tc_user_device (userid, deviceid) VALUES ($1, $2)",
		userID, deviceID)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	err = tx.Commit(context.Background())
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	d.ID = deviceID
	d.Status = "offline"
	return c.JSON(d)
}

func (h *DeviceHandler) DeleteDevice(c *fiber.Ctx) error {
	_, err := auth.GetUserFromCookie(c)
	if err != nil {
		return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
	}

	idStr := c.Params("id")
	id, _ := strconv.ParseInt(idStr, 10, 64)

	_, err = h.DB.Exec(context.Background(), "DELETE FROM tc_devices WHERE id = $1", id)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
	}

	return c.SendString("OK")
}
