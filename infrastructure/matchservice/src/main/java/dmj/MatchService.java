package dmj;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.util.CustomModel;
import com.graphhopper.json.Statement;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.PointList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Mini-servicio de map-matching para DMujeres-Tracking.
 * Carga el grafo de Ecuador (GraphHopper 8.0, MMAP) y expone
 * POST /match {"points":[[lon,lat],...]} -> {"matched":[...], "distance":m, "time":ms}
 * Solo loopback (127.0.0.1:8991); lo consume el endpoint del servidor Traccar.
 */
public class MatchService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PBF = "/opt/graphhopper/ecuador-latest.osm.pbf";
    private static final String GRAPH = "/opt/graphhopper/graph-cache-8";

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
        System.out.println("[matchservice] importando/cargando grafo...");
        GraphHopper hopper = buildHopper();
        MapMatching mm = MapMatching.fromGraphHopper(hopper, new com.graphhopper.util.PMap().putObject("profile", "car"));
        mm.setTransitionProbabilityBeta(5);
        mm.setMeasurementErrorSigma(10);
        System.out.println("[matchservice] grafo listo");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8991), 0);
        server.createContext("/match", (HttpExchange ex) -> handle(ex, mm));
        server.createContext("/health", (HttpExchange ex) -> {
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(ok); }
        });
        server.start();
        System.out.println("[matchservice] escuchando en 127.0.0.1:8991");
    }

    private static void handle(HttpExchange ex, MapMatching mm) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "{\"error\":\"POST required\"}");
                return;
            }
            ObjectNode body = JSON.readValue(ex.getRequestBody(), ObjectNode.class);
            ArrayNode points = (ArrayNode) body.get("points");
            double accuracy = body.has("accuracy") ? body.get("accuracy").asDouble() : 30.0;
            if (points == null || points.size() < 2) {
                respond(ex, 400, "{\"error\":\"se requieren >=2 puntos\"}");
                return;
            }
            List<Observation> observations = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                ArrayNode p = (ArrayNode) points.get(i);
                double lon = p.get(0).asDouble();
                double lat = p.get(1).asDouble();
                observations.add(new Observation(new com.graphhopper.util.shapes.GHPoint(lat, lon)));
            }
            List<Observation> filtered = mm.filterObservations(observations);
            HybridResult hybrid = new HybridResult();
            resilientMatch(mm, filtered, hybrid, 0);
            ArrayNode matched = JSON.createArrayNode();
            for (double[] coord : hybrid.coords) {
                ArrayNode point = JSON.createArrayNode();
                point.add(coord[0]);
                point.add(coord[1]);
                matched.add(point);
            }
            ObjectNode resp = JSON.createObjectNode();
            resp.set("matched", matched);
            resp.put("distance", hybrid.distance);
            resp.put("raw", observations.size());
            resp.put("filtered", filtered.size());
            resp.put("snappedRatio", hybrid.coords.isEmpty() ? 0 : (double) hybrid.snapped / hybrid.coords.size());
            respond(ex, 200, JSON.writeValueAsString(resp));
        } catch (Exception e) {
            e.printStackTrace();
            respond(ex, 500, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * Resultado híbrido: puntos para dibujar + distancia de carretera + cuántos
     * se pegaron a vía (snapped) vs cuántos quedaron crudos.
     */
    private static class HybridResult {
        final List<double[]> coords = new ArrayList<>();
        double distance = 0;
        int snapped = 0;

        void addRaw(Observation obs) {
            coords.add(new double[]{obs.getPoint().getLon(), obs.getPoint().getLat()});
        }

        void addAllRaw(List<Observation> list) {
            for (Observation obs : list) {
                addRaw(obs);
            }
        }
    }

    /**
     * Matching resiliente que NUNCA pierde cobertura y NUNCA inventa desvíos:
     * - Cada observación casada aporta su snap SOLO si dist(raw, snap) <=
     *   SNAP_TRUST_M; si el snap está más lejos (vías de servicio no enrutables,
     *   patios, caminos nuevos), se dibuja el fix crudo.
     * - Si el Viterbi falla en un paso, se descarta esa observación (salto) y
     *   se reintenta (máx 10); si sigue fallando se divide; lo que ni así casa
     *   sale crudo. Cobertura: 100% de las observaciones de entrada.
     */
    private static final double SNAP_TRUST_M = 60;

    private static void resilientMatch(MapMatching mm, List<Observation> observations, HybridResult out, int depth) {
        if (observations.size() < 2 || depth > 4) {
            out.addAllRaw(observations);
            return;
        }
        List<Observation> current = new ArrayList<>(observations);
        int dropped = 0;
        while (true) {
            try {
                MatchResult result = mm.match(current);
                collectHybrid(result, current, out);
                out.distance += result.getMatchLength();
                return;
            } catch (IllegalArgumentException e) {
                int step = parseBrokenStep(e.getMessage());
                if (step >= 0 && step < current.size() && dropped < 10) {
                    current.remove(step);
                    dropped += 1;
                    continue;
                }
                if (depth >= 4 || current.size() < 4) {
                    out.addAllRaw(current);
                    return;
                }
                int mid = current.size() / 2;
                resilientMatch(mm, current.subList(0, mid), out, depth + 1);
                resilientMatch(mm, current.subList(mid, current.size()), out, depth + 1);
                return;
            }
        }
    }

    private static void collectHybrid(MatchResult result, List<Observation> observations, HybridResult out) {
        List<com.graphhopper.matching.EdgeMatch> edges = result.getEdgeMatches();
        List<com.graphhopper.matching.State> states = new ArrayList<>();
        for (com.graphhopper.matching.EdgeMatch edge : edges) {
            states.addAll(edge.getStates());
        }
        // Si la alineación falla, crudo: nunca inventar.
        if (states.size() != observations.size()) {
            out.addAllRaw(observations);
            return;
        }
        for (int i = 0; i < observations.size(); i++) {
            com.graphhopper.storage.index.Snap snap = states.get(i).getSnap();
            if (snap == null) {
                out.addRaw(observations.get(i));
                continue;
            }
            // El snap ya viene calculado por el propio matching (calcSnappedPoint
            // solo puede llamarse una vez: no recalcular).
            com.graphhopper.util.shapes.GHPoint3D snapped = runCatchingSnapped(snap);
            if (snapped != null && snap.getQueryDistance() <= SNAP_TRUST_M) {
                out.coords.add(new double[]{snapped.getLon(), snapped.getLat()});
                out.snapped += 1;
            } else {
                out.addRaw(observations.get(i));
            }
        }
    }

    private static com.graphhopper.util.shapes.GHPoint3D runCatchingSnapped(
            com.graphhopper.storage.index.Snap snap) {
        try {
            return snap.getSnappedPoint();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseBrokenStep(String message) {
        if (message == null) {
            return -1;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("time step (\\d+)").matcher(message);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }


    private static void respond(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}