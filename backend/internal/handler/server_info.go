package handler

import (
	"github.com/gofiber/fiber/v2"
)

func GetServerInfo(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"id":               1,
		"registration":     false,
		"readonly":         false,
		"deviceReadonly":   false,
		"map":              "googleRoads",
		"latitude":         -2.1894,
		"longitude":        -79.8891,
		"zoom":             12,
		"twelveHourFormat": false,
		"version":          "6.2",
		"forceSettings":    false,
		"coordinateFormat": "",
	})
}
