# Makefile para Dmujeres-Traccar
.PHONY: build build-backend build-frontend deploy restart logs status clean help

# Objetivo por defecto
all: build

# Compilar backend en Go
build-backend:
	@echo "Compilando backend en Go..."
	cd backend && go build -o dmujeres-backend cmd/server/main.go

# Compilar frontend en React
build-frontend:
	@echo "Compilando frontend en React..."
	cd frontend && npm run build

# Compilar todo
build: build-backend build-frontend

# Desplegar configuraciones Nginx y Systemd, recargar daemon y reiniciar servicios
deploy:
	@echo "Desplegando configuración de Nginx..."
	cat /home/Dmujeres-Traccar/nginx/dmujeres.conf > /etc/nginx/sites-enabled/dmujeres
	mkdir -p /var/cache/nginx/google_tiles
	chown -R www-data:www-data /var/cache/nginx/google_tiles
	nginx -t && systemctl restart nginx
	@echo "Desplegando servicio Systemd..."
	cat /home/Dmujeres-Traccar/systemd/dmujeres.service > /etc/systemd/system/dmujeres.service
	systemctl daemon-reload
	systemctl enable dmujeres.service
	systemctl restart dmujeres.service
	@echo "Despliegue completado con éxito."

# Reiniciar servicios
restart:
	@echo "Reiniciando servicios..."
	systemctl restart nginx
	systemctl restart dmujeres

# Ver logs del backend Go
logs:
	journalctl -u dmujeres -n 100 -f --no-tail

# Ver estado de los servicios (Go backend, PostgreSQL, Nginx, Redis)
status:
	@echo "=== ESTADO DEL BACKEND GO ==="
	systemctl status dmujeres --no-pager || true
	@echo "\n=== ESTADO DE NGINX ==="
	systemctl status nginx --no-pager || true
	@echo "\n=== ESTADO DE POSTGRESQL ==="
	systemctl status postgresql --no-pager || true
	@echo "\n=== ESTADO DE REDIS ==="
	systemctl status redis-server --no-pager || true

# Limpiar temporales
clean:
	@echo "Limpiando binarios y temporales..."
	rm -f backend/dmujeres-backend
	rm -rf frontend/build

# Ayuda
help:
	@echo "Comandos disponibles:"
	@echo "  make build          - Compila backend y frontend"
	@echo "  make build-backend  - Compila solo el backend de Go"
	@echo "  make build-frontend - Compila solo el frontend de React"
	@echo "  make deploy         - Copia configs de Nginx/Systemd y reinicia servicios (requiere root)"
	@echo "  make restart        - Reinicia Nginx y dmujeres"
	@echo "  make logs           - Muestra logs en tiempo real del backend"
	@echo "  make status         - Muestra el estado de Go, Nginx, PostgreSQL y Redis"
	@echo "  make clean          - Elimina binarios y compilados del frontend"
