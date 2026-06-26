package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"time"

	"dmujeres-traccar/internal/cache"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
)

type ServerHandler struct {
	DB    *pgxpool.Pool
	Redis *cache.RedisClient
}

func NewServerHandler(db *pgxpool.Pool, rdb *cache.RedisClient) *ServerHandler {
	return &ServerHandler{DB: db, Redis: rdb}
}

func (h *ServerHandler) GetServerInfo(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"id":               1,
		"registration":     false,
		"readonly":         false,
		"deviceReadonly":   false,
		"map":              "googleRoad",
		"latitude":         -2.1894,
		"longitude":        -79.8891,
		"zoom":             12,
		"twelveHourFormat": false,
		"version":          "6.2",
		"forceSettings":    false,
		"coordinateFormat": "",
		"geocoderEnabled":  true, // Enable geocoder link "Mostrar calle"
		"attributes": fiber.Map{
			"googleKey": "AIzaSyD2hKDNTxveRoCj08_HFR8Ciz4RWEXwBqA",
			"speedUnit": "kmh",
			"timezone":  "America/Guayaquil",
		},
	})
}

func (h *ServerHandler) Geocode(c *fiber.Ctx) error {
	latStr := c.Query("latitude")
	lonStr := c.Query("longitude")
	if latStr == "" || lonStr == "" {
		return c.Status(fiber.StatusBadRequest).SendString("Missing latitude or longitude")
	}

	latVal, _ := strconv.ParseFloat(latStr, 64)
	lonVal, _ := strconv.ParseFloat(lonStr, 64)

	redisKey := fmt.Sprintf("geocode:%.5f:%.5f", latVal, lonVal)
	ctx := context.Background()

	// Layer 1: Check Redis cache
	if h.Redis != nil {
		if cachedAddr, err := h.Redis.Client.Get(ctx, redisKey).Result(); err == nil && cachedAddr != "" {
			return c.SendString(cachedAddr)
		}
	}

	// Layer 2: Check database (tc_positions) for coordinates within ~5 meters tolerance
	var dbAddress string
	err := h.DB.QueryRow(ctx,
		`SELECT address FROM tc_positions 
		 WHERE latitude BETWEEN $1 - 0.00005 AND $1 + 0.00005 
		   AND longitude BETWEEN $2 - 0.00005 AND $2 + 0.00005 
		   AND address IS NOT NULL AND address <> '' 
		 ORDER BY (ABS(latitude - $1) + ABS(longitude - $2)) ASC 
		 LIMIT 1`, latVal, lonVal).Scan(&dbAddress)

	if err == nil && dbAddress != "" {
		// Cache in Redis for next time
		if h.Redis != nil {
			_ = h.Redis.Client.Set(ctx, redisKey, dbAddress, 30*24*time.Hour).Err()
		}
		return c.SendString(dbAddress)
	}

	// Layer 3: Call external Google Maps API
	// User's Google Maps API Key
	googleKey := "AIzaSyD2hKDNTxveRoCj08_HFR8Ciz4RWEXwBqA"
	url := fmt.Sprintf("https://maps.googleapis.com/maps/api/geocode/json?latlng=%f,%f&key=%s&hl=es", latVal, lonVal, googleKey)

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		log.Printf("Geocode API request failed: %v", err)
		return c.Status(fiber.StatusInternalServerError).SendString("Geocode request failed")
	}
	defer resp.Body.Close()

	var result struct {
		Results []struct {
			FormattedAddress string `json:"formatted_address"`
		} `json:"results"`
		Status string `json:"status"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		log.Printf("Geocode JSON decode failed: %v", err)
		return c.Status(fiber.StatusInternalServerError).SendString("Geocode decode failed")
	}

	if len(result.Results) > 0 {
		address := result.Results[0].FormattedAddress

		// Cache in Redis
		if h.Redis != nil {
			_ = h.Redis.Client.Set(ctx, redisKey, address, 30*24*time.Hour).Err()
		}

		// Proactively cache the address in the database for positions with these coordinates
		go func(addr string, lt, ln float64) {
			_, err := h.DB.Exec(context.Background(),
				`UPDATE tc_positions 
				 SET address = $1 
				 WHERE latitude = $2 AND longitude = $3 AND (address IS NULL OR address = '')`,
				addr, lt, ln)
			if err != nil {
				// Don't treat this as critical (e.g. partition could be compressed/locked)
				log.Printf("DB geocode cache update: %v (expected on compressed old chunks)", err)
			}
		}(address, latVal, lonVal)

		return c.SendString(address)
	}

	// Fallback to coordinates
	fallback := fmt.Sprintf("%.5f, %.5f", latVal, lonVal)
	return c.SendString(fallback)
}

func (h *ServerHandler) GetTimezones(c *fiber.Ctx) error {
	timezones := []string{
		"Africa/Abidjan", "Africa/Accra", "Africa/Addis_Ababa", "Africa/Algiers",
		"Africa/Cairo", "Africa/Casablanca", "Africa/Johannesburg", "Africa/Lagos",
		"Africa/Nairobi", "Africa/Tunis",
		"America/Anchorage", "America/Argentina/Buenos_Aires", "America/Bogota",
		"America/Caracas", "America/Chicago", "America/Costa_Rica", "America/Denver",
		"America/El_Salvador", "America/Guatemala", "America/Guayaquil",
		"America/Havana", "America/La_Paz", "America/Lima", "America/Los_Angeles",
		"America/Managua", "America/Manaus", "America/Mexico_City", "America/Monterrey",
		"America/Montevideo", "America/New_York", "America/Panama",
		"America/Phoenix", "America/Santiago", "America/Sao_Paulo",
		"America/St_Johns", "America/Tegucigalpa", "America/Tijuana", "America/Toronto",
		"Asia/Almaty", "Asia/Baghdad", "Asia/Baku", "Asia/Bangkok", "Asia/Beirut",
		"Asia/Calcutta", "Asia/Colombo", "Asia/Damascus", "Asia/Dhaka", "Asia/Dubai",
		"Asia/Hong_Kong", "Asia/Irkutsk", "Asia/Istanbul", "Asia/Jakarta",
		"Asia/Jerusalem", "Asia/Kabul", "Asia/Kamchatka", "Asia/Karachi",
		"Asia/Kathmandu", "Asia/Kolkata", "Asia/Krasnoyarsk", "Asia/Kuala_Lumpur",
		"Asia/Kuwait", "Asia/Magadan", "Asia/Manila", "Asia/Muscat",
		"Asia/Novosibirsk", "Asia/Omsk", "Asia/Riyadh", "Asia/Seoul",
		"Asia/Shanghai", "Asia/Singapore", "Asia/Taipei", "Asia/Tashkent",
		"Asia/Tehran", "Asia/Tokyo", "Asia/Vladivostok", "Asia/Yakutsk",
		"Asia/Yekaterinburg", "Asia/Yerevan",
		"Atlantic/Azores", "Atlantic/Cape_Verde", "Atlantic/Reykjavik",
		"Australia/Adelaide", "Australia/Brisbane", "Australia/Darwin",
		"Australia/Hobart", "Australia/Melbourne", "Australia/Perth", "Australia/Sydney",
		"Europe/Amsterdam", "Europe/Athens", "Europe/Belgrade", "Europe/Berlin",
		"Europe/Brussels", "Europe/Bucharest", "Europe/Budapest", "Europe/Copenhagen",
		"Europe/Dublin", "Europe/Helsinki", "Europe/Kiev", "Europe/Lisbon",
		"Europe/London", "Europe/Madrid", "Europe/Minsk", "Europe/Moscow",
		"Europe/Oslo", "Europe/Paris", "Europe/Prague", "Europe/Riga",
		"Europe/Rome", "Europe/Samara", "Europe/Sofia", "Europe/Stockholm",
		"Europe/Tallinn", "Europe/Vienna", "Europe/Vilnius", "Europe/Warsaw",
		"Europe/Zurich",
		"Pacific/Auckland", "Pacific/Fiji", "Pacific/Guam", "Pacific/Honolulu",
		"Pacific/Midway", "Pacific/Noumea", "Pacific/Pago_Pago", "Pacific/Tongatapu",
		"UTC",
	}
	return c.JSON(timezones)
}
