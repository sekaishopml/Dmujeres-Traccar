package dmj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PointList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mini-servicio de ruteo por calles para los tramos sin datos del Replay.
 *
 * El matcher (/match) solo pega cada fix a la via; para reconstruir un hueco
 * hace falta la ruta entre dos puntos, que es lo que expone este servicio:
 *   POST /route {"from":[lon,lat],"to":[lon,lat]}
 *     -> {"points":[[lon,lat],...],"distance":m,"time":ms}
 *
 * Reutiliza el grafo de Ecuador ya importado (/opt/graphhopper/graph-cache-8)
 * con el mismo perfil "car" del matcher, asi que el trazado sigue las calles
 * con las mismas reglas de circulacion. Solo escucha en loopback y lo consume
 * la API (`services/api/src/ruteo.js`); la web dibuja el resultado como un
 * tramo mas del recorrido.
 */
public class RouteService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PBF = System.getenv().getOrDefault("DMJ_RUTEO_PBF", "/opt/graphhopper/ecuador-latest.osm.pbf");
    private static final String GRAPH = System.getenv().getOrDefault("DMJ_RUTEO_GRAFO", "/opt/graphhopper/graph-cache-8");
    private static final int PUERTO = Integer.parseInt(System.getenv().getOrDefault("DMJ_RUTEO_PUERTO", "8992"));

    private static GraphHopper buildHopper() {
        GraphHopperConfig cfg = new GraphHopperConfig();
        cfg.putObject("datareader.file", PBF);
        cfg.putObject("graph.location", GRAPH);
        cfg.putObject("graph.dataaccess", "MMAP");
        cfg.putObject("import.osm.ignored_highways", "proposed,raceway,bus_guideway");
        cfg.putObject("graph.vehicles", "car");
        cfg.putObject("graph.encoded_values", "road_access, max_speed, ferry_speed, road_class, road_environment, surface");
        CustomModel cm = new CustomModel();
        cm.setDistanceInfluence(70.0);
        cm.addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"));
        cm.addToSpeed(Statement.If("road_environment == FERRY", Statement.Op.LIMIT, "ferry_speed"));
        cm.addToSpeed(Statement.Else(Statement.Op.LIMIT, "car_average_speed"));
        cm.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "max_speed * 0.9"));
        cfg.setProfiles(List.of(new Profile("car").setVehicle("car").setWeighting("custom").setCustomModel(cm).setTurnCosts(false)));
        GraphHopper hopper = new GraphHopper().init(cfg);
        hopper.importOrLoad();
        return hopper;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[ruteo] importando/cargando grafo...");
        GraphHopper hopper = buildHopper();
        System.out.println("[ruteo] grafo listo");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PUERTO), 0);
        // La API pide varios tramos en paralelo por cada Replay: cuatro hilos
        // alcanzan para que el grafo no se convierta en cuello de botella.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.createContext("/health", (HttpExchange ex) -> responder(ex, 200, "ok", false));
        server.createContext("/route", (HttpExchange ex) -> manejar(ex, hopper));
        server.start();
        System.out.println("[ruteo] escuchando en 127.0.0.1:" + PUERTO);
    }

    private static void manejar(HttpExchange ex, GraphHopper hopper) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                responder(ex, 405, "{\"error\":\"POST required\"}", true);
                return;
            }
            ObjectNode body = JSON.readValue(ex.getRequestBody(), ObjectNode.class);
            double[] desde = leerPunto(body.get("from"));
            double[] hasta = leerPunto(body.get("to"));
            if (desde == null || hasta == null) {
                responder(ex, 400, "{\"error\":\"from y to son [lon,lat] requeridos\"}", true);
                return;
            }
            GHRequest pedido = new GHRequest(desde[1], desde[0], hasta[1], hasta[0]).setProfile("car");
            GHResponse respuesta = hopper.route(pedido);
            if (respuesta.hasErrors()) {
                responder(ex, 422, "{\"error\":\"sin ruta entre los puntos\"}", true);
                return;
            }
            ResponsePath ruta = respuesta.getBest();
            PointList puntos = ruta.getPoints();
            ArrayNode salida = JSON.createArrayNode();
            for (int i = 0; i < puntos.size(); i++) {
                ArrayNode punto = JSON.createArrayNode();
                punto.add(puntos.getLon(i));
                punto.add(puntos.getLat(i));
                salida.add(punto);
            }
            ObjectNode cuerpo = JSON.createObjectNode();
            cuerpo.set("points", salida);
            cuerpo.put("distance", ruta.getDistance());
            cuerpo.put("time", ruta.getTime());
            responder(ex, 200, JSON.writeValueAsString(cuerpo), true);
        } catch (Exception e) {
            e.printStackTrace();
            responder(ex, 500, "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}", true);
        }
    }

    /** [lon, lat] finito dentro de rango; null si el nodo no sirve. */
    private static double[] leerPunto(JsonNode nodo) {
        if (nodo == null || !nodo.isArray() || nodo.size() < 2) return null;
        double lon = nodo.get(0).asDouble(Double.NaN);
        double lat = nodo.get(1).asDouble(Double.NaN);
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) return null;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        return new double[]{lon, lat};
    }

    private static void responder(HttpExchange ex, int codigo, String cuerpo, boolean json) throws IOException {
        byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", json ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(codigo, datos.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(datos);
        }
    }
}
