package main

import (
	"fmt"
	"log"

	"dmujeres-traccar/internal/config"
	"dmujeres-traccar/internal/db"
	"dmujeres-traccar/internal/handler"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
)

func main() {
	cfg := config.LoadConfig()

	databasePool, err := db.ConnectPostgres(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer databasePool.Close()

	app := fiber.New(fiber.Config{
		AppName: "Dmujeres-Traccar Go API",
	})

	app.Use(logger.New())

	ingestHandler := handler.NewIngestHandler(databasePool)
	sessionHandler := handler.NewSessionHandler(databasePool)
	deviceHandler := handler.NewDeviceHandler(databasePool)
	positionHandler := handler.NewPositionHandler(databasePool)

	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"status": "healthy",
			"app":    "Dmujeres-Traccar Backend",
		})
	})

	app.Get("/ingest", ingestHandler.HandleOsmAnd)
	app.Get("/", ingestHandler.HandleOsmAnd)

	api := app.Group("/api")

	api.Get("/session", sessionHandler.GetSession)
	api.Post("/session", sessionHandler.Login)
	api.Delete("/session", sessionHandler.Logout)

	api.Get("/server", handler.GetServerInfo)

	api.Get("/devices", deviceHandler.GetDevices)
	api.Post("/devices", deviceHandler.CreateDevice)
	api.Delete("/devices/:id", deviceHandler.DeleteDevice)

	api.Get("/positions", positionHandler.GetPositions)

	fmt.Printf("Starting Dmujeres-Traccar Go backend on port %s...\n", cfg.Port)
	log.Fatal(app.Listen(":" + cfg.Port))
}
