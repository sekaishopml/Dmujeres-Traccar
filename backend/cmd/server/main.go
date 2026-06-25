package main

import (
        "fmt"
        "log"

        "dmujeres-traccar/internal/cache"
        "dmujeres-traccar/internal/config"
        "dmujeres-traccar/internal/db"
        "dmujeres-traccar/internal/handler"
        "dmujeres-traccar/internal/ws"

        "github.com/gofiber/fiber/v2"
        "github.com/gofiber/fiber/v2/middleware/logger"
        "github.com/gofiber/websocket/v2"
)

func main() {
        cfg := config.LoadConfig()

        databasePool, err := db.ConnectPostgres(cfg.DatabaseURL)
        if err != nil {
                log.Fatalf("Failed to connect to database: %v", err)
        }
        defer databasePool.Close()

        // Connect to Redis
        redisClient, err := cache.ConnectRedis(cfg.RedisURL)
        if err != nil {
                log.Printf("Warning: Failed to connect to Redis cache: %v", err)
        } else {
                defer redisClient.Client.Close()
        }

        // Initialize WebSocket Hub
        hub := ws.NewHub()
        go hub.Run()

        app := fiber.New(fiber.Config{
                AppName: "Dmujeres-Traccar Go API",
        })

        app.Use(logger.New())

        ingestHandler := handler.NewIngestHandler(databasePool, hub)
        sessionHandler := handler.NewSessionHandler(databasePool)
        deviceHandler := handler.NewDeviceHandler(databasePool)
        positionHandler := handler.NewPositionHandler(databasePool)
        reportHandler := handler.NewReportHandler(databasePool)
        userHandler := handler.NewUserHandler(databasePool)

        app.Get("/health", func(c *fiber.Ctx) error {
                return c.JSON(fiber.Map{
                        "status": "healthy",
                        "app":    "Dmujeres-Traccar Backend",
                })
        })

        // Ingest endpoint (OsmAnd)
        app.Get("/ingest", ingestHandler.HandleOsmAnd)
        app.Get("/", ingestHandler.HandleOsmAnd)

        // Development tiles fallback (302 redirect)
        app.Get("/google-tiles", func(c *fiber.Ctx) error {
                lyrs := c.Query("lyrs")
                x := c.Query("x")
                y := c.Query("y")
                z := c.Query("z")
                url := fmt.Sprintf("https://mt1.google.com/vt/lyrs=%s&hl=es&x=%s&y=%s&z=%s&s=Ga", lyrs, x, y, z)
                return c.Redirect(url, 302)
        })

        // WebSocket real-time connection endpoint
        app.Get("/api/socket", websocket.New(func(c *websocket.Conn) {
                client := &ws.Client{Hub: hub, Conn: c, Send: make(chan []byte, 256)}
                hub.Register <- client
                go client.WritePump()
                client.ReadPump()
        }))

        api := app.Group("/api")

        api.Get("/session", sessionHandler.GetSession)
        api.Post("/session", sessionHandler.Login)
        api.Delete("/session", sessionHandler.Logout)

        api.Get("/server", handler.GetServerInfo)

        api.Get("/devices", deviceHandler.GetDevices)
        api.Post("/devices", deviceHandler.CreateDevice)
        api.Delete("/devices/:id", deviceHandler.DeleteDevice)

        api.Get("/positions", positionHandler.GetPositions)

        // Reports
        api.Get("/reports/route", reportHandler.GetRouteReport)

        // Users CRUD
        api.Get("/users", userHandler.GetUsers)
        api.Post("/users", userHandler.CreateUser)
        api.Put("/users/:id", userHandler.UpdateUser)
        api.Delete("/users/:id", userHandler.DeleteUser)

        api.Get("/geofences", func(c *fiber.Ctx) error {
                return c.JSON([]interface{}{})
        })
        api.Get("/groups", func(c *fiber.Ctx) error {
                return c.JSON([]interface{}{})
        })
        api.Get("/drivers", func(c *fiber.Ctx) error {
                return c.JSON([]interface{}{})
        })
        api.Get("/maintenance", func(c *fiber.Ctx) error {
                return c.JSON([]interface{}{})
        })
        api.Get("/calendars", func(c *fiber.Ctx) error {
                return c.JSON([]interface{}{})
        })
        api.Get("/notifications/types", func(c *fiber.Ctx) error {
                return c.JSON([]fiber.Map{
                        {"type": "deviceOnline"},
                        {"type": "deviceOffline"},
                        {"type": "deviceMoving"},
                        {"type": "deviceStopped"},
                        {"type": "geofenceEnter"},
                        {"type": "geofenceExit"},
                        {"type": "alarm"},
                })
        })
        api.Get("/notifications/notificators", func(c *fiber.Ctx) error {
                return c.JSON([]fiber.Map{
                        {"type": "web"},
                        {"type": "mail"},
                        {"type": "sms"},
                        {"type": "firebase"},
                        {"type": "telegram"},
                })
        })

        fmt.Printf("Starting Dmujeres-Traccar Go backend on port %s...\n", cfg.Port)
        log.Fatal(app.Listen(":" + cfg.Port))
}
