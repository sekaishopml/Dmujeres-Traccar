package main

import (
"fmt"
"log"

"github.com/gofiber/fiber/v2"
"github.com/gofiber/fiber/v2/middleware/logger"
)

func main() {
app := fiber.New(fiber.Config{
AppName: "Dmujeres-Traccar API v1",
})

app.Use(logger.New())

// Health check endpoint
app.Get("/health", func(c *fiber.Ctx) error {
return c.JSON(fiber.Map{
"status": "healthy",
"app":    "Dmujeres-Traccar",
})
})

// OsmAnd Ingest protocol endpoint
app.Get("/ingest", func(c *fiber.Ctx) error {
id := c.Query("id")
lat := c.Query("lat")
lon := c.Query("lon")
log.Printf("Received GPS ping: Device=%s, Lat=%s, Lon=%s", id, lat, lon)
return c.SendString("OK")
})

fmt.Println("Starting Dmujeres-Traccar backend on port 8082...")
log.Fatal(app.Listen(":8082"))
}

